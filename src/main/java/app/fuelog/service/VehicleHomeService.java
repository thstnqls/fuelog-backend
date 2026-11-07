package app.fuelog.service;

import app.fuelog.dto.VehicleHomeDtos;
import app.fuelog.repository.PairingRepository;
import app.fuelog.repository.TripUserCandidateRepository;
import app.fuelog.repository.VehicleHomeRepository;
import app.fuelog.repository.VehicleHomeRepository.Challenge;
import app.fuelog.repository.VehicleHomeRepository.Interval;
import app.fuelog.repository.VehicleHomeRepository.Session;

import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.UUID;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** 개인 홈 세션과 수동 운행 기록을 관리하며 설치·차량·사용자 범위를 검사한다. */
@Service
public class VehicleHomeService {
    private static final Duration CHALLENGE_LIFETIME = Duration.ofMinutes(2);
    private static final Duration SESSION_LIFETIME = Duration.ofMinutes(30);
    private static final Duration SAMPLE_FRESHNESS = Duration.ofMinutes(1);
    private static final Duration CLOCK_SKEW_TOLERANCE = Duration.ofMinutes(5);
    private final InstallationAccess access;
    private final PairingRepository pairing;
    private final VehicleHomeRepository repository;
    private final InstallationAuth auth;
    private final PairingActor actor;
    private final TripUserCandidateRepository candidates;

    public VehicleHomeService(
            PairingRepository pairing,
            VehicleHomeRepository repository,
            InstallationAuth auth,
            PairingActor actor,
            TripUserCandidateRepository candidates,
            InstallationAccess access) {
        this.access = access;
        this.pairing = pairing;
        this.repository = repository;
        this.auth = auth;
        this.actor = actor;
        this.candidates = candidates;
    }

    @Transactional
    public VehicleHomeDtos.Challenge challenge(
            String bearer, VehicleHomeDtos.ChallengeRequest body) {
        var installation = auth.require(bearer);
        if (body.installationId() != null && !installation.id().equals(body.installationId()))
            fail(HttpStatus.FORBIDDEN, "INSTALLATION_MISMATCH");
        pairing.lockInstallation(installation.id());
        access.requireChallenge(installation.id());
        UUID vehicle = linkedVehicle(installation.id());
        UUID intervalId = body.intervalId();
        if (intervalId != null) {
            Interval interval = repository.interval(intervalId, false);
            if (!installation.id().equals(interval.installationId())
                    || !vehicle.equals(interval.vehicleId()))
                fail(HttpStatus.FORBIDDEN, "INTERVAL_MISMATCH");
            if (!"recording".equals(interval.status()))
                fail(HttpStatus.CONFLICT, "INTERVAL_FINISHED");
        } else if (body.installationId() == null
                || body.operationId() == null
                || body.operationId().isBlank()) {
            fail(HttpStatus.BAD_REQUEST, "INVALID_CHALLENGE_REQUEST");
        }
        String operationId =
                body.operationId() == null || body.operationId().isBlank()
                        ? "c03:" + UUID.randomUUID()
                        : body.operationId();
        Instant now = Instant.now();
        Challenge existing = repository.challengeForOperation(installation.id(), operationId);
        if (existing != null) {
            if (!java.util.Objects.equals(existing.intervalId(), intervalId))
                fail(HttpStatus.CONFLICT, "OPERATION_ID_REUSED");
            return challengeView(existing, installation.secret(), now);
        }
        repository.revokeActiveSessions(installation.id(), now);
        repository.incrementSessionVersion(installation.id());
        repository.revokePendingChallenges(installation.id(), now);
        if (candidates.revokeForInstallation(installation.id(), now) > 0)
            candidates.addEvent(installation.id(), "candidates_changed", now);
        UUID id = UUID.randomUUID();
        byte[] proof = derive(installation.secret(), "fuelog.trip-user.challenge.v1:", id);
        repository.createChallenge(
                id,
                installation.id(),
                vehicle,
                intervalId,
                operationId,
                InstallationAuth.sha256(proof),
                now.plus(CHALLENGE_LIFETIME),
                now);
        return challengeView(repository.challenge(id, false), installation.secret(), now);
    }

