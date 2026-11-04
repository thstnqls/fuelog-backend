package app.fuelog.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class TripUserCandidateDtos {
    private TripUserCandidateDtos() {}

    public record CandidatesRequest(
            @NotNull @Schema(example = "55555555-5555-4555-8555-555555555555") UUID intervalId) {}

    public record Candidate(
            @Schema(example = "66666666-6666-4666-8666-666666666666") UUID candidateId,
            @Schema(example = "운전자") String displayName,
            boolean vehicleMember,
            boolean mobileConsented,
            @Schema(minimum = "1", maximum = "300", example = "240") int remainingSeconds) {}

    public record Candidates(UUID intervalId, List<Candidate> candidates) {}

    public record VerifyRequest(
            @NotNull UUID intervalId,
            @NotNull UUID candidateId,
            @NotBlank @Size(max = 100) @Schema(example = "verify-001") String operationId) {}

    public record Verified(UUID intervalId, UUID candidateId, UUID subjectId,
                           boolean vehicleMember, boolean mobileConsented,
                           long expiresAtEpochMillis) {}

    public record MobileConsentRequest(
            @NotNull UUID vehicleId,
            @NotNull UUID installationId,
            @NotNull UUID intervalId,
            @NotNull UUID challengeId,
            @NotBlank String proof,
            @NotBlank @Size(max = 100) String operationId) {}

    public record MobileConsent(UUID consentId, UUID candidateId, UUID intervalId,
                                String status, Instant expiresAt) {}

    public record RevokeRequest(@NotNull UUID consentId) {}

    public record Revoked(UUID consentId, String status) {}

    public record Event(long revision, String type) {}

    public record Events(long revision, long nextCursor, List<Event> events) {}
}
