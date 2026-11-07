package app.fuelog.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import jakarta.validation.constraints.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** 정비 계약. Instant는 UTC 문자열, Long 관측 시각은 epoch milliseconds, LocalDate는 날짜만 표현한다. */
public final class MaintenanceDtos {
    private MaintenanceDtos() {}

    public enum Basis {
        USER,
        MANUFACTURER
    }

    public enum Status {
        CONFIRMED,
        VISIT_ESTIMATE
    }

    public enum Quality {
        VALID,
        UNSUPPORTED,
        ERROR
    }

    @Schema(name = "MaintenanceOdometerInput")
    public record OdometerInput(
            @Schema(description = "차량 전체 누적거리(km). VALID일 때 필수, ERROR/UNSUPPORTED이면 null.")
                    @DecimalMin("0")
                    @Digits(integer = 9, fraction = 3)
                    BigDecimal km,
            @NotNull
                    @Positive
                    @Schema(
                            description = "누적거리를 측정한 시각. UTC epoch milliseconds.",
                            example = "1793940000000")
                    Long observedAt,
            @Schema(description = "km 고정.") @NotBlank @Pattern(regexp = "km") String unit,
            @Schema(description = "PLEOS_VEHICLE_API 고정. 문자열 자체는 하드웨어 진위 증명이 아닙니다.")
                    @NotBlank
                    @Pattern(regexp = "PLEOS_VEHICLE_API")
                    String source,
            @Schema(description = "VALID: 정상 측정 / UNSUPPORTED: 미지원 / ERROR: 측정 오류.") @NotNull
                    Quality quality) {}

    @Schema(name = "MaintenanceOdometerSaved")
    public record OdometerSaved(
            @Schema(description = "원본 누적거리 샘플 UUID. 재전송 시 유지합니다.") UUID sampleId,
            @Schema(description = "서버 수신 시각. UTC ISO-8601 문자열.") Instant receivedAt) {}

    @Schema(name = "MaintenanceOdometer")
    public record Odometer(BigDecimal km, Long observedAt, String status) {}

    @Schema(name = "MaintenanceSummaryItem")
    public record SummaryItem(
            UUID id,
            @Schema(description = "사용자가 표시할 정비 항목 이름.") String name,
            LocalDate lastServiceDate,
            BigDecimal lastServiceKm,
            LocalDate nextDueDate,
            BigDecimal nextDueKm,
            @Schema(description = "USER는 사용자 기준, MANUFACTURER는 검증된 제조사 근거 기준.") Basis basis,
            Boolean dateDue,
            Boolean distanceDue,
            BigDecimal remainingKm) {}

    @Schema(name = "MaintenanceSummary")
    public record Summary(
            @Schema(description = "서버 저장 데이터의 버전. 변경할 때 증가합니다.") long revision,
            Odometer odometer,
            List<SummaryItem> items) {}

    @Schema(name = "MaintenancePlanInput")
    public record PlanInput(
            @Schema(description = "사용자가 표시할 정비 항목 이름.") @NotBlank @Size(max = 100) String name,
            @Schema(description = "true이면 차량 공동 항목, false이면 본인 개인 항목.") @NotNull Boolean shared,
            @Schema(description = "USER는 사용자 기준, MANUFACTURER는 검증된 제조사 근거 기준.") @NotNull
                    Basis basis,
            @Schema(description = "제조사 기준 주기의 검증 근거 UUID. USER 기준에는 보내지 않습니다.") UUID evidenceId,
            @Schema(description = "정비 반복 주기(km). intervalMonths와 둘 중 하나는 필요합니다.")
                    @DecimalMin(value = "0", inclusive = false)
                    @Digits(integer = 9, fraction = 3)
                    BigDecimal intervalKm,
            @Schema(description = "정비 반복 주기(달력 개월 수, 1~1200).") @Min(1) @Max(1200)
                    Integer intervalMonths) {}

    @Schema(name = "MaintenanceEvidence")
    public record Evidence(
            UUID id,
            String documentUrl,
            String documentTitle,
            String manufacturer,
            String model,
            int modelYearFrom,
            int modelYearTo,
            String scopeText,
            boolean verified) {}