    @Transactional
    public VehicleHomeDtos.MobileApproval approve(
            Jwt jwt, VehicleHomeDtos.MobileApprovalRequest body) {
        UUID user = actor.requireUser(jwt);
        Challenge first = repository.challenge(body.challengeId(), false);
        pairing.lockInstallation(first.installationId());
        Challenge challenge = repository.challenge(body.challengeId(), true);
        Instant now = Instant.now();
        if (challenge.intervalId() != null)
            fail(HttpStatus.CONFLICT, "INTERVAL_CHALLENGE_REQUIRES_CONSENT");
        if ("revoked".equals(challenge.status()) || "expired".equals(challenge.status()))
            fail(HttpStatus.GONE, "CHALLENGE_UNAVAILABLE");
        if (!"pending".equals(challenge.status()))
            fail(HttpStatus.CONFLICT, "CHALLENGE_ALREADY_USED");
        if (!now.isBefore(challenge.expiresAt())) fail(HttpStatus.GONE, "CHALLENGE_EXPIRED");
        if (!challenge.vehicleId().equals(body.vehicleId())
                || !challenge.vehicleId().equals(pairing.linkedVehicle(challenge.installationId())))
            fail(HttpStatus.FORBIDDEN, "VEHICLE_MISMATCH");
        requireProof(challenge.proofHash(), body.proof());
        if (!repository.member(user, body.vehicleId()))
            fail(HttpStatus.FORBIDDEN, "VEHICLE_MEMBERSHIP_REQUIRED");
        access.verified(challenge.installationId(), challenge.id());
        String displayName = displayName(jwt);
        repository.revokeActiveSessions(challenge.installationId(), now);
        long version = repository.incrementSessionVersion(challenge.installationId());
        UUID sessionId = UUID.randomUUID();
        repository.createSession(
                sessionId,
                challenge.installationId(),
                body.vehicleId(),
                user,
                displayName,
                version,
                now.plus(SESSION_LIFETIME),
                now);
        repository.approveChallenge(challenge.id(), user, displayName, sessionId, now);
        return new VehicleHomeDtos.MobileApproval(challenge.id(), "APPROVED", version);
    }

    @Transactional
    public VehicleHomeDtos.Session session(String bearer, VehicleHomeDtos.SessionRequest body) {
        var installation = auth.require(bearer);
        pairing.lockInstallation(installation.id());
        Challenge challenge = repository.challenge(body.challengeId(), true);
        if (!installation.id().equals(challenge.installationId()))
            fail(HttpStatus.FORBIDDEN, "INSTALLATION_MISMATCH");
        Instant now = Instant.now();
        if ("pending".equals(challenge.status()) && !now.isBefore(challenge.expiresAt())) {
            repository.expireChallenge(challenge.id(), now);
            challenge = repository.challenge(challenge.id(), false);
        }
        long version = repository.sessionVersion(installation.id());
        if ("pending".equals(challenge.status()))
            return new VehicleHomeDtos.Session(
                    "PENDING", challenge.id(), null, null, version, null, null);
        if ("expired".equals(challenge.status()) || "revoked".equals(challenge.status()))
            return new VehicleHomeDtos.Session(
                    challenge.status().toUpperCase(),
                    challenge.id(),
                    null,
                    null,
                    version,
                    null,
                    null);
        if (challenge.sessionId() == null)
            return new VehicleHomeDtos.Session(
                    "REVOKED", challenge.id(), null, null, version, null, null);
        Session session = repository.session(challenge.sessionId());
        if (!"active".equals(session.status())
                || !now.isBefore(session.expiresAt())
                || session.version() != version
                || !session.vehicleId().equals(pairing.linkedVehicle(installation.id()))
                || !repository.member(session.userId(), session.vehicleId()))
            return new VehicleHomeDtos.Session(
                    "REVOKED", challenge.id(), null, null, version, null, null);
        byte[] token = derive(installation.secret(), "fuelog.trip-user.session.v1:", session.id());
        // 설치 비밀값 소유를 확인한 장치만 재시도 가능한 토큰을 받고 DB에는 해시만 남긴다.
        byte[] tokenHash = InstallationAuth.sha256(token);
        repository.replaceSessionTokenHash(session.id(), tokenHash);
        return new VehicleHomeDtos.Session(
                "ACTIVE",
                challenge.id(),
                session.displayName(),
                session.userId(),
                version,
                session.expiresAt(),
                Base64.getUrlEncoder().withoutPadding().encodeToString(token));
    }

    @Transactional
    public VehicleHomeDtos.SessionDeleted logout(String bearer, String scopedBearer) {
        var installation = auth.require(bearer);
        pairing.lockInstallation(installation.id());
        requireSession(installation.id(), scopedBearer);
        Instant now = Instant.now();
        repository.revokeActiveSessions(installation.id(), now);
        long version = repository.incrementSessionVersion(installation.id());
        return new VehicleHomeDtos.SessionDeleted(version, "REVOKED");
    }

