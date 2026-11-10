package app.fuelog.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** 차량 홈·사용자 세션·수동 기록 계약. 문자열 시각은 UTC이며 거리는 km다. */
public final class VehicleHomeDtos {
    private VehicleHomeDtos() {}

    @Schema(name = "VehicleHomeDataStatus")
    public record DataStatus(
            @Schema(
                            name = "VehicleHomeHome",
                            allowableValues = {
                                "available",
                                "stale",
                                "unsupported",
                                "error",
                                "needs_user_proof"
                            })
                    String state,
            Instant observedAt) {}

    @Schema(description = "사용자 증명이 없으면 개인 필드는 null이고 상태는 needs_user_proof입니다. 시각은 UTC입니다.")
    public record Home(
            String vehicleAlias,
            DataStatus vehicleAliasStatus,
            long userSessionVersion,
            @Schema(allowableValues = {"recording", "finished", "unknown"}) String recordingStatus,
            DataStatus recordingDataStatus,
            Instant lastSampleAt,
            BigDecimal distanceKm,
            DataStatus distanceStatus,
            DataStatus fuelStatus,
            DataStatus costStatus,
            Money estimatedCost,
            DataStatus syncStatus,
            Integer fuelReviewCount,
            DataStatus fuelReviewStatus,
            Integer maintenanceDueCount,
            DataStatus maintenanceStatus,
            Instant generatedAt,
            @Schema(description = "현재 연결 사용자가 모바일 연결 요청의 new_vehicle_name에 입력한 별칭. 입력값이 없으면 차량명, 사용자 미연결·해제 시 null.", example = "서빈의 출퇴근 차", nullable = true)
            String connectionAlias) {}

    @Schema(name = "VehicleHomeMoney")
    public record Money(
            BigDecimal amount, @Schema(description = "ISO 4217 통화 코드. 예: KRW.") String currency) {}

    @Schema(name = "VehicleHomeChallengeRequest")
    public record ChallengeRequest(
            @Schema(
                            description = "개인 홈 확인 대상 설치 UUID. 구간 사용자 확인에서는 생략할 수 있습니다.",
                            example = "44444444-4444-4444-8444-444444444444")
                    UUID installationId,
            @Size(max = 100)
                    @Schema(
                            description = "개인 홈 확인에서는 필수. 구간 확인에서는 선택이며 재시도 시 같은 값을 유지합니다.",
                            example = "trip-user-20261104-001")
                    String operationId,
            @Schema(
                            description = "현재 운행 사용자 확인 대상 구간 UUID. 개인 홈 확인에서는 생략합니다.",
                            example = "55555555-5555-4555-8555-555555555555")
                    UUID intervalId) {}

    @Schema(name = "VehicleHomeChallenge")
    public record Challenge(
            @Schema(description = "사용자 확인 QR 요청 UUID.") UUID challengeId,
            String status,
            @Schema(description = "권한 또는 요청 만료 시각. UTC ISO-8601 문자열.") Instant expiresAt,
            @Schema(description = "QR에 넣는 일회용 비밀값. pending 상태에서만 반환합니다.") String proof,
            String type,
            Integer version) {}

