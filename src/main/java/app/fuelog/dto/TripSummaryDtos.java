package app.fuelog.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class TripSummaryDtos {
    private TripSummaryDtos() {}

    public record Upload(
            @NotNull UUID tripId,
            @NotNull @Positive Long revision,
            @NotBlank @Pattern(regexp = "manual_app") String source,
            @Schema(description = "같은 구간에서 서버가 verify한 C03 candidateId. 미확정 구간은 null") UUID userConsentId,
            @NotNull Instant startedAt, @NotNull Instant endedAt,
            @NotNull Boolean endEstimated,
            @DecimalMin("0") @Digits(integer = 9, fraction = 3) BigDecimal distanceKm,
            @NotNull Boolean distanceValid,
            @DecimalMin("0") @Digits(integer = 7, fraction = 3) BigDecimal startFuelLiters,
            @DecimalMin("0") @Digits(integer = 7, fraction = 3) BigDecimal endFuelLiters,
            @NotNull Boolean fuelEndpointsValid,
            @NotNull Boolean missing, @NotNull Boolean refueled, @NotNull Boolean ownerChanged,
            @DecimalMin("0") @Digits(integer = 9, fraction = 3) BigDecimal unitPrice,
            @Pattern(regexp = "[A-Z]{3}") String currency,
            @Size(max = 200) String priceSource, Instant priceObservedAt,
            @NotNull Boolean priceValid) {}

    public record Synced(UUID tripId, long revision, Instant syncedAt) {}
    public record Metric(BigDecimal value, String status, String reason) {}
    public record Money(BigDecimal value, String currency, String status, String reason) {}
    public record Quality(boolean missing, boolean endEstimated, boolean refueled, boolean ownerChanged,
                          @Schema(description = "현재 연료 끝점은 클라이언트 보고값이며 원시 센서 출처 검증 전입니다", example = "client_reported") String measurementSource) {}
    public record Summary(UUID tripId, long revision, Instant syncedAt, Instant startedAt, Instant endedAt,
                          Metric distanceKm, Metric fuelConsumedLiters, Metric fuelEfficiencyKmPerLiter,
                          Money estimatedCost, String priceSource, Instant priceObservedAt, Quality quality) {}
    public record Deliver(@NotBlank @Size(max = 100) String operationId, @NotNull @Positive Long tripRevision) {}
    public record Delivered(long inboxItemId, UUID tripId, Instant deliveredAt) {}
    public record InboxItem(long inboxItemId, UUID tripId, long tripRevision, Instant deliveredAt,
                            Instant readAt, Summary summary) {}
    public record Inbox(List<InboxItem> items, Long nextCursor) {}
    public record Read(@NotNull Instant readAt) {}
    public record ReadResult(long inboxItemId, Instant readAt) {}
}
