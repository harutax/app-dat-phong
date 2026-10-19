package datphong.vn.controller;

import datphong.vn.dto.request.AuthRequests;
import datphong.vn.dto.response.AuthResponses;
import datphong.vn.security.CurrentUser;
import datphong.vn.service.UserService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/** /users/me: userId lấy từ principal, không từ body. */
@RestController
@RequestMapping("/users/me")
@RequiredArgsConstructor
public class UserController {

    private final UserService users;

    @GetMapping
    public AuthResponses.Profile me() {
        return users.me(CurrentUser.id());
    }

    @PatchMapping
    public AuthResponses.Profile update(@Valid @RequestBody AuthRequests.UpdateProfile req) {
        return users.updateMe(CurrentUser.id(), req);
    }

    @PostMapping("/password")
    public Map<String, String> changePassword(@Valid @RequestBody AuthRequests.ChangePassword req) {
        users.changePassword(CurrentUser.id(), req);
        return Map.of("status", "OK");
    }
}
