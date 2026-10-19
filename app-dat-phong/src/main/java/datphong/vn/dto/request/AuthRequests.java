package datphong.vn.dto.request;

import jakarta.validation.constraints.*;

/** DTO riêng cho từng use case: không có role/status/userId nên không thể mass-assignment. */
public final class AuthRequests {
    private AuthRequests() {}

    public record Register(@NotBlank @Email @Size(max = 255) String email,
                           @NotBlank String password,
                           @NotBlank @Size(max = 150) String fullName,
                           @Size(max = 30) @Pattern(regexp = "^[0-9+ ()-]*$", message = "Số điện thoại không hợp lệ") String phone,
                           @AssertTrue(message = "Cần chấp nhận điều khoản") boolean acceptTerms) {}

    public record Verify(@NotBlank @Email String email, @NotBlank @Size(min = 6, max = 6) String code) {}

    public record Resend(@NotBlank @Email String email) {}

    public record Login(@NotBlank @Email String email, @NotBlank String password) {}

    public record Refresh(@NotBlank String refreshToken) {}

    public record Logout(String refreshToken, boolean allDevices) {}

    public record UpdateProfile(@Size(max = 150) String fullName,
                                @Size(max = 30) @Pattern(regexp = "^[0-9+ ()-]*$") String phone,
                                @Size(max = 500) String avatarUrl,
                                @NotNull Long version) {}

    public record ChangePassword(@NotBlank String oldPassword, @NotBlank String newPassword) {}
}
