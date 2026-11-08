package app.fuelog.dto;

import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class ChargeCandidateDtos {
    private ChargeCandidateDtos() {}
    public record Sync(@NotNull UUID id, @NotNull UUID intervalId, @NotNull UUID owner,
            @Positive long startAt, @Positive long endAt,
            @NotNull @DecimalMin(value="0", inclusive=false) @DecimalMax("1000") BigDecimal estimatedKwh,
            @NotBlank @Size(max=100) String basis) {}
    public record Location(@NotNull UUID candidateId, @NotBlank @Size(max=100) String locationOperationId,
            @DecimalMin("-90") @DecimalMax("90") @NotNull Double latitude,
            @DecimalMin("-180") @DecimalMax("180") @NotNull Double longitude,
            @NotNull Instant measuredAt, @NotNull Instant receivedAt,
            @NotBlank @Pattern(regexp="PLEOS_CURRENT_UPDATE") String source) {}
    public record ComparedStation(String stationId, String name, String operatorId, double distanceMeters,
            BigDecimal connectorKw, BigDecimal wonPerKwh, String tariffVersion, String sourceUrl,
            String sourceUpdatedAt, Instant verifiedAt) {}
    public record Scenario(String powerBand, String basis, BigDecimal averageWonPerKwh,
            BigDecimal estimatedAmount, List<ComparedStation> stations) {}
    public record View(UUID id, UUID intervalId, UUID owner, String status, long revision,
            long startAt, long endAt, BigDecimal estimatedKwh, String basis,
            String estimateStatus, List<Scenario> scenarios, BigDecimal correctedKwh,
            BigDecimal correctedAmount, String correctedPlace) {}
    public record Patch(@NotBlank @Size(max=100) String operationId, @Positive long expectedRevision,
            @NotBlank @Pattern(regexp="CONFIRM|REJECT") String action,
            @DecimalMin(value="0",inclusive=false) @DecimalMax("1000") BigDecimal kwh,
            @DecimalMin("0") @Digits(integer=12,fraction=2) BigDecimal amount,
            @Size(max=200) String place) {}
}
