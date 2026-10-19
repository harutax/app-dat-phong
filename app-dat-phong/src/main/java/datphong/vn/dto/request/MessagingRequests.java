package datphong.vn.dto.request;

import jakarta.validation.constraints.*;

import java.time.Instant;

public final class MessagingRequests {
    private MessagingRequests() {}

    /** hostId KHÔNG nhận từ client: lấy từ chủ sở hữu thực của property. */
    public record OpenConversation(@NotNull Long propertyId, Long bookingId, @NotBlank String type,
                                   @NotBlank @Size(max = 64) String clientMessageId,
                                   @NotBlank @Size(max = 2000) String firstMessage) {}

    public record SendMessage(@NotBlank @Size(max = 64) String clientMessageId, @NotBlank @Size(max = 2000) String body, String type) {}

    public record MarkRead(@NotNull @Min(0) Long seq) {}

    public record Complaint(@NotBlank @Size(max = 40) String category, @NotBlank String severity,
                            @NotBlank @Size(max = 2000) String description, @NotBlank @Size(max = 64) String clientMessageId) {}

    public record TicketAction(@NotBlank String action, @Size(max = 1000) String note) {}

    public record CheckinCreate(@NotNull Instant proposedAt, @Size(max = 500) String note) {}

    public record CheckinRespond(@NotBlank String decision, Instant counterAt, @Size(max = 500) String note, @NotNull Long version) {}
}