    @Transactional(readOnly = true)
    public VehicleHomeDtos.Home home(String bearer, String scopedBearer) {
        var installation = auth.require(bearer);
        Instant now = Instant.now();
        Session session =
                scopedBearer == null ? null : requireSession(installation.id(), scopedBearer);
        UUID vehicleId = linkedVehicle(installation.id());
        var vehicle = repository.vehicle(vehicleId);
        Interval latest =
                repository.latestIntervalForUser(
                        installation.id(), vehicleId, session == null ? null : session.userId());
        String recordingStatus = latest == null ? "unknown" : latest.status();
        Instant lastSampleAt = latest == null ? null : latest.lastSampleAt();
        var recordingState = state(lastSampleAt, now);
        var syncState = state(latest == null ? null : latest.lastSyncedAt(), now);
        VehicleHomeDtos.DataStatus privateUnavailable =
                new VehicleHomeDtos.DataStatus(
                        session == null ? "needs_user_proof" : "unsupported", null);
        BigDecimal distance = null;
        VehicleHomeDtos.DataStatus distanceState = privateUnavailable;
        VehicleHomeDtos.DataStatus fuelState = privateUnavailable;
        VehicleHomeDtos.DataStatus costState = privateUnavailable;
        if (session != null) {
            var observed = repository.distanceForUser(vehicleId, session.userId());
            if (observed.km() != null) {
                distance = observed.km();
                distanceState = state(observed.observedAt(), now);
            }
            var fuel = repository.latestFuelForUser(vehicleId, session.userId());
            if (fuel != null) {
                fuelState =
                        switch (fuel.status()) {
                            case "available" -> state(fuel.observedAt(), now);
                            case "error" -> new VehicleHomeDtos.DataStatus(
                                    "error", fuel.observedAt());
                            default -> new VehicleHomeDtos.DataStatus(
                                    "unsupported", fuel.observedAt());
                        };
                if ("error".equals(fuel.status()))
                    costState = new VehicleHomeDtos.DataStatus("error", fuel.observedAt());
            }
        }
        return new VehicleHomeDtos.Home(
                vehicle.alias(),
                new VehicleHomeDtos.DataStatus("available", vehicle.observedAt()),
                repository.sessionVersion(installation.id()),
                recordingStatus,
                recordingState,
                lastSampleAt,
                distance,
                distanceState,
                fuelState,
                costState,
                null,
                syncState,
                null,
                privateUnavailable,
                null,
                privateUnavailable,
                now);
    }

    @Transactional
    public VehicleHomeDtos.Interval start(
            String bearer, String scopedBearer, VehicleHomeDtos.IntervalStartRequest body) {
        var installation = auth.require(bearer);
        pairing.lockInstallation(installation.id());
        access.requireChallenge(installation.id());
        UUID vehicle = linkedVehicle(installation.id());
        Session session =
                scopedBearer == null ? null : requireSession(installation.id(), scopedBearer);
        Interval existing = repository.intervalByLocalId(installation.id(), body.localIntervalId());
        if (existing != null) {
            if (!vehicle.equals(existing.vehicleId())
                    || !"manual_app".equals(body.source())
                    || !existing.startedAt().equals(dbTime(body.startedAt())))
                fail(HttpStatus.CONFLICT, "INTERVAL_ID_REUSED");
            return intervalView(existing);
        }
        if (!"manual_app".equals(body.source()))
            fail(HttpStatus.BAD_REQUEST, "INVALID_RECORDING_SOURCE");
        UUID id = UUID.randomUUID();
        repository.createInterval(
                id,
                installation.id(),
                vehicle,
                session == null ? null : session.userId(),
                session == null ? null : session.version(),
                body.localIntervalId(),
                dbTime(body.startedAt()),
                Instant.now());
        return intervalView(repository.interval(id, false));
    }