    @Schema(name = "MaintenancePlan")
    public record Plan(
            UUID id,
            @Schema(description = "사용자가 표시할 정비 항목 이름.") String name,
            @Schema(description = "true이면 차량 공동 항목, false이면 본인 개인 항목.") boolean shared,
            boolean active,
            @Schema(description = "USER는 사용자 기준, MANUFACTURER는 검증된 제조사 근거 기준.") Basis basis,
            Evidence evidence,
            @Schema(description = "정비 반복 주기(km). intervalMonths와 둘 중 하나는 필요합니다.")
                    BigDecimal intervalKm,
            @Schema(description = "정비 반복 주기(달력 개월 수, 1~1200).") Integer intervalMonths,
            @Schema(description = "서버 저장 데이터의 버전. 변경할 때 증가합니다.") long revision,
            LocalDate lastServiceDate,
            BigDecimal lastServiceKm,
            LocalDate nextDueDate,
            BigDecimal nextDueKm) {}

    @Schema(name = "MaintenancePlans")
    public record Plans(List<Plan> items) {}

    @Schema(name = "MaintenanceEventInput")
    public record EventInput(
            @Schema(description = "정비 계획 UUID.") @NotNull UUID itemId,
            @Schema(description = "정비일(YYYY-MM-DD). odometerKm와 둘 중 하나는 필요합니다.")
                    LocalDate serviceDate,
            @Schema(description = "정비 당시 차량 전체 누적거리(km).")
                    @DecimalMin("0")
                    @Digits(integer = 9, fraction = 3)
                    BigDecimal odometerKm,
            @Schema(description = "CONFIRMED: 확정 정비 / VISIT_ESTIMATE: 방문 추정. 확정 기록만 예정 계산에 사용합니다.")
                    @NotNull
                    Status status,
            @Schema(description = "true이면 차량 공동 항목, false이면 본인 개인 항목.") @NotNull Boolean shared,
            @Schema(description = "비용·메모·정비소·영수증 상세를 함께 공개할지 여부.") @NotNull Boolean detailsShared,
            @Schema(description = "사용자가 기록한 정비 금액. 입력 시 currency도 필요합니다.")
                    @DecimalMin("0")
                    @Digits(integer = 12, fraction = 2)
                    BigDecimal cost,
            @Schema(description = "ISO 4217 통화 코드. 예: KRW.") @Pattern(regexp = "[A-Z]{3}")
                    String currency,
            @Schema(description = "정비 메모.") @Size(max = 2000) String memo,
            @Schema(description = "정비소 이름.") @Size(max = 200) String shop,
            @Schema(description = "영수증 HTTPS 주소. 사용자 정보가 포함된 URL은 허용하지 않습니다.") @Size(max = 1000)
                    String receiptUrl) {}

    @Schema(name = "MaintenancePrivateDetails")
    public record PrivateDetails(
            @Schema(description = "사용자가 기록한 정비 금액. 입력 시 currency도 필요합니다.") BigDecimal cost,
            @Schema(description = "ISO 4217 통화 코드. 예: KRW.") String currency,
            @Schema(description = "정비 메모.") String memo,
            @Schema(description = "정비소 이름.") String shop,
            @Schema(description = "영수증 HTTPS 주소. 사용자 정보가 포함된 URL은 허용하지 않습니다.") String receiptUrl) {}

    @Schema(name = "MaintenanceEvent")
    public record Event(
            UUID id,
            @Schema(description = "정비 계획 UUID.") UUID itemId,
            @Schema(description = "정비일(YYYY-MM-DD). odometerKm와 둘 중 하나는 필요합니다.")
                    LocalDate serviceDate,
            @Schema(description = "정비 당시 차량 전체 누적거리(km).") BigDecimal odometerKm,
            Status status,
            @Schema(description = "true이면 차량 공동 항목, false이면 본인 개인 항목.") boolean shared,
            @Schema(description = "비용·메모·정비소·영수증 상세를 함께 공개할지 여부.") boolean detailsShared,
            @Schema(description = "서버 저장 데이터의 버전. 변경할 때 증가합니다.") long revision,
            PrivateDetails details) {}

    @Schema(name = "MaintenanceEvents")
    public record Events(List<Event> items, UUID nextCursor) {}

    @Schema(name = "MaintenanceDeleted")
    public record Deleted(
            UUID id,
            @Schema(description = "서버 저장 데이터의 버전. 변경할 때 증가합니다.") long revision,
            boolean deleted) {}
}
