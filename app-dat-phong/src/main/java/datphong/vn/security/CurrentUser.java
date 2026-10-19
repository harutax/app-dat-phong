package datphong.vn.security;

import datphong.vn.exception.BusinessException;
import datphong.vn.exception.ErrorCode;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.Optional;

/** Truy cập người dùng hiện tại từ SecurityContext. userId/role/owner KHÔNG BAO GIỜ lấy từ body/query của client. */
public final class CurrentUser {

    private CurrentUser() {}

    public static Optional<AuthUser> optional() {
        Authentication a = SecurityContextHolder.getContext().getAuthentication();
        if (a != null && a.getPrincipal() instanceof AuthUser u) return Optional.of(u);
        return Optional.empty();
    }

    public static AuthUser require() {
        return optional().orElseThrow(() -> new BusinessException(ErrorCode.UNAUTHORIZED, "Chưa xác thực"));
    }

    public static Long id() {
        return require().getId();
    }
}
