package app.fuelog.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** 현재 운행 구간의 모바일 동의와 차량 화면 사용자 확인 계약. */
public final class TripUserCandidateDtos {
    private TripUserCandidateDtos() {}

    @Schema(name = "TripUserCandidateCandidatesRequest")
    public record CandidatesRequest(
            @NotNull
                    @Schema(
                            description = "수동 운행 기록 구간 UUID.",
                            example = "55555555-5555-4555-8555-555555555555")
                    UUID intervalId) {}

    @Schema(name = "TripUserCandidateCandidate")
    public record Candidate(
            @Schema(
                            description = "모바일이 구간에 동의한 뒤 발급된 사용자 후보 UUID.",
                            example = "66666666-6666-4666-8666-666666666666")
                    UUID candidateId,
            @Schema(example = "운전자") String displayName,
            boolean vehicleMember,
            boolean mobileConsented,
            @Schema(
                            description = "서버 만료 시각 기준 남은 유효 시간(초).",
                            minimum = "1",
                            maximum = "300",
                            example = "240")
                    int remainingSeconds) {}

    @Schema(name = "TripUserCandidateCandidates")
    public record Candidates(
            @Schema(description = "수동 운행 기록 구간 UUID.") UUID intervalId,
            List<Candidate> candidates) {}

    @Schema(name = "TripUserCandidateVerifyRequest")
    public record VerifyRequest(
            @Schema(description = "수동 운행 기록 구간 UUID.") @NotNull UUID intervalId,
            @Schema(description = "모바일이 구간에 동의한 뒤 발급된 사용자 후보 UUID.") @NotNull UUID candidateId,
            @NotBlank
                    @Size(max = 100)
                    @Schema(
                            description = "동일 요청 재시도에 유지하는 고유 키. 다른 내용의 요청에는 새 값을 사용합니다.",
                            example = "verify-001")
                    String operationId) {}

    @Schema(name = "TripUserCandidateVerified")
    public record Verified(
            @Schema(description = "수동 운행 기록 구간 UUID.") UUID intervalId,
            @Schema(description = "모바일이 구간에 동의한 뒤 발급된 사용자 후보 UUID.") UUID candidateId,
            @Schema(description = "확인된 운행 사용자의 UUID.") UUID subjectId,
            boolean vehicleMember,
            boolean mobileConsented,
            @Schema(description = "확인 권한 만료 시각. UTC epoch milliseconds.")
                    long expiresAtEpochMillis) {}

    @Schema(name = "TripUserCandidateMobileConsentRequest")
    public record MobileConsentRequest(
            @Schema(description = "차량 UUID.") @NotNull UUID vehicleId,
            @Schema(description = "장치 앱 설치 UUID.") @NotNull UUID installationId,
            @Schema(description = "수동 운행 기록 구간 UUID.") @NotNull UUID intervalId,
            @Schema(description = "사용자 확인 QR 요청 UUID.") @NotNull UUID challengeId,
            @Schema(description = "사용자 확인 QR의 일회용 비밀값. URL이나 로그에 넣지 않습니다.") @NotBlank String proof,
            @Schema(description = "동일 요청 재시도에 유지하는 고유 키. 다른 내용의 요청에는 새 값을 사용합니다.")
                    @NotBlank
                    @Size(max = 100)
                    String operationId) {}

    @Schema(name = "TripUserCandidateMobileConsent")
    public record MobileConsent(
            @Schema(description = "모바일 구간 동의 UUID. 현재 구현에서는 candidateId와 같습니다.") UUID consentId,
            @Schema(description = "모바일이 구간에 동의한 뒤 발급된 사용자 후보 UUID.") UUID candidateId,
            @Schema(description = "수동 운행 기록 구간 UUID.") UUID intervalId,
            String status,
            @Schema(description = "권한 또는 요청 만료 시각. UTC ISO-8601 문자열.") Instant expiresAt) {}

    @Schema(name = "TripUserCandidateRevokeRequest")
    public record RevokeRequest(
            @Schema(description = "모바일 구간 동의 UUID. 현재 구현에서는 candidateId와 같습니다.") @NotNull
                    UUID consentId) {}

    @Schema(name = "TripUserCandidateRevoked")
    public record Revoked(
            @Schema(description = "모바일 구간 동의 UUID. 현재 구현에서는 candidateId와 같습니다.") UUID consentId,
            String status) {}

    @Schema(name = "TripUserCandidateEvent")
    public record Event(
            @Schema(description = "서버 저장 데이터의 버전. 변경할 때 증가합니다.") long revision, String type) {}

    @Schema(name = "TripUserCandidateEvents")
    public record Events(
            @Schema(description = "서버 저장 데이터의 버전. 변경할 때 증가합니다.") long revision,
            long nextCursor,
            List<Event> events) {}
}