    @Transactional
    public VehicleHomeDtos.SamplesAccepted samples(
            String bearer, UUID id, UUID candidateId, VehicleHomeDtos.SamplesRequest body) {
        var installation = auth.require(bearer);
        pairing.lockInstallation(installation.id());
        access.requireCollection(installation.id());
        Interval interval = repository.interval(id, true);
        if (!installation.id().equals(interval.installationId()))
            fail(HttpStatus.FORBIDDEN, "INSTALLATION_MISMATCH");
        if (!interval.vehicleId().equals(pairing.linkedVehicle(installation.id())))
            fail(HttpStatus.CONFLICT, "VEHICLE_CHANGED");
        UUID sampleUser = interval.userId();
        if (candidateId != null) {
            var candidate = candidates.get(candidateId, true);
            if (!installation.id().equals(candidate.installationId())
                    || !interval.vehicleId().equals(candidate.vehicleId())
                    || !interval.id().equals(candidate.intervalId()))
                fail(HttpStatus.FORBIDDEN, "CANDIDATE_SCOPE_MISMATCH");
            if ("revoked".equals(candidate.status())
                    || "expired".equals(candidate.status())
                    || !Instant.now().isBefore(candidate.expiresAt())
                    || candidate.verificationExpiresAt() == null
                    || !Instant.now().isBefore(candidate.verificationExpiresAt()))
                fail(HttpStatus.GONE, "CANDIDATE_UNAVAILABLE");
            if (!"verified".equals(candidate.status()))
                fail(HttpStatus.CONFLICT, "CANDIDATE_NOT_VERIFIED");
            if (sampleUser != null && !sampleUser.equals(candidate.userId()))
                fail(HttpStatus.FORBIDDEN, "INTERVAL_OWNER_MISMATCH");
            sampleUser = candidate.userId();
        }
        if (sampleUser != null && !candidates.memberForUpdate(sampleUser, interval.vehicleId()))
            fail(HttpStatus.FORBIDDEN, "VEHICLE_MEMBERSHIP_REQUIRED");
        byte[] hash = batchHash(body, candidateId);
        var previous = repository.batch(id, body.batchId());
        if (previous != null) {
            if (!MessageDigest.isEqual(previous.payloadHash(), hash))
                fail(HttpStatus.CONFLICT, "BATCH_ID_REUSED");
            return new VehicleHomeDtos.SamplesAccepted(
                    id,
                    body.batchId(),
                    previous.acceptedCount(),
                    previous.lastAcceptedSequence(),
                    previous.receivedAt());
        }
        if (!"recording".equals(interval.status())) fail(HttpStatus.CONFLICT, "INTERVAL_FINISHED");
        Instant now = Instant.now();
        long highest = 0;
        Instant latestObserved = null;
        for (var sample : body.samples()) {
            if (sample.sequence() <= highest)
                fail(HttpStatus.BAD_REQUEST, "SEQUENCE_NOT_INCREASING");
            highest = sample.sequence();
            validateSample(sample);
            if (latestObserved == null || sample.observedAt().isAfter(latestObserved))
                latestObserved = sample.observedAt();
            String fuelStatus =
                    sample.fuelLiters() == null
                            ? "unsupported"
                            : sample.fuelLiters().signum() < 0
                                            || sample.fuelLiters().compareTo(new BigDecimal("1000"))
                                                    > 0
                                            || sample.fuelLiters().scale() > 3
                                            || sample.fuelLiters().precision() > 10
                                    ? "error"
                                    : "available";
            repository.insertSample(
                    id,
                    body.batchId(),
                    sample.sequence(),
                    sample.observedAt(),
                    sample.distanceKm(),
                    sample.missingReason(),
                    "available".equals(fuelStatus) ? sample.fuelLiters() : null,
                    fuelStatus,
                    sampleUser,
                    candidateId,
                    now);
        }
        long acceptedSequence = Math.max(interval.lastAcceptedSequence(), highest);
        repository.insertBatch(
                id, body.batchId(), hash, body.samples().size(), acceptedSequence, now);
        repository.updateIntervalAfterSamples(id, highest, latestObserved, now);
        return new VehicleHomeDtos.SamplesAccepted(
                id, body.batchId(), body.samples().size(), acceptedSequence, now);
    }

    @Transactional
    public VehicleHomeDtos.Interval finish(
            String bearer, UUID id, VehicleHomeDtos.FinishRequest body) {
        var installation = auth.require(bearer);
        pairing.lockInstallation(installation.id());
        access.requireCollection(installation.id());
        Interval interval = repository.interval(id, true);
        if (!installation.id().equals(interval.installationId()))
            fail(HttpStatus.FORBIDDEN, "INSTALLATION_MISMATCH");
        if (!interval.vehicleId().equals(pairing.linkedVehicle(installation.id())))
            fail(HttpStatus.CONFLICT, "VEHICLE_CHANGED");
        if ("finished".equals(interval.status())) {
            if (body.operationId().equals(interval.finishOperationId())
                    && dbTime(body.endedAt()).equals(interval.endedAt())
                    && body.lastSequence().equals(interval.finishLastSequence()))
                return intervalView(interval);
            fail(HttpStatus.CONFLICT, "INTERVAL_ALREADY_FINISHED");
        }
        if (dbTime(body.endedAt()).isBefore(interval.startedAt())
                || body.lastSequence() < interval.lastAcceptedSequence()
                || body.lastSequence() < 0) fail(HttpStatus.BAD_REQUEST, "INVALID_FINISH");
        repository.finishInterval(
                id, body.operationId(), dbTime(body.endedAt()), body.lastSequence(), Instant.now());
        return intervalView(repository.interval(id, false));
    }

