package app.fuelog.service;

import app.fuelog.dto.TripUserCandidateDtos;
import app.fuelog.repository.PairingRepository;
import app.fuelog.repository.TripUserCandidateRepository;
import app.fuelog.repository.TripUserCandidateRepository.Candidate;
import app.fuelog.repository.VehicleHomeRepository;
import app.fuelog.repository.VehicleHomeRepository.Challenge;
import app.fuelog.repository.VehicleHomeRepository.Interval;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Base64;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class TripUserCandidateService {
    private static final Duration CONSENT_LIFETIME = Duration.ofMinutes(5);
    private final InstallationAuth auth;
    private final PairingActor actor;
    private final PairingRepository pairing;
    private final VehicleHomeRepository home;
    private final TripUserCandidateRepository repository;

    public TripUserCandidateService(InstallationAuth auth, PairingActor actor, PairingRepository pairing,
                                    VehicleHomeRepository home, TripUserCandidateRepository repository) {
        this.auth = auth;
        this.actor = actor;
        this.pairing = pairing;
        this.home = home;
        this.repository = repository;
    }

    @Transactional
    public TripUserCandidateDtos.Candidates candidates(String bearer, TripUserCandidateDtos.CandidatesRequest body) {
        UUID installation = auth.require(bearer).id();
        pairing.lockInstallation(installation);
        Interval interval = currentInterval(installation, body.intervalId());
        Instant now = Instant.now();
        expireAndNotify(installation, now);
        var rows = repository.activeCandidates(installation, interval.vehicleId(), interval.id(), now);
        var list = new ArrayList<TripUserCandidateDtos.Candidate>(rows.size());
        for (Candidate candidate : rows) {
            long millis = Duration.between(now, candidate.expiresAt()).toMillis();
            int remaining = (int) Math.max(1, Math.min(300, (millis + 999) / 1000));
            list.add(new TripUserCandidateDtos.Candidate(candidate.id(), candidate.displayName(), true, true, remaining));
        }
        return new TripUserCandidateDtos.Candidates(interval.id(), list);
    }

    @Transactional
    public TripUserCandidateDtos.Verified verify(String bearer, TripUserCandidateDtos.VerifyRequest body) {
        UUID installation = auth.require(bearer).id();
        pairing.lockInstallation(installation);
        Candidate priorKey = repository.byVerifyOperation(installation, body.operationId());
        if (priorKey != null && (!priorKey.id().equals(body.candidateId())
                || !priorKey.intervalId().equals(body.intervalId())))
            fail(HttpStatus.CONFLICT, "OPERATION_ID_REUSED");
        Interval interval = home.interval(body.intervalId(), false);
        if (!installation.equals(interval.installationId())
                || !interval.vehicleId().equals(pairing.linkedVehicle(installation)))
            fail(HttpStatus.FORBIDDEN, "INTERVAL_SCOPE_MISMATCH");
        Candidate candidate = repository.get(body.candidateId(), true);
        requireScope(candidate, installation, interval);
        if (interval.userId() != null && !interval.userId().equals(candidate.userId()))
            fail(HttpStatus.FORBIDDEN, "INTERVAL_OWNER_MISMATCH");
        if (!repository.memberForUpdate(candidate.userId(), interval.vehicleId()))
            fail(HttpStatus.FORBIDDEN, "VEHICLE_MEMBERSHIP_REQUIRED");
        Instant now = Instant.now();
        if ("revoked".equals(candidate.status()) || "expired".equals(candidate.status()))
            fail(HttpStatus.GONE, "CANDIDATE_UNAVAILABLE");
        if (!now.isBefore(candidate.expiresAt())) fail(HttpStatus.GONE, "CANDIDATE_EXPIRED");
        if ("verified".equals(candidate.status())) {
            if (!body.operationId().equals(candidate.verifiedOperationId()))
                fail(HttpStatus.CONFLICT, "CANDIDATE_ALREADY_USED");
            if (!now.isBefore(candidate.verificationExpiresAt())) fail(HttpStatus.GONE, "VERIFICATION_EXPIRED");
            return verified(candidate);
        }
        if (!"recording".equals(interval.status())) fail(HttpStatus.CONFLICT, "INTERVAL_FINISHED");
        if (!"active".equals(candidate.status())) fail(HttpStatus.CONFLICT, "CANDIDATE_ALREADY_USED");
        Instant expiry = candidate.expiresAt();
        repository.markVerified(candidate.id(), body.operationId(), expiry, now);
        repository.addEvent(installation, "candidates_changed", now);
        return verified(repository.get(candidate.id(), false));
    }

    @Transactional
    public TripUserCandidateDtos.MobileConsent consent(Jwt jwt, TripUserCandidateDtos.MobileConsentRequest body) {
        UUID user = actor.requireUser(jwt);
        Challenge first = home.challenge(body.challengeId(), false);
        pairing.lockInstallation(first.installationId());
        Challenge challenge = home.challenge(body.challengeId(), true);
        if (challenge.intervalId() == null || !challenge.installationId().equals(body.installationId())
                || !challenge.vehicleId().equals(body.vehicleId())
                || !challenge.intervalId().equals(body.intervalId()))
            fail(HttpStatus.FORBIDDEN, "CHALLENGE_SCOPE_MISMATCH");
        if (!repository.memberForUpdate(user, body.vehicleId()))
            fail(HttpStatus.FORBIDDEN, "VEHICLE_MEMBERSHIP_REQUIRED");
        byte[] proof = decodeProof(body.proof());
        byte[] fingerprint = InstallationAuth.sha256(proof);
        Candidate existing = repository.byChallenge(challenge.id());
        if (existing != null) {
            if (!existing.userId().equals(user) || !existing.consentOperationId().equals(body.operationId())
                    || !MessageDigest.isEqual(existing.proofFingerprint(), fingerprint))
                fail(HttpStatus.CONFLICT, "CONSENT_ALREADY_USED");
            if (!Instant.now().isBefore(existing.expiresAt()))
                fail(HttpStatus.GONE, "CONSENT_EXPIRED");
            return consentView(existing);
        }
        Interval interval = currentInterval(challenge.installationId(), body.intervalId());
        if (interval.userId() != null && !interval.userId().equals(user))
            fail(HttpStatus.FORBIDDEN, "INTERVAL_OWNER_MISMATCH");
        Candidate usedOperation = repository.byConsentOperation(challenge.installationId(), body.operationId());
        if (usedOperation != null) fail(HttpStatus.CONFLICT, "OPERATION_ID_REUSED");
        Instant now = Instant.now();
        if (!"pending".equals(challenge.status())) fail(HttpStatus.CONFLICT, "CHALLENGE_ALREADY_USED");
        if (!now.isBefore(challenge.expiresAt())) fail(HttpStatus.GONE, "CHALLENGE_EXPIRED");
        if (challenge.proofHash() == null || !MessageDigest.isEqual(challenge.proofHash(), fingerprint))
            fail(HttpStatus.FORBIDDEN, "INVALID_CHALLENGE_PROOF");
        UUID id = UUID.randomUUID();
        Candidate candidate = new Candidate(id, challenge.id(), challenge.installationId(), challenge.vehicleId(),
                interval.id(), user, VehicleHomeService.displayName(jwt), body.operationId(), fingerprint,
                null, "active", now.plus(CONSENT_LIFETIME).truncatedTo(ChronoUnit.MICROS), null);
        repository.insert(candidate, now);
        repository.markChallengeConsented(challenge.id(), user, candidate.displayName(), now);
        repository.addEvent(challenge.installationId(), "candidates_changed", now);
        return consentView(candidate);
    }

    @Transactional
    public TripUserCandidateDtos.Revoked revokeByInstallation(String bearer, UUID consentId) {
        UUID installation = auth.require(bearer).id();
        pairing.lockInstallation(installation);
        Candidate candidate = repository.get(consentId, true);
        if (!installation.equals(candidate.installationId())) fail(HttpStatus.FORBIDDEN, "INSTALLATION_MISMATCH");
        return revoke(candidate);
    }

    @Transactional
    public TripUserCandidateDtos.Revoked revokeByUser(UUID user, UUID consentId) {
        Candidate first = repository.get(consentId, false);
        pairing.lockInstallation(first.installationId());
        Candidate candidate = repository.get(consentId, true);
        if (!user.equals(candidate.userId())) fail(HttpStatus.FORBIDDEN, "CONSENT_OWNER_MISMATCH");
        return revoke(candidate);
    }

    @Transactional
    public TripUserCandidateDtos.Events events(String bearer, Long cursor) {
        UUID installation = auth.require(bearer).id();
        pairing.lockInstallation(installation);
        if (cursor != null && cursor < 0) fail(HttpStatus.BAD_REQUEST, "INVALID_CURSOR");
        expireAndNotify(installation, Instant.now());
        long revision = repository.revision(installation);
        long after = Math.min(cursor == null ? 0 : cursor, revision);
        var events = repository.events(installation, after, 100);
        long next = events.isEmpty() ? after : events.getLast().revision();
        return new TripUserCandidateDtos.Events(revision, next, events);
    }

    @Transactional
    public void revokeForInstallationReset(UUID installation) {
        pairing.lockInstallation(installation);
        Instant now = Instant.now();
        if (repository.revokeForInstallation(installation, now) > 0)
            repository.addEvent(installation, "candidates_changed", now);
    }

    private TripUserCandidateDtos.Revoked revoke(Candidate candidate) {
        if (!"revoked".equals(candidate.status())) {
            Instant now = Instant.now();
            repository.revoke(candidate.id(), now);
            repository.addEvent(candidate.installationId(), "candidates_changed", now);
        }
        return new TripUserCandidateDtos.Revoked(candidate.id(), "REVOKED");
    }

    private void expireAndNotify(UUID installation, Instant now) {
        if (repository.expireActive(installation, now) > 0)
            repository.addEvent(installation, "candidates_changed", now);
    }

    private Interval currentInterval(UUID installation, UUID intervalId) {
        Interval interval = home.interval(intervalId, false);
        if (!installation.equals(interval.installationId())
                || !interval.vehicleId().equals(pairing.linkedVehicle(installation)))
            fail(HttpStatus.FORBIDDEN, "INTERVAL_SCOPE_MISMATCH");
        if (!"recording".equals(interval.status())) fail(HttpStatus.CONFLICT, "INTERVAL_FINISHED");
        return interval;
    }

    private static void requireScope(Candidate candidate, UUID installation, Interval interval) {
        if (!installation.equals(candidate.installationId())
                || !interval.id().equals(candidate.intervalId())
                || !interval.vehicleId().equals(candidate.vehicleId()))
            fail(HttpStatus.FORBIDDEN, "CANDIDATE_SCOPE_MISMATCH");
    }

    private static TripUserCandidateDtos.Verified verified(Candidate candidate) {
        return new TripUserCandidateDtos.Verified(candidate.intervalId(), candidate.id(), candidate.userId(),
                true, true, candidate.verificationExpiresAt().toEpochMilli());
    }

    private static TripUserCandidateDtos.MobileConsent consentView(Candidate candidate) {
        return new TripUserCandidateDtos.MobileConsent(candidate.id(), candidate.id(), candidate.intervalId(),
                candidate.status().toUpperCase(), candidate.expiresAt());
    }

    private static byte[] decodeProof(String encoded) {
        if (encoded == null || !encoded.matches("[A-Za-z0-9_-]{43}"))
            fail(HttpStatus.FORBIDDEN, "INVALID_CHALLENGE_PROOF");
        byte[] proof;
        try { proof = Base64.getUrlDecoder().decode(encoded); }
        catch (IllegalArgumentException ex) { fail(HttpStatus.FORBIDDEN, "INVALID_CHALLENGE_PROOF"); return null; }
        if (proof.length != 32) fail(HttpStatus.FORBIDDEN, "INVALID_CHALLENGE_PROOF");
        return proof;
    }

    private static void fail(HttpStatus status, String code) { throw new ResponseStatusException(status, code); }
}
