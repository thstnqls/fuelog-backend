package app.fuelog.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import jakarta.validation.constraints.*;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/** 주유 후보 계약. Long 시각은 UTC epoch milliseconds이며 감지값과 사용자 보정값을 구분한다. */
public final class FuelCandidateDtos {
    private FuelCandidateDtos() {}

    public enum Status {
        CANDIDATE,
        CONFIRMED,
        REJECTED
    }

    public enum Action {
        CONFIRM,
        REJECT
    }
    // 클라이언트의 상태·추정 캐시는 검증된 서버 값이나 상태 변경 명령으로 사용하지 않는다.
    @Schema(name = "FuelCandidateSync")
    public record Sync(
            @NotNull UUID id,
            @Schema(description = "이 데이터를 소유한 사용자 UUID.") @NotNull UUID owner,
            @Schema(description = "수동 운행 기록 구간 UUID.") @NotNull UUID intervalId,
            @Schema(description = "연료 증가 관측 시작 시각. UTC epoch milliseconds.")
                    @NotNull
                    @PositiveOrZero
                    Long startAt,
            @Schema(description = "연료 증가 관측 종료 시각. UTC epoch milliseconds.")
                    @NotNull
                    @PositiveOrZero
                    Long endAt,
            @Schema(description = "연료 잔량 증가율(%). 0 초과 100 이하.")
                    @NotNull
                    @DecimalMin(value = "0", inclusive = false)
                    @DecimalMax("100")
                    @Digits(integer = 3, fraction = 3)
                    BigDecimal increasePercent,
            @Schema(description = "클라이언트 캐시. 이 값으로 서버 상태를 변경할 수 없습니다.") Status status,
            @Schema(description = "클라이언트 캐시. 서버가 동기화 버전을 결정합니다.") Long revision,
            @Schema(description = "true이면 소유자의 모바일 확인함에 전달합니다.") @NotNull Boolean queued,
            @Schema(description = "클라이언트 캐시. 연료탱크 용량 검증 여부는 서버가 결정합니다.") Boolean capacityVerified,
            @Schema(description = "클라이언트 캐시. 서버가 검증된 용량으로 추정합니다.") BigDecimal estimatedLiters,
            @Schema(description = "클라이언트 캐시. 서버가 검증된 단가로 추정합니다.") BigDecimal estimatedAmount,
            @Schema(description = "클라이언트 캐시. 서버가 유효한 단가 시각을 결정합니다.") Long priceAt,
            @Schema(description = "클라이언트 표시 캐시. 현재 공급자 미연동으로 서버 후보 목록은 비어 있습니다.")
                    List<String> stationCandidates) {}

    @Schema(name = "FuelCandidateView")
    public record View(
            UUID id,
            @Schema(description = "이 데이터를 소유한 사용자 UUID.") UUID owner,
            Status status,
            @Schema(description = "서버 저장 데이터의 버전. 변경할 때 증가합니다.") long revision,
            boolean capacityVerified,
            BigDecimal estimatedLiters,
            BigDecimal estimatedAmount,
            Long priceAt,
            List<String> stationCandidates,
            @Schema(description = "수동 운행 기록 구간 UUID.") UUID intervalId,
            long startAt,
            long endAt,
            BigDecimal increasePercent,
            String correctedPlace,
            BigDecimal correctedLiters,
            BigDecimal correctedAmount) {}

    @Schema(name = "FuelCandidatePatch")
    public record Patch(
            @Schema(description = "동일 요청 재시도에 유지하는 고유 키. 다른 내용의 요청에는 새 값을 사용합니다.")
                    @NotBlank
                    @Size(max = 100)
                    String operationId,
            @Schema(description = "수정하려는 데이터의 현재 revision. 불일치하면 409입니다.") @NotNull @Positive
                    Long expectedRevision,
            @Schema(description = "CONFIRM: 주유 확인 / REJECT: 주유 후보 기각.") @NotNull Action action,
            @Schema(description = "사용자가 보정한 주유 장소. 생략·null이면 기존 보정값 유지.") @Size(max = 200)
                    String place,
            @Schema(description = "사용자가 보정한 주유량(L). 생략·null이면 기존 보정값 유지.")
                    @DecimalMin(value = "0", inclusive = false)
                    @Digits(integer = 9, fraction = 3)
                    BigDecimal liters,
            @Schema(description = "사용자가 보정한 주유 금액. 생략·null이면 기존 보정값 유지.")
                    @DecimalMin("0")
                    @Digits(integer = 13, fraction = 2)
                    BigDecimal amount) {}

    @Schema(name = "FuelCandidateStationSearch")
    public record StationSearch(
            @Schema(description = "장치에서 위치 사용 권한을 받았는지 여부.") @NotNull Boolean locationPermission,
            @Schema(description = "관측 위도(-90~90).") @DecimalMin("-90") @DecimalMax("90")
                    BigDecimal latitude,
            @Schema(description = "관측 경도(-180~180).") @DecimalMin("-180") @DecimalMax("180")
                    BigDecimal longitude,
            @Schema(description = "위치 관측 시각. UTC epoch milliseconds.") @PositiveOrZero
                    Long observedAt) {}
}
