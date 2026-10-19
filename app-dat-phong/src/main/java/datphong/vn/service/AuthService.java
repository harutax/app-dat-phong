package datphong.vn.service;

import datphong.vn.config.AppProperties;
import datphong.vn.config.JwtProperties;
import datphong.vn.constant.RoleName;
import datphong.vn.constant.UserStatus;
import datphong.vn.domain.HmacSigner;
import datphong.vn.dto.request.AuthRequests;
import datphong.vn.dto.response.AuthResponses;
import datphong.vn.exception.entity.RefreshToken;
import datphong.vn.exception.entity.Role;
import datphong.vn.exception.entity.User;
import datphong.vn.exception.entity.VerificationToken;
import datphong.vn.exception.BusinessException;
import datphong.vn.exception.ErrorCode;
import datphong.vn.repository.*;
import datphong.vn.security.JwtService;
import datphong.vn.util.Texts;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/** UC-01 (đăng ký/xác minh) và UC-02 (đăng nhập, refresh rotation, đăng xuất). */
@Slf4j
@Service
@RequiredArgsConstructor
public class AuthService {

    private static final String PURPOSE = "EMAIL_VERIFY";

    private final UserRepository users;
    private final RoleRepository roles;
    private final VerificationTokenRepository vTokens;
    private final RefreshTokenRepository refreshTokens;
    private final PasswordEncoder encoder;
    private final JwtService jwt;
    private final JwtProperties jwtProps;
    private final AppProperties app;
    private final ConfigService config;
    private final PasswordPolicy passwordPolicy;
    private final OutboxService outbox;
    private final RateLimitService rateLimit;
    private final AuditService audit;
    private final Clock clock;

    // ------------------------------------------------------------------ UC-01
    @Transactional
    public AuthResponses.Profile register(AuthRequests.Register req, String ip) {
        rateLimit.check("register:" + ip, 10, Duration.ofMinutes(10));
        String email = Texts.normEmail(req.email());
        passwordPolicy.validate(req.password());
        if (users.existsByEmail(email)) throw new BusinessException(ErrorCode.EMAIL_ALREADY_EXISTS, "Email đã được sử dụng");

        Role customer = roles.findByCode(RoleName.CUSTOMER.name()).orElseThrow();
        User u = new User();
        u.setEmail(email);
        u.setPasswordHash(encoder.encode(req.password()));
        u.setFullName(req.fullName().trim());
        u.setPhone(req.phone());
        u.setStatus(UserStatus.PENDING_VERIFICATION);   // status và role KHÔNG nhận từ request
        u.getRoles().add(customer);
        try {
            users.saveAndFlush(u);                        // unique lower(email) chặn hai request đồng thời
        } catch (DataIntegrityViolationException e) {
            throw new BusinessException(ErrorCode.EMAIL_ALREADY_EXISTS, "Email đã được sử dụng");
        }
        String code = issueCode(u);
        publishVerificationEvents(u, code, true);
        audit.record("USER_REGISTER", "USER", u.getId(), null, u.getStatus().name(), null, null);
        return profile(u);
    }

    private String issueCode(User u) {
        String code = Texts.numericCode(6);
        VerificationToken t = new VerificationToken();
        t.setUserId(u.getId());
        t.setPurpose(PURPOSE);
        t.setTokenHash(hashCode(u.getId(), code));
        t.setExpiresAt(clock.instant().plus(Duration.ofMinutes(config.getInt("auth.verify.ttlMinutes"))));
        t.setCreatedAt(clock.instant());
        vTokens.save(t);
        return code;
    }

    private void publishVerificationEvents(User u, String code, boolean created) {
        // Mã xác minh chỉ đi vào payload snapshot của mail (cần cho retry); không vào log/audit.
        Map<String, Object> payload = Map.of("userId", u.getId(), "email", u.getEmail(), "customerName", u.getFullName(),
                "maskedEmail", Texts.maskEmail(u.getEmail()),
                "verificationCode", code,
                "verificationLink", app.getPublicBaseUrl() + "/verify-email?email=" + u.getEmail() + "&code=" + code);
        if (created) outbox.publish("ACCOUNT_CREATED:" + u.getId(), "ACCOUNT_CREATED", "USER", u.getId(), payload);
        outbox.publish("EMAIL_VERIFICATION:" + u.getId() + ":" + code.hashCode() + ":" + clock.millis(), "EMAIL_VERIFICATION", "USER", u.getId(), payload);
    }

