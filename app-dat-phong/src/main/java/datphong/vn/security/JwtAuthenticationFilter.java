package datphong.vn.security;

import datphong.vn.exception.entity.User;
import datphong.vn.repository.UserRepository;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Optional;

/**
 * Xác thực mỗi request: chữ ký + hạn + issuer, rồi đối chiếu DB (trạng thái ACTIVE, token_version, role/permission hiện hành).
 * Token không hợp lệ -> không gán Authentication; endpoint bảo vệ sẽ trả 401 qua EntryPoint.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private final JwtService jwtService;
    private final UserRepository userRepository;

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {
        String h = req.getHeader("Authorization");
        if (h != null && h.startsWith("Bearer ") && SecurityContextHolder.getContext().getAuthentication() == null) {
            try {
                Claims c = jwtService.parse(h.substring(7).trim());
                if ("access".equals(c.get("typ", String.class))) {
                    Long userId = Long.valueOf(c.getSubject());
                    int tv = c.get("tv", Integer.class);
                    authenticate(userId, tv, req);
                }
            } catch (JwtException | IllegalArgumentException ex) {
                log.debug("jwt rejected: {}", ex.getMessage());
            }
        }
        try {
            chain.doFilter(req, res);
        } finally {
            MDC.remove("userId");
        }
    }

    private void authenticate(Long userId, int tv, HttpServletRequest req) {
        Optional<User> opt = userRepository.findWithAuthorities(userId);
        if (opt.isEmpty()) return;
        var u = opt.get();
        if (u.getStatus() != datphong.vn.constant.UserStatus.ACTIVE || u.getTokenVersion() != tv) return;
        var roles = new java.util.HashSet<String>();
        var perms = new java.util.HashSet<String>();
        u.getRoles().forEach(r -> {
            roles.add(r.getCode());
            r.getPermissions().forEach(p -> perms.add(p.getCode()));
        });
        AuthUser principal = new AuthUser(u.getId(), u.getEmail(), u.getFullName(), u.getStatus(), roles, perms);
        var auth = new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities());
        SecurityContextHolder.getContext().setAuthentication(auth);
        MDC.put("userId", String.valueOf(userId));
    }
}
