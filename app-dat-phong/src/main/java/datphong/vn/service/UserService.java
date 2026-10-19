package datphong.vn.service;

import datphong.vn.constant.RoleName;
import datphong.vn.constant.UserStatus;
import datphong.vn.dto.request.AuthRequests;
import datphong.vn.dto.response.AuthResponses;
import datphong.vn.exception.entity.Role;
import datphong.vn.exception.entity.User;
import datphong.vn.exception.BusinessException;
import datphong.vn.exception.ErrorCode;
import datphong.vn.repository.RefreshTokenRepository;
import datphong.vn.repository.RoleRepository;
import datphong.vn.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.*;

/** UC-03 (hồ sơ) và UC-20 (quản trị tài khoản/quyền). Cấp quyền tách API/DTO/audit khỏi sửa hồ sơ. */
@Service
@RequiredArgsConstructor
public class UserService {

    private final UserRepository users;
    private final RoleRepository roles;
    private final RefreshTokenRepository refreshTokens;
    private final PasswordEncoder encoder;
    private final PasswordPolicy passwordPolicy;
    private final AuditService audit;
    private final Clock clock;

    @Transactional(readOnly = true)
    public AuthResponses.Profile me(Long userId) {
        return AuthService.profile(users.findWithAuthorities(userId).orElseThrow(() -> BusinessException.notFound("Tài khoản")));
    }

    @Transactional
    public AuthResponses.Profile updateMe(Long userId, AuthRequests.UpdateProfile req) {
        User u = users.findWithAuthorities(userId).orElseThrow(() -> BusinessException.notFound("Tài khoản"));
        if (u.getVersion() != req.version()) throw new BusinessException(ErrorCode.VERSION_CONFLICT, "Hồ sơ đã thay đổi, hãy tải lại");
        List<String> changed = new ArrayList<>();
        if (req.fullName() != null && !req.fullName().isBlank()) { u.setFullName(req.fullName().trim()); changed.add("fullName"); }
        if (req.phone() != null) { u.setPhone(req.phone()); changed.add("phone"); }
        if (req.avatarUrl() != null) { u.setAvatarUrl(req.avatarUrl()); changed.add("avatarUrl"); }
        users.saveAndFlush(u);
        audit.record("USER_PROFILE_UPDATE", "USER", userId, null, null, null, Map.of("changedFields", changed));
        return AuthService.profile(u);
    }

    /** Đổi mật khẩu: xác minh mật khẩu cũ, vô hiệu mọi phiên (tokenVersion + refresh tokens). */
    @Transactional
    public void changePassword(Long userId, AuthRequests.ChangePassword req) {
        User u = users.findById(userId).orElseThrow(() -> BusinessException.notFound("Tài khoản"));
        if (!encoder.matches(req.oldPassword(), u.getPasswordHash())) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "Mật khẩu cũ không đúng");
        }
        passwordPolicy.validate(req.newPassword());
        u.setPasswordHash(encoder.encode(req.newPassword()));
        u.setTokenVersion(u.getTokenVersion() + 1);
        refreshTokens.revokeAllForUser(userId, clock.instant());
        audit.record("USER_PASSWORD_CHANGE", "USER", userId, null, null, null, null);
    }

    // ------------------------------------------------------------ admin
    @Transactional(readOnly = true)
    public Page<AuthResponses.Profile> search(String q, int page, int size) {
        String query = (q == null || q.isBlank()) ? null : q.trim();
        return users.search(query, PageRequest.of(Math.max(0, page), Math.min(Math.max(1, size), 100), Sort.by("id").descending()))
                .map(u -> {
                    u.getRoles().size(); // trong transaction readOnly
                    return AuthService.profile(u);
                });
    }

    @Transactional
    public void setLocked(Long actorId, Long targetId, boolean lock, String reason) {
        if (reason == null || reason.isBlank()) throw BusinessException.validation("Cần nêu lý do");
        if (actorId.equals(targetId) && lock) throw new BusinessException(ErrorCode.BUSINESS_RULE_VIOLATION, "Không thể tự khóa tài khoản của mình");
        User u = users.findWithAuthorities(targetId).orElseThrow(() -> BusinessException.notFound("Tài khoản"));
        UserStatus to = lock ? UserStatus.LOCKED : UserStatus.ACTIVE;
        if (lock && isAdmin(u) && users.countActiveAdmins() <= 1) {
            throw new BusinessException(ErrorCode.BUSINESS_RULE_VIOLATION, "Không thể khóa quản trị viên cuối cùng");
        }
        String from = u.getStatus().name();
        u.setStatus(to);
        if (lock) {
            u.setTokenVersion(u.getTokenVersion() + 1);   // access token chưa hết hạn bị từ chối ngay
            refreshTokens.revokeAllForUser(targetId, clock.instant());
        }
        audit.record(lock ? "USER_LOCK" : "USER_UNLOCK", "USER", targetId, from, to.name(), reason, null);
    }

    @Transactional
    public AuthResponses.Profile setRoles(Long actorId, Long targetId, Set<String> roleCodes, String reason) {
        if (reason == null || reason.isBlank()) throw BusinessException.validation("Cần nêu lý do");
        if (roleCodes == null || roleCodes.isEmpty()) throw BusinessException.validation("Phải có ít nhất một vai trò");
        User u = users.findWithAuthorities(targetId).orElseThrow(() -> BusinessException.notFound("Tài khoản"));
        Set<Role> newRoles = new HashSet<>();
        for (String c : roleCodes) {
            try { RoleName.valueOf(c); } catch (IllegalArgumentException e) { throw BusinessException.validation("Vai trò không hợp lệ: " + c); }
            newRoles.add(roles.findByCode(c).orElseThrow());
        }
        boolean wasAdmin = isAdmin(u);
        boolean willBeAdmin = roleCodes.contains(RoleName.ADMIN.name());
        if (wasAdmin && !willBeAdmin && users.countActiveAdmins() <= 1) {
            throw new BusinessException(ErrorCode.BUSINESS_RULE_VIOLATION, "Không thể thu quyền quản trị viên cuối cùng");
        }
        String before = String.join(",", u.getRoles().stream().map(Role::getCode).sorted().toList());
        u.getRoles().clear();
        u.getRoles().addAll(newRoles);
        u.setTokenVersion(u.getTokenVersion() + 1);        // phạm vi quyền mới có hiệu lực ở request kế tiếp
        users.save(u);
        audit.record("USER_ROLES_CHANGE", "USER", targetId, before, String.join(",", new TreeSet<>(roleCodes)), reason, null);
        return AuthService.profile(u);
    }

    private boolean isAdmin(User u) {
        return u.getRoles().stream().anyMatch(r -> r.getCode().equals(RoleName.ADMIN.name()));
    }
}