    private String hashCode(Long userId, String code) {
        return HmacSigner.sign(app.getVerificationSecret(), userId + ":" + code);
    }

    /** noRollbackFor: số lần thử sai phải được lưu dù trả lỗi. */
    @Transactional(noRollbackFor = BusinessException.class)
    public void verify(AuthRequests.Verify req) {
        User u = users.findByEmail(Texts.normEmail(req.email()))
                .orElseThrow(() -> new BusinessException(ErrorCode.VERIFICATION_INVALID, "Mã xác minh không hợp lệ"));
        if (u.getStatus() == UserStatus.ACTIVE) return; // idempotent
        VerificationToken t = vTokens.findFirstByUserIdAndPurposeAndUsedAtIsNullOrderByCreatedAtDesc(u.getId(), PURPOSE)
                .orElseThrow(() -> new BusinessException(ErrorCode.VERIFICATION_INVALID, "Mã xác minh không hợp lệ"));
        if (t.getAttempts() >= config.getInt("auth.verify.maxAttempts")) {
            throw new BusinessException(ErrorCode.RATE_LIMITED, "Đã nhập sai quá số lần cho phép, hãy yêu cầu mã mới");
        }
        if (t.getExpiresAt().isBefore(clock.instant())) throw new BusinessException(ErrorCode.VERIFICATION_EXPIRED, "Mã xác minh đã hết hạn");
        if (!HmacSigner.verify(app.getVerificationSecret(), u.getId() + ":" + req.code(), t.getTokenHash())) {
            t.setAttempts(t.getAttempts() + 1);
            throw new BusinessException(ErrorCode.VERIFICATION_INVALID, "Mã xác minh không hợp lệ");
        }
        t.setUsedAt(clock.instant());                    // thu hồi mã đã dùng
        u.setStatus(UserStatus.ACTIVE);
        u.setVerifiedAt(clock.instant());
        audit.record("USER_VERIFY", "USER", u.getId(), UserStatus.PENDING_VERIFICATION.name(), UserStatus.ACTIVE.name(), null, null);
    }

    @Transactional
    public void resend(String emailRaw) {
        String email = Texts.normEmail(emailRaw);
        rateLimit.check("resend:" + email, 3, Duration.ofSeconds(config.getInt("auth.verify.resendCooldownSeconds")));
        // Chống enumeration: luôn trả thành công dù email không tồn tại / đã xác minh.
        users.findByEmail(email).filter(u -> u.getStatus() == UserStatus.PENDING_VERIFICATION).ifPresent(u -> {
            vTokens.findFirstByUserIdAndPurposeAndUsedAtIsNullOrderByCreatedAtDesc(u.getId(), PURPOSE)
                    .ifPresent(old -> old.setUsedAt(clock.instant())); // vô hiệu mã cũ, không dùng lại token cũ
            publishVerificationEvents(u, issueCode(u), false);
        });
    }