    @Transactional(readOnly = true)
    public VehicleHomeDtos.SyncStatus sync(String bearer) {
        var installation = auth.require(bearer);
        UUID vehicle = linkedVehicle(installation.id());
        Interval latest = repository.latestInterval(installation.id(), vehicle);
        Instant last = latest == null ? null : latest.lastSyncedAt();
        return new VehicleHomeDtos.SyncStatus(
                latest == null ? null : latest.id(),
                latest == null ? 0 : latest.lastAcceptedSequence(),
                last,
                null,
                null,
                state(last, Instant.now()));
    }

    @Transactional
    public void revokeForInstallationReset(UUID installation) {
        pairing.lockInstallation(installation);
        repository.revokePendingChallenges(installation, Instant.now());
        repository.revokeActiveSessions(installation, Instant.now());
        Instant now = Instant.now();
        if (candidates.revokeForInstallation(installation, now) > 0)
            candidates.addEvent(installation, "candidates_changed", now);
        repository.incrementSessionVersion(installation);
    }

    private Session requireSession(UUID installation, String bearer) {
        if (bearer == null || !bearer.startsWith("Bearer "))
            fail(HttpStatus.UNAUTHORIZED, "INVALID_USER_SESSION");
        String encoded = bearer.substring(7);
        if (!encoded.matches("[A-Za-z0-9_-]{43}"))
            fail(HttpStatus.UNAUTHORIZED, "INVALID_USER_SESSION");
        byte[] token;
        try {
            token = Base64.getUrlDecoder().decode(encoded);
        } catch (IllegalArgumentException ex) {
            fail(HttpStatus.UNAUTHORIZED, "INVALID_USER_SESSION");
            return null;
        }
        Session session = repository.sessionByTokenHash(InstallationAuth.sha256(token));
        if (session == null
                || !"active".equals(session.status())
                || !Instant.now().isBefore(session.expiresAt()))
            fail(HttpStatus.UNAUTHORIZED, "INVALID_USER_SESSION");
        if (!installation.equals(session.installationId()))
            fail(HttpStatus.FORBIDDEN, "SESSION_INSTALLATION_MISMATCH");
        if (!session.vehicleId().equals(pairing.linkedVehicle(installation))
                || session.version() != repository.sessionVersion(installation)
                || !repository.member(session.userId(), session.vehicleId()))
            fail(HttpStatus.FORBIDDEN, "SESSION_REVOKED");
        return session;
    }

    private UUID linkedVehicle(UUID installation) {
        UUID vehicle = pairing.linkedVehicle(installation);
        if (vehicle == null) fail(HttpStatus.CONFLICT, "INSTALLATION_NOT_LINKED");
        return vehicle;
    }

    private static VehicleHomeDtos.DataStatus state(Instant observed, Instant now) {
        if (observed == null) return new VehicleHomeDtos.DataStatus("unsupported", null);
        if (observed.isAfter(now.plus(CLOCK_SKEW_TOLERANCE)))
            return new VehicleHomeDtos.DataStatus("error", observed);
        return new VehicleHomeDtos.DataStatus(
                observed.isBefore(now.minus(SAMPLE_FRESHNESS)) ? "stale" : "available", observed);
    }

    private static Instant dbTime(Instant instant) {
        return instant.truncatedTo(ChronoUnit.MICROS);
    }

    private static VehicleHomeDtos.Interval intervalView(Interval value) {
        return new VehicleHomeDtos.Interval(
                value.id(),
                value.vehicleId(),
                value.localIntervalId(),
                value.status(),
                value.startedAt(),
                value.endedAt(),
                value.userId(),
                value.lastAcceptedSequence(),
                value.lastSampleAt());
    }

