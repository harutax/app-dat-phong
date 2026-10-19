package datphong.vn.controller;

import datphong.vn.dto.request.AuthRequests;
import datphong.vn.dto.response.AuthResponses;
import datphong.vn.security.CurrentUser;
import datphong.vn.service.AuthService;
import datphong.vn.service.ConfigService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequiredArgsConstructor
public class AuthController {

    private final AuthService auth;
    private final ConfigService config;

    @PostMapping("/auth/register")
    @ResponseStatus(HttpStatus.CREATED)
    public AuthResponses.Profile register(@Valid @RequestBody AuthRequests.Register req, HttpServletRequest http) {
        return auth.register(req, http.getRemoteAddr());
    }

    @PostMapping("/auth/verify")
    public Map<String, String> verify(@Valid @RequestBody AuthRequests.Verify req) {
        auth.verify(req);
        return Map.of("status", "ACTIVE");
    }

    @PostMapping("/auth/resend")
    public Map<String, String> resend(@Valid @RequestBody AuthRequests.Resend req) {
        auth.resend(req.email());
        return Map.of("status", "OK");
    }

    @PostMapping("/auth/login")
    public AuthResponses.Tokens login(@Valid @RequestBody AuthRequests.Login req, HttpServletRequest http) {
        return auth.login(req, http.getRemoteAddr(), http.getHeader("User-Agent"));
    }

    @PostMapping("/auth/refresh")
    public AuthResponses.Tokens refresh(@Valid @RequestBody AuthRequests.Refresh req, HttpServletRequest http) {
        return auth.refresh(req.refreshToken(), http.getHeader("User-Agent"));
    }

    @PostMapping("/auth/logout")
    public Map<String, String> logout(@RequestBody AuthRequests.Logout req) {
        auth.logout(CurrentUser.id(), req);
        return Map.of("status", "OK");
    }

    /** Cấu hình công khai tối thiểu cho form đăng ký (độ dài mật khẩu), không lộ secret. */
    @GetMapping("/public/config")
    public Map<String, String> publicConfig() {
        return config.publicConfig();
    }
}