    // ------------------------------------------------------------------ UC-02
    @Transactional(noRollbackFor = BusinessException.class)
    public AuthResponses.Tokens login(AuthRequests.Login req, String ip, String device) {
        String email = Texts.normEmail(req.email());
        rateLimit.check("login:ip:" + ip, 30, Duration.ofMinutes(5));
        rateLimit.check("login:email:" + email, 10, Duration.ofMinutes(5));

        User u = users.findWithAuthoritiesByEmail(email).orElse(null);
        Instant now = clock.instant();
        if (u == null) {
            encoder.matches(req.password(), "$2a$11$0000000000000000000000000000000000000000000000000000"); // làm đều thời gian
            log.info("login failure (unknown email) ip={}", ip);
            throw new BusinessException(ErrorCode.UNAUTHORIZED, "Thông tin đăng nhập không chính xác");
        }
        if (u.getLockedUntil() != null && u.getLockedUntil().isAfter(now)) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED, "Thông tin đăng nhập không chính xác");
        }
        if (!encoder.matches(req.password(), u.getPasswordHash())) {
            u.setFailedLoginCount(u.getFailedLoginCount() + 1);
            if (u.getFailedLoginCount() >= config.getInt("auth.maxFailedLogin")) {
                u.setLockedUntil(now.plus(Duration.ofMinutes(config.getInt("auth.lockMinutes"))));
                u.setFailedLoginCount(0);
            }
            log.info("login failure userId={} ip={}", u.getId(), ip);
            throw new BusinessException(ErrorCode.UNAUTHORIZED, "Thông tin đăng nhập không chính xác");
        }
        if (u.getStatus() == UserStatus.PENDING_VERIFICATION) {
            throw new BusinessException(ErrorCode.ACCOUNT_NOT_VERIFIED, "Tài khoản chưa xác minh email");
        }
        if (u.getStatus() != UserStatus.ACTIVE) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED, "Thông tin đăng nhập không chính xác");
        }
        u.setFailedLoginCount(0);
        u.setLockedUntil(null);
        log.info("login success userId={} traceId={}", u.getId(), MDC.get("traceId"));
        return issueTokens(u, Texts.uuid(), device);
    }

    @Transactional(noRollbackFor = BusinessException.class)
    public AuthResponses.Tokens refresh(String rawToken, String device) {
        String hash = HmacSigner.sha256Hex(rawToken);
        RefreshToken t = refreshTokens.findByTokenHash(hash)
                .orElseThrow(() -> new BusinessException(ErrorCode.UNAUTHORIZED, "Phiên không hợp lệ"));
        Instant now = clock.instant();
        if (t.getRevokedAt() != null) {
            // Token đã rotate mà bị dùng lại -> nghi bị đánh cắp: thu hồi cả họ phiên.
            if (t.getReplacedByHash() != null) {
                refreshTokens.revokeFamily(t.getFamilyId(), now);
                log.warn("refresh token reuse detected userId={}", t.getUserId());
            }
            throw new BusinessException(ErrorCode.UNAUTHORIZED, "Phiên không hợp lệ");
        }
        if (t.getExpiresAt().isBefore(now)) throw new BusinessException(ErrorCode.UNAUTHORIZED, "Phiên đã hết hạn");
        User u = users.findWithAuthorities(t.getUserId()).orElseThrow(() -> new BusinessException(ErrorCode.UNAUTHORIZED, "Phiên không hợp lệ"));
        if (u.getStatus() != UserStatus.ACTIVE) throw new BusinessException(ErrorCode.UNAUTHORIZED, "Phiên không hợp lệ");

        AuthResponses.Tokens out = issueTokens(u, t.getFamilyId(), device);
        t.setRevokedAt(now);
        t.setReplacedByHash(HmacSigner.sha256Hex(out.refreshToken()));
        return out;
    }

    @Transactional
    public void logout(Long userId, AuthRequests.Logout req) {
        Instant now = clock.instant();
        if (req.refreshToken() != null && !req.refreshToken().isBlank()) {
            refreshTokens.findByTokenHash(HmacSigner.sha256Hex(req.refreshToken()))
                    .filter(t -> t.getUserId().equals(userId))
                    .ifPresent(t -> refreshTokens.revokeFamily(t.getFamilyId(), now));
        }
        if (req.allDevices()) {
            User u = users.findById(userId).orElseThrow();
            u.setTokenVersion(u.getTokenVersion() + 1);   // access token đang sống bị từ chối ngay
            refreshTokens.revokeAllForUser(userId, now);
        }
    }

    private AuthResponses.Tokens issueTokens(User u, String familyId, String device) {
        Instant now = clock.instant();
        String raw = Texts.randomToken(48);
        RefreshToken rt = new RefreshToken();
        rt.setUserId(u.getId());
        rt.setTokenHash(HmacSigner.sha256Hex(raw));
        rt.setFamilyId(familyId);
        rt.setExpiresAt(now.plusMillis(jwtProps.getRefreshTokenExpiration()));
        rt.setDevice(device == null ? null : device.substring(0, Math.min(200, device.length())));
        rt.setCreatedAt(now);
        refreshTokens.save(rt);
        // Giới hạn số phiên: thu hồi phiên cũ nhất khi vượt
        List<RefreshToken> active = refreshTokens.findActive(u.getId(), now);
        for (int i = 0; i < active.size() - jwtProps.getMaxActiveRefreshTokensPerUser(); i++) {
            active.get(i).setRevokedAt(now);
        }
        return new AuthResponses.Tokens(jwt.generateAccessToken(u.getId(), u.getTokenVersion()), raw, "Bearer",
                jwt.accessTtlSeconds(), profile(u));
    }

    public static AuthResponses.Profile profile(User u) {
        Set<String> r = u.getRoles().stream().map(Role::getCode).collect(Collectors.toCollection(HashSet::new));
        return new AuthResponses.Profile(u.getId(), u.getEmail(), u.getFullName(), u.getPhone(), u.getAvatarUrl(),
                u.getStatus().name(), r, u.getVersion());
    }
}
