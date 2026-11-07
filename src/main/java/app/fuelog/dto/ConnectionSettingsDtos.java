package app.fuelog.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;

import java.util.List;
import java.util.UUID;

/** 장치 설정·초기화 승인 계약. Long 시각 필드는 UTC epoch milliseconds다. */
public final class ConnectionSettingsDtos {
    private ConnectionSettingsDtos() {}

    public enum Kind {
        USER,
        VEHICLE
    }

    @Schema(
            description =
                    "AVAILABLE: 사용 가능 / UNSUPPORTED: 미지원 / PERMISSION_REQUIRED: 권한 필요 /"
                            + " TEMPORARY_FAILURE: 일시 오류 / UNKNOWN: 미확인.")
    public enum Feature {
        AVAILABLE,
        UNSUPPORTED,
        PERMISSION_REQUIRED,
        TEMPORARY_FAILURE,
        UNKNOWN
    }

    @Schema(name = "ConnectionSettingsEnvelope")
    public record Envelope(
            @Schema(description = "조회용 기존 필드. null은 허용하지 않지만 값은 설정 변경에 사용하지 않습니다.")
                    @NotNull
                    @Size(max = 40)
                    String kind,
            @Schema(description = "동일 요청 재시도에 유지하는 고유 키. 다른 내용의 요청에는 새 값을 사용합니다.")
                    @NotBlank
                    @Size(max = 100)
                    String operationId) {}

    @Schema(name = "ConnectionSettingsDisconnect")
    public record Disconnect(
            @Schema(description = "USER는 사용자 해제, VEHICLE은 관리자 승인이 필요한 차량 초기화.") @NotNull Kind kind,
            @Schema(description = "동일 요청 재시도에 유지하는 고유 키. 다른 내용의 요청에는 새 값을 사용합니다.")
                    @NotBlank
                    @Size(max = 100)
                    String operationId) {}

    @Schema(name = "ConnectionSettingsFeatures")
    public record Features(
            @Schema(description = "연료 기능 상태.") @NotNull Feature fuel,
            @Schema(description = "거리 기능 상태.") @NotNull Feature distance,
            @Schema(description = "위치 기능 상태.") @NotNull Feature location) {}

    @Schema(name = "ConnectionSettingsCapabilities")
    public record Capabilities(
            @Schema(description = "SDK 상태를 확인한 시각. UTC epoch milliseconds.")
                    @NotNull
                    @PositiveOrZero
                    Long observedAt,
            @NotNull @Valid Features features) {}

    @Schema(name = "ConnectionSettingsSettings")
    public record Settings(
            @Schema(description = "장치 표시 이름. 설정하지 않았으면 null.") String deviceAlias,
            @Schema(description = "PENDING: 대기 항목 있음 / SYNCED: 서버 완료 확인 / UNKNOWN: 미확인.")
                    String syncStatus,
            @Schema(description = "서버가 마지막으로 확인한 시각. UTC epoch milliseconds, 미확인 시 null.")
                    Long lastSyncedAt,
            Features features,
            @Schema(description = "현재 차량 연결에 추천 기능이 서버 등록되었는지 여부.")
                    boolean recommendationRegistered,
            @Schema(description = "현재 사용자가 추천 기능에 동의했는지 여부.") boolean recommendationConsented,
            @Schema(description = "서버가 추천 기능 사용 조건을 검증했는지 여부.") boolean recommendationVerified,
            @Schema(description = "등록·검증·동의·현재 사용자 조건을 모두 만족해 활성화되었는지 여부.")
                    boolean recommendationEnabled) {}

    @Schema(name = "ConnectionSettingsOperationResult")
    public record OperationResult(
            @Schema(description = "동일 요청 재시도에 유지하는 고유 키. 다른 내용의 요청에는 새 값을 사용합니다.")
                    String operationId,
            @Schema(description = "COMPLETED: 완료 / AWAITING_ADMIN: 관리자 승인 대기 / REJECTED: 기각.")
                    String status) {}

    @Schema(name = "ConnectionSettingsPendingOperation")
    public record PendingOperation(
            UUID id,
            @Schema(description = "동일 요청 재시도에 유지하는 고유 키. 다른 내용의 요청에는 새 값을 사용합니다.")
                    String operationId,
            @Schema(description = "장치 앱 설치 UUID.") UUID installationId,
            @Schema(description = "차량 UUID.") UUID vehicleId,
            @Schema(description = "초기화 대기 목록에서 받은 설치·차량 연결 버전. 재연결된 차량에 이전 승인을 적용하지 않도록 검사합니다.")
                    long registrationVersion,
            String status,
            @Schema(description = "초기화 요청 생성 시각. UTC epoch milliseconds.") long createdAt) {}

    @Schema(name = "ConnectionSettingsOperations")
    public record Operations(List<PendingOperation> items) {}

    @Schema(name = "ConnectionSettingsApprove")
    public record Approve(
            @Schema(description = "장치 앱 설치 UUID.") @NotNull UUID installationId,
            @Schema(description = "초기화 대기 목록에서 받은 설치·차량 연결 버전. 재연결된 차량에 이전 승인을 적용하지 않도록 검사합니다.")
                    @NotNull
                    @PositiveOrZero
                    Long registrationVersion,
            @Schema(description = "RETAIN_ORIGINAL_OWNER 고정. 기존 기록의 소유권을 보존합니다.")
                    @NotBlank
                    @Pattern(regexp = "RETAIN_ORIGINAL_OWNER")
                    String pendingRecordsPolicy,
            @Schema(
                            description =
                                    "같은 관리자의 최근 5분 내 OTP/TOTP 인증 Supabase JWT 원문. Bearer 접두어는 붙이지"
                                            + " 않습니다.")
                    @NotBlank
                    @Size(max = 16384)
                    String reauthenticationProof) {}

    @Schema(name = "ConnectionSettingsReject")
    public record Reject(
            @Schema(description = "장치 앱 설치 UUID.") @NotNull UUID installationId,
            @Schema(description = "초기화 대기 목록에서 받은 설치·차량 연결 버전. 재연결된 차량에 이전 승인을 적용하지 않도록 검사합니다.")
                    @NotNull
                    @PositiveOrZero
                    Long registrationVersion) {}

    @Schema(name = "ConnectionSettingsRecommendation")
    public record Recommendation(
            @Schema(description = "true는 추천 동의, false는 동의 철회.") @NotNull Boolean consented,
            @Schema(description = "추천 활성화 여부. 생략하면 false로 처리하며 true에는 동의가 필요합니다.")
                    Boolean enabled) {}

    @Schema(name = "ConnectionSettingsRecommendationResult")
    public record RecommendationResult(
            boolean registered, boolean consented, boolean verified, boolean enabled) {}

    @Schema(name = "ConnectionSettingsOwnerSync")
    public record OwnerSync(
            @Schema(description = "이 데이터를 소유한 사용자 UUID.") UUID owner,
            String status,
            @Schema(description = "서버가 확인한 대기 건수. 모르면 null이며 기기 로컬 큐 건수와 다릅니다.") Long pendingCount,
            @Schema(description = "서버 큐 확인 시각. UTC epoch milliseconds.") Long lastAcknowledgedAt) {}

    @Schema(name = "ConnectionSettingsSyncStatus")
    public record SyncStatus(
            String status,
            @Schema(description = "서버 큐 확인 시각. UTC epoch milliseconds.") Long lastAcknowledgedAt,
            List<OwnerSync> owners) {}
}
