package app.fuelog.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.List;
import java.util.UUID;

public final class ConnectionSettingsDtos {
    private ConnectionSettingsDtos() {}
    public enum Kind { USER, VEHICLE }
    public enum Feature { AVAILABLE, UNSUPPORTED, PERMISSION_REQUIRED, TEMPORARY_FAILURE, UNKNOWN }
    public record Envelope(@NotNull @Size(max=40) String kind, @NotBlank @Size(max=100) String operationId) {}
    public record Disconnect(@NotNull Kind kind,@NotBlank @Size(max=100) String operationId) {}
    public record Features(@NotNull Feature fuel,@NotNull Feature distance,@NotNull Feature location) {}
    public record Capabilities(@NotNull @PositiveOrZero Long observedAt,@NotNull @Valid Features features) {}
    public record Settings(String deviceAlias,String syncStatus,Long lastSyncedAt,Features features,
                           boolean recommendationRegistered,boolean recommendationConsented,
                           boolean recommendationVerified,boolean recommendationEnabled) {}
    public record OperationResult(String operationId,String status) {}
    public record PendingOperation(UUID id,String operationId,UUID installationId,UUID vehicleId,
                                   long registrationVersion,String status,long createdAt) {}
    public record Operations(List<PendingOperation> items) {}
    public record Approve(@NotNull UUID installationId,@NotNull @PositiveOrZero Long registrationVersion,
                          @NotBlank @Pattern(regexp="RETAIN_ORIGINAL_OWNER") String pendingRecordsPolicy,
                          @NotBlank @Size(max=16384) String reauthenticationProof) {}
    public record Reject(@NotNull UUID installationId,@NotNull @PositiveOrZero Long registrationVersion) {}
    public record Recommendation(@NotNull Boolean consented,Boolean enabled) {}
    public record RecommendationResult(boolean registered,boolean consented,boolean verified,boolean enabled) {}
    public record OwnerSync(UUID owner,String status,Long pendingCount,Long lastAcknowledgedAt) {}
    public record SyncStatus(String status,Long lastAcknowledgedAt,List<OwnerSync> owners) {}
}
