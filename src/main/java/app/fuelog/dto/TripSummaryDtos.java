package app.fuelog.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import jakarta.validation.constraints.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** 운행 요약과 확인함 계약. 시각은 UTC이며 거리·연료 단위는 각각 km·L다. */
public final class TripSummaryDtos {
    private TripSummaryDtos() {}

    @Schema(name = "TripSummaryUpload")
    public record Upload(
            @Schema(description = "운행 UUID. 경로와 본문에 있으면 같은 값이어야 합니다.") @NotNull UUID tripId,
            @Schema(description = "서버 저장 데이터의 버전. 변경할 때 증가합니다.") @NotNull @Positive Long revision,
            @Schema(description = "manual_app 고정. 앱에서 수동 기록한 운행입니다.")
                    @NotBlank
                    @Pattern(regexp = "manual_app")
                    String source,
            @Schema(description = "과거 구간의 C03 candidateId. 새 페어링 사용자 구간은 null이며 소유자는 시작 시 서버에 고정됨")
                    UUID userConsentId,
            @Schema(description = "운행 시작 시각. UTC ISO-8601 문자열.") @NotNull Instant startedAt,
            @Schema(description = "운행 종료 시각. UTC ISO-8601 문자열.") @NotNull Instant endedAt,
            @Schema(description = "정확한 종료 시각 대신 추정 시각을 사용했는지 여부.") @NotNull Boolean endEstimated,
            @Schema(description = "운행 전체 거리(km). 확인할 수 없으면 null.")
                    @DecimalMin("0")
                    @Digits(integer = 9, fraction = 3)
                    BigDecimal distanceKm,
            @Schema(description = "거리 측정값을 계산에 사용할 수 있는지 여부.") @NotNull Boolean distanceValid,
            @Schema(description = "운행 시작 연료량(L). 확인할 수 없으면 null.")
                    @DecimalMin("0")
                    @Digits(integer = 7, fraction = 3)
                    BigDecimal startFuelLiters,
            @Schema(description = "운행 종료 연료량(L). 확인할 수 없으면 null.")
                    @DecimalMin("0")
                    @Digits(integer = 7, fraction = 3)
                    BigDecimal endFuelLiters,
            @Schema(description = "같은 연속 구간의 시작·종료 연료량이 유효한지 여부.") @NotNull
                    Boolean fuelEndpointsValid,
            @Schema(description = "운행 중 기록 누락이 있는지 여부.") @NotNull Boolean missing,
            @Schema(description = "운행 중 주유가 있었는지 여부. true이면 연료 차이로 소비량을 계산하지 않습니다.") @NotNull
                    Boolean refueled,
            @Schema(description = "운행 중 사용자가 변경되었는지 여부.") @NotNull Boolean ownerChanged,
            @Schema(description = "연료 1L당 가격. 모르면 null과 priceValid=false를 보냅니다.")
                    @DecimalMin("0")
                    @Digits(integer = 9, fraction = 3)
                    BigDecimal unitPrice,
            @Schema(description = "ISO 4217 통화 코드. 예: KRW.") @Pattern(regexp = "[A-Z]{3}")
                    String currency,
            @Schema(description = "연료 단가의 확인 출처.") @Size(max = 200) String priceSource,
            @Schema(description = "단가를 확인한 UTC 시각. 운행 종료 시각 이후일 수 없습니다.") Instant priceObservedAt,
            @Schema(description = "단가와 출처·통화·확인 시각이 유효한지 여부.") @NotNull Boolean priceValid) {}

    @Schema(name = "TripSummarySynced")
    public record Synced(
            @Schema(description = "운행 UUID. 경로와 본문에 있으면 같은 값이어야 합니다.") UUID tripId,
            @Schema(description = "서버 저장 데이터의 버전. 변경할 때 증가합니다.") long revision,
            @Schema(description = "서버 저장 완료 시각. UTC ISO-8601 문자열.") Instant syncedAt) {}

    @Schema(name = "TripSummaryMetric")
    public record Metric(BigDecimal value, String status, String reason) {}

    @Schema(name = "TripSummaryMoney")
    public record Money(
            BigDecimal value,
            @Schema(description = "ISO 4217 통화 코드. 예: KRW.") String currency,
            String status,
            String reason) {}

    @Schema(name = "TripSummaryQuality")
    public record Quality(
            boolean missing,
            boolean endEstimated,
            boolean refueled,
            boolean ownerChanged,
            @Schema(
                            description = "현재 연료 끝점은 클라이언트 보고값이며 원시 센서 출처 검증 전입니다",
                            example = "client_reported")
                    String measurementSource) {}

    @Schema(name = "TripSummarySummary")
    public record Summary(
            @Schema(description = "운행 UUID. 경로와 본문에 있으면 같은 값이어야 합니다.") UUID tripId,
            @Schema(description = "서버 저장 데이터의 버전. 변경할 때 증가합니다.") long revision,
            @Schema(description = "서버 저장 완료 시각. UTC ISO-8601 문자열.") Instant syncedAt,
            @Schema(description = "운행 시작 시각. UTC ISO-8601 문자열.") Instant startedAt,
            @Schema(description = "운행 종료 시각. UTC ISO-8601 문자열.") Instant endedAt,
            Metric distanceKm,
            Metric fuelConsumedLiters,
            Metric fuelEfficiencyKmPerLiter,
            Money estimatedCost,
            String priceSource,
            Instant priceObservedAt,
            Quality quality) {}

    @Schema(name = "TripSummaryDeliver")
    public record Deliver(
            @Schema(description = "mobile-inbox:{tripId} 형식의 고정 키.") @NotBlank @Size(max = 100)
                    String operationId,
            @Schema(description = "운행 요약 업로드 성공 응답에서 받은 revision.") @NotNull @Positive
                    Long tripRevision) {}

    @Schema(name = "TripSummaryDelivered")
    public record Delivered(
            long inboxItemId,
            @Schema(description = "운행 UUID. 경로와 본문에 있으면 같은 값이어야 합니다.") UUID tripId,
            Instant deliveredAt) {}

    @Schema(name = "TripSummaryInboxItem")
    public record InboxItem(
            long inboxItemId,
            @Schema(description = "운행 UUID. 경로와 본문에 있으면 같은 값이어야 합니다.") UUID tripId,
            long tripRevision,
            Instant deliveredAt,
            Instant readAt,
            Summary summary,
            String kind,
            @Schema(description = "모바일이 구간에 동의한 뒤 발급된 사용자 후보 UUID.") UUID candidateId,
            Long candidateRevision) {}

    @Schema(name = "TripSummaryInbox")
    public record Inbox(List<InboxItem> items, Long nextCursor) {}

    @Schema(name = "TripSummaryRead")
    public record Read(
            @Schema(description = "항목을 읽은 UTC 시각. 전달 시각 이후여야 합니다.") @NotNull Instant readAt) {}

    @Schema(name = "TripSummaryReadResult")
    public record ReadResult(long inboxItemId, Instant readAt) {}
}
