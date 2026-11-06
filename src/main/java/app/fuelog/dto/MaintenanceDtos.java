package app.fuelog.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public final class MaintenanceDtos {
    private MaintenanceDtos() {}
    public enum Basis { USER, MANUFACTURER }
    public enum Status { CONFIRMED, VISIT_ESTIMATE }
    public enum Quality { VALID, UNSUPPORTED, ERROR }
    public record OdometerInput(
            @DecimalMin("0") @Digits(integer=9, fraction=3) BigDecimal km,
            @NotNull @Positive @Schema(description="Unix epoch milliseconds", example="1793940000000") Long observedAt,
            @NotBlank @Pattern(regexp="km") String unit,
            @NotBlank @Pattern(regexp="PLEOS_VEHICLE_API") String source,
            @NotNull Quality quality) {}
    public record OdometerSaved(UUID sampleId, Instant receivedAt) {}
    public record Odometer(BigDecimal km, Long observedAt, String status) {}
    public record SummaryItem(UUID id, String name, LocalDate lastServiceDate, BigDecimal lastServiceKm,
                              LocalDate nextDueDate, BigDecimal nextDueKm, Basis basis,
                              Boolean dateDue, Boolean distanceDue, BigDecimal remainingKm) {}
    public record Summary(long revision, Odometer odometer, List<SummaryItem> items) {}
    public record PlanInput(@NotBlank @Size(max=100) String name, @NotNull Boolean shared,
                            @NotNull Basis basis, UUID evidenceId,
                            @DecimalMin(value="0", inclusive=false) @Digits(integer=9,fraction=3) BigDecimal intervalKm,
                            @Min(1) @Max(1200) Integer intervalMonths) {}
    public record Evidence(UUID id, String documentUrl, String documentTitle, String manufacturer,
                           String model, int modelYearFrom, int modelYearTo, String scopeText, boolean verified) {}
    public record Plan(UUID id, String name, boolean shared, boolean active, Basis basis, Evidence evidence,
                       BigDecimal intervalKm, Integer intervalMonths, long revision, LocalDate lastServiceDate,
                       BigDecimal lastServiceKm, LocalDate nextDueDate, BigDecimal nextDueKm) {}
    public record Plans(List<Plan> items) {}
    public record EventInput(@NotNull UUID itemId, LocalDate serviceDate,
                             @DecimalMin("0") @Digits(integer=9,fraction=3) BigDecimal odometerKm,
                             @NotNull Status status, @NotNull Boolean shared,
                             @NotNull Boolean detailsShared,
                             @DecimalMin("0") @Digits(integer=12,fraction=2) BigDecimal cost,
                             @Pattern(regexp="[A-Z]{3}") String currency,
                             @Size(max=2000) String memo, @Size(max=200) String shop,
                             @Size(max=1000) String receiptUrl) {}
    public record PrivateDetails(BigDecimal cost, String currency, String memo, String shop, String receiptUrl) {}
    public record Event(UUID id, UUID itemId, LocalDate serviceDate, BigDecimal odometerKm,
                        Status status, boolean shared, boolean detailsShared, long revision, PrivateDetails details) {}
    public record Events(List<Event> items, UUID nextCursor) {}
    public record Deleted(UUID id, long revision, boolean deleted) {}
}
