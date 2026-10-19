package datphong.vn.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.Map;
import java.util.Set;

public final class AdminRequests {
    private AdminRequests() {}

    public record LockUser(@NotBlank @Size(max = 300) String reason) {}

    public record SetRoles(@NotEmpty Set<String> roles, @NotBlank @Size(max = 300) String reason) {}

    public record UpdateSettings(@NotEmpty Map<String, String> values, Instant effectiveAt, @NotBlank @Size(max = 300) String reason) {}

    public record AmenityUpsert(@NotBlank @Size(max = 40) String code, @NotBlank @Size(max = 100) String name, boolean active) {}
}
