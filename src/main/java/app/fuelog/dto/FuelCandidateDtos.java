package app.fuelog.dto;

import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

public final class FuelCandidateDtos {
    private FuelCandidateDtos() {}
    public enum Status { CANDIDATE, CONFIRMED, REJECTED }
    public enum Action { CONFIRM, REJECT }
    // Status, revision and display metadata are client caches, never commands or trusted inputs.
    public record Sync(@NotNull UUID id, @NotNull UUID owner, @NotNull UUID intervalId,
                       @NotNull @PositiveOrZero Long startAt, @NotNull @PositiveOrZero Long endAt,
                       @NotNull @DecimalMin(value="0", inclusive=false) @DecimalMax("100")
                       @Digits(integer=3, fraction=3) BigDecimal increasePercent,
                       Status status, Long revision, @NotNull Boolean queued,
                       Boolean capacityVerified, BigDecimal estimatedLiters, BigDecimal estimatedAmount,
                       Long priceAt, List<String> stationCandidates) {}
    public record View(UUID id, UUID owner, Status status, long revision,
                       boolean capacityVerified, BigDecimal estimatedLiters, BigDecimal estimatedAmount,
                       Long priceAt, List<String> stationCandidates, UUID intervalId,
                       long startAt, long endAt, BigDecimal increasePercent,
                       String correctedPlace, BigDecimal correctedLiters, BigDecimal correctedAmount) {}
    public record Patch(@NotBlank @Size(max=100) String operationId,
                        @NotNull @Positive Long expectedRevision, @NotNull Action action,
                        @Size(max=200) String place,
                        @DecimalMin(value="0", inclusive=false) @Digits(integer=9, fraction=3) BigDecimal liters,
                        @DecimalMin("0") @Digits(integer=13, fraction=2) BigDecimal amount) {}
    public record StationSearch(@NotNull Boolean locationPermission,
                                @DecimalMin("-90") @DecimalMax("90") BigDecimal latitude,
                                @DecimalMin("-180") @DecimalMax("180") BigDecimal longitude,
                                @PositiveOrZero Long observedAt) {}
}