    @Schema(name = "VehicleHomeMobileApprovalRequest")
    public record MobileApprovalRequest(
            @NotNull
                    @Schema(
                            description = "사용자 확인 QR 요청 UUID.",
                            example = "11111111-1111-4111-8111-111111111111")
                    UUID challengeId,
            @NotBlank
                    @Schema(
                            description = "사용자 확인 QR의 일회용 비밀값. URL이나 로그에 넣지 않습니다.",
                            example = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA")
                    String proof,
            @NotNull
                    @Schema(
                            description = "차량 UUID.",
                            example = "22222222-2222-4222-8222-222222222222")
                    UUID vehicleId) {}

    @Schema(name = "VehicleHomeMobileApproval")
    public record MobileApproval(
            @Schema(description = "사용자 확인 QR 요청 UUID.") UUID challengeId,
            String status,
            @Schema(description = "사용자 세션 폐기를 판별하는 버전.") long sessionVersion) {}

    @Schema(name = "VehicleHomeSessionRequest")
    public record SessionRequest(
            @NotNull
                    @Schema(
                            description = "사용자 확인 QR 요청 UUID.",
                            example = "11111111-1111-4111-8111-111111111111")
                    UUID challengeId) {}

    @Schema(name = "VehicleHomeSession")
    public record Session(
            String status,
            @Schema(description = "사용자 확인 QR 요청 UUID.") UUID challengeId,
            String displayName,
            @Schema(description = "확인된 운행 사용자의 UUID.") UUID subjectId,
            @Schema(description = "사용자 세션 폐기를 판별하는 버전.") long sessionVersion,
            @Schema(description = "권한 또는 요청 만료 시각. UTC ISO-8601 문자열.") Instant expiresAt,
            @Schema(
                            description =
                                    "설치·차량·사용자에 묶인 짧은 세션 토큰. 개인 홈 조회 때 X-Trip-User-Session Bearer로"
                                            + " 사용합니다.")
                    String scopedToken) {}

    @Schema(name = "VehicleHomeSessionDeleted")
    public record SessionDeleted(
            @Schema(description = "사용자 세션 폐기를 판별하는 버전.") long sessionVersion, String status) {}

    @Schema(name = "VehicleHomeIntervalStartRequest")
    public record IntervalStartRequest(
            @NotBlank
                    @Size(max = 100)
                    @Schema(
                            description = "설치 내에서 구간 시작 재시도에 유지하는 고유 키.",
                            example = "local-interval-001")
                    String localIntervalId,
            @NotNull
                    @Schema(
                            description = "운행 시작 시각. UTC ISO-8601 문자열.",
                            example = "2026-11-04T10:00:00Z")
                    Instant startedAt,
            @NotBlank
                    @Schema(
                            allowableValues = {"manual_app"},
                            example = "manual_app")
                    String source,
            @Schema(description = "기록 시작 때 확인한 마지막 QR 인증 사용자. 서버의 현재 사용자와 일치해야 함") UUID subjectId) {
        public IntervalStartRequest(String localIntervalId, Instant startedAt, String source) {
            this(localIntervalId, startedAt, source, null);
        }
    }

    @Schema(name = "VehicleHomeInterval")
    public record Interval(
            UUID id,
            @Schema(description = "차량 UUID.") UUID vehicleId,
            @Schema(description = "설치 내에서 구간 시작 재시도에 유지하는 고유 키.") String localIntervalId,
            String status,
            @Schema(description = "운행 시작 시각. UTC ISO-8601 문자열.") Instant startedAt,
            @Schema(description = "운행 종료 시각. UTC ISO-8601 문자열.") Instant endedAt,
            @Schema(description = "확인된 운행 사용자의 UUID.") UUID subjectId,
            @Schema(description = "서버가 수락한 가장 큰 샘플 번호. 중간 번호의 누락이 없다는 의미는 아닙니다.")
                    long lastAcceptedSequence,
            Instant lastSampleAt) {}

    @Schema(name = "VehicleHomeSample")
    public record Sample(
            @NotNull @Schema(example = "1") Long sequence,
            @NotNull @Schema(example = "2026-11-04T10:00:03Z") Instant observedAt,
            @Schema(description = "이 샘플에서 증가한 거리(km). 누락이면 null입니다.", example = "0.125")
                    BigDecimal distanceKm,
            @Size(max = 40)
                    @Schema(
                            description = "거리 누락 이유. distanceKm가 null일 때 필요",
                            example = "odometer_reset")
                    String missingReason,
            @Schema(
                            description =
                                    "선택적인 연료 사용량. 잘못된 값은 거리 샘플을 취소하지 않고 fuelStatus=error로 기록합니다.",
                            example = "0.02")
                    BigDecimal fuelLiters) {}

    @Schema(name = "VehicleHomeSamplesRequest")
    public record SamplesRequest(
            @NotBlank
                    @Size(max = 100)
                    @Schema(description = "같은 샘플 묶음의 재전송에 유지하는 고유 키.", example = "batch-001")
                    String batchId,
            @Schema(description = "시간·거리·연료 샘플 목록. 배치당 1~500개이며 sequence가 증가해야 합니다.")
                    @NotEmpty
                    @Size(max = 500)
                    List<@NotNull @Valid Sample> samples) {}

    @Schema(name = "VehicleHomeSamplesAccepted")
    public record SamplesAccepted(
            @Schema(description = "수동 운행 기록 구간 UUID.") UUID intervalId,
            @Schema(description = "같은 샘플 묶음의 재전송에 유지하는 고유 키.") String batchId,
            int acceptedCount,
            @Schema(description = "서버가 수락한 가장 큰 샘플 번호. 중간 번호의 누락이 없다는 의미는 아닙니다.")
                    long lastAcceptedSequence,
            @Schema(description = "마지막 서버 저장 시각.") Instant lastSyncedAt) {}

    @Schema(name = "VehicleHomeFinishRequest")
    public record FinishRequest(
            @NotBlank
                    @Size(max = 100)
                    @Schema(
                            description = "동일 요청 재시도에 유지하는 고유 키. 다른 내용의 요청에는 새 값을 사용합니다.",
                            example = "finish-001")
                    String operationId,
            @NotNull
                    @Schema(
                            description = "운행 종료 시각. UTC ISO-8601 문자열.",
                            example = "2026-11-04T10:01:00Z")
                    Instant endedAt,
            @NotNull
                    @Schema(description = "기기에 기록된 마지막 샘플 번호. 서버 수락 번호보다 작을 수 없습니다.", example = "5")
                    Long lastSequence) {}

    @Schema(
            name = "VehicleHomeSyncStatus",
            description = "서버가 관찰한 동기화 상태. 클라이언트의 업로드 대기 큐 건수는 서버가 알 수 없어 null입니다.")
    public record SyncStatus(
            @Schema(description = "수동 운행 기록 구간 UUID.") UUID intervalId,
            @Schema(description = "서버가 수락한 가장 큰 샘플 번호. 중간 번호의 누락이 없다는 의미는 아닙니다.")
                    long lastAcceptedSequence,
            @Schema(description = "마지막 서버 저장 시각.") Instant lastSyncedAt,
            Integer pendingCount,
            Instant retryAfter,
            DataStatus state) {}
}
