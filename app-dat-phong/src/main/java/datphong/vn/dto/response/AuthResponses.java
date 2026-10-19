package datphong.vn.dto.response;

import java.util.Set;

public final class AuthResponses {
    private AuthResponses() {}

    public record Tokens(String accessToken, String refreshToken, String tokenType, long expiresInSeconds, Profile user) {}

    public record Profile(Long id, String email, String fullName, String phone, String avatarUrl, String status,
                          Set<String> roles, long version) {}
}
