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

public final class VehicleHomeDtos {
    private VehicleHomeDtos() {}

    public record DataStatus(
            @Schema(allowableValues = {"available", "stale", "unsupported", "error", "needs_user_proof"}) String state,
            Instant observedAt) {}

    @Schema(description = "사용자 증명이 없으면 개인 필드는 null이고 상태는 needs_user_proof입니다. 시각은 UTC입니다.")
    public record Home(
            String vehicleAlias, DataStatus vehicleAliasStatus,
            long userSessionVersion,
            @Schema(allowableValues = {"recording", "finished", "unknown"}) String recordingStatus,
            DataStatus recordingDataStatus,
            Instant lastSampleAt,
            BigDecimal distanceKm, DataStatus distanceStatus,
            DataStatus fuelStatus,
            DataStatus costStatus, Money estimatedCost,
            DataStatus syncStatus,
            Integer fuelReviewCount, DataStatus fuelReviewStatus,
            Integer maintenanceDueCount, DataStatus maintenanceStatus,
            Instant generatedAt) {}

    public record Money(BigDecimal amount, String currency) {}

    public record ChallengeRequest(
            @Schema(description = "C02 설치 전체 확인 때 사용. C03은 생략 가능", example = "44444444-4444-4444-8444-444444444444") UUID installationId,
            @Size(max = 100) @Schema(description = "C02 재시도 키. C03에서는 선택 사항", example = "trip-user-20261104-001") String operationId,
            @Schema(description = "C03 구간 전용 확인 대상. C02는 생략", example = "55555555-5555-4555-8555-555555555555") UUID intervalId) {}

    public record Challenge(
            UUID challengeId, String status, Instant expiresAt,
            @Schema(description = "QR에 넣는 일회용 비밀값. pending 상태에서만 반환합니다.") String proof,
            String type, Integer version) {}

    public record MobileApprovalRequest(
            @NotNull @Schema(example = "11111111-1111-4111-8111-111111111111") UUID challengeId,
            @NotBlank @Schema(example = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA") String proof,
            @NotNull @Schema(example = "22222222-2222-4222-8222-222222222222") UUID vehicleId) {}

    public record MobileApproval(UUID challengeId, String status, long sessionVersion) {}

    public record SessionRequest(@NotNull @Schema(example = "11111111-1111-4111-8111-111111111111") UUID challengeId) {}

    public record Session(
            String status, UUID challengeId, String displayName, UUID subjectId,
            long sessionVersion, Instant expiresAt,
            @Schema(description = "설치·차량·사용자에 묶인 짧은 세션 토큰. 개인 홈 조회 때 X-Trip-User-Session Bearer로 사용합니다.") String scopedToken) {}

    public record SessionDeleted(long sessionVersion, String status) {}

    public record IntervalStartRequest(
            @NotBlank @Size(max = 100) @Schema(example = "local-interval-001") String localIntervalId,
            @NotNull @Schema(example = "2026-11-04T10:00:00Z") Instant startedAt,
            @NotBlank @Schema(allowableValues = {"manual_app"}, example = "manual_app") String source) {}

    public record Interval(UUID id, UUID vehicleId, String localIntervalId, String status,
                           Instant startedAt, Instant endedAt, UUID subjectId,
                           long lastAcceptedSequence, Instant lastSampleAt) {}

    public record Sample(
            @NotNull @Schema(example = "1") Long sequence,
            @NotNull @Schema(example = "2026-11-04T10:00:03Z") Instant observedAt,
            @Schema(description = "이 샘플에서 증가한 거리(km). 누락이면 null입니다.", example = "0.125") BigDecimal distanceKm,
            @Size(max = 40) @Schema(description = "거리 누락 이유. distanceKm가 null일 때 필요", example = "odometer_reset") String missingReason,
            @Schema(description = "선택적인 연료 사용량. 잘못된 값은 거리 샘플을 취소하지 않고 fuelStatus=error로 기록합니다.", example = "0.02") BigDecimal fuelLiters) {}

    public record SamplesRequest(
            @NotBlank @Size(max = 100) @Schema(example = "batch-001") String batchId,
            @NotEmpty @Size(max = 500) List<@NotNull @Valid Sample> samples) {}

    public record SamplesAccepted(UUID intervalId, String batchId, int acceptedCount,
                                  long lastAcceptedSequence, Instant lastSyncedAt) {}

    public record FinishRequest(
            @NotBlank @Size(max = 100) @Schema(example = "finish-001") String operationId,
            @NotNull @Schema(example = "2026-11-04T10:01:00Z") Instant endedAt,
            @NotNull @Schema(example = "5") Long lastSequence) {}

    @Schema(description = "서버가 관찰한 동기화 상태. 클라이언트의 업로드 대기 큐 건수는 서버가 알 수 없어 null입니다.")
    public record SyncStatus(UUID intervalId, long lastAcceptedSequence, Instant lastSyncedAt,
                             Integer pendingCount, Instant retryAfter, DataStatus state) {}
}