    private static VehicleHomeDtos.Challenge challengeView(
            Challenge value, byte[] secret, Instant now) {
        if (!"pending".equals(value.status()) || !now.isBefore(value.expiresAt()))
            return new VehicleHomeDtos.Challenge(
                    value.id(),
                    "pending".equals(value.status()) ? "EXPIRED" : value.status().toUpperCase(),
                    value.expiresAt(),
                    null,
                    "fuelog.trip-user",
                    1);
        byte[] proof = derive(secret, "fuelog.trip-user.challenge.v1:", value.id());
        if (value.proofHash() == null
                || !MessageDigest.isEqual(value.proofHash(), InstallationAuth.sha256(proof)))
            fail(HttpStatus.CONFLICT, "CHALLENGE_NOT_RECOVERABLE");
        return new VehicleHomeDtos.Challenge(
                value.id(),
                "PENDING",
                value.expiresAt(),
                Base64.getUrlEncoder().withoutPadding().encodeToString(proof),
                "fuelog.trip-user",
                1);
    }

    private static void requireProof(byte[] hash, String encoded) {
        if (hash == null || encoded == null || !encoded.matches("[A-Za-z0-9_-]{43}"))
            fail(HttpStatus.FORBIDDEN, "INVALID_CHALLENGE_PROOF");
        byte[] supplied;
        try {
            supplied = Base64.getUrlDecoder().decode(encoded);
        } catch (IllegalArgumentException ex) {
            fail(HttpStatus.FORBIDDEN, "INVALID_CHALLENGE_PROOF");
            return;
        }
        if (supplied.length != 32
                || !MessageDigest.isEqual(hash, InstallationAuth.sha256(supplied)))
            fail(HttpStatus.FORBIDDEN, "INVALID_CHALLENGE_PROOF");
    }

    static String displayName(Jwt jwt) {
        var metadata = jwt.getClaimAsMap("user_metadata");
        Object candidate = metadata == null ? null : metadata.get("full_name");
        if (!(candidate instanceof String text) || text.isBlank())
            candidate = jwt.getClaimAsString("email");
        String value =
                candidate instanceof String text && !text.isBlank()
                        ? text.trim()
                        : jwt.getSubject().substring(0, 8);
        value = value.replaceAll("\\p{Cntrl}", " ").trim();
        if (value.isBlank()) value = jwt.getSubject().substring(0, 8);
        return value.length() > 100 ? value.substring(0, 100) : value;
    }

    private static void validateSample(VehicleHomeDtos.Sample sample) {
        if (sample.sequence() <= 0) fail(HttpStatus.BAD_REQUEST, "INVALID_SEQUENCE");
        boolean hasDistance = sample.distanceKm() != null;
        boolean hasReason = sample.missingReason() != null && !sample.missingReason().isBlank();
        if (hasDistance == hasReason) fail(HttpStatus.BAD_REQUEST, "INVALID_SAMPLE");
        if (hasDistance
                && (sample.distanceKm().signum() < 0
                        || sample.distanceKm().compareTo(new BigDecimal("1000")) > 0
                        || sample.distanceKm().scale() > 3
                        || sample.distanceKm().precision() > 12))
            fail(HttpStatus.BAD_REQUEST, "INVALID_DISTANCE");
    }

    // 재시도 비교 형식이므로 필드 순서와 숫자 표현을 임의로 바꾸면 기존 배치와 충돌한다.
    private static byte[] batchHash(VehicleHomeDtos.SamplesRequest body, UUID candidateId) {
        try {
            var bytes = new ByteArrayOutputStream();
            var out = new DataOutputStream(bytes);
            out.writeUTF(candidateId == null ? "" : candidateId.toString());
            out.writeUTF(body.batchId());
            out.writeInt(body.samples().size());
            for (var sample : body.samples()) {
                out.writeLong(sample.sequence());
                out.writeUTF(sample.observedAt().toString());
                out.writeUTF(sample.distanceKm() == null ? "" : sample.distanceKm().toString());
                out.writeUTF(sample.missingReason() == null ? "" : sample.missingReason());
                out.writeUTF(sample.fuelLiters() == null ? "" : sample.fuelLiters().toString());
            }
            return InstallationAuth.sha256(bytes.toByteArray());
        } catch (IOException ex) {
            throw new IllegalStateException(ex);
        }
    }

    // QR과 세션은 서로 다른 접두어로 파생해 비밀값 원문 저장 없이 동일 응답을 복원한다.
    private static byte[] derive(byte[] key, String domain, UUID id) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return mac.doFinal((domain + id).getBytes(StandardCharsets.US_ASCII));
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }

    private static void fail(HttpStatus status, String code) {
        throw new ResponseStatusException(status, code);
    }
}
