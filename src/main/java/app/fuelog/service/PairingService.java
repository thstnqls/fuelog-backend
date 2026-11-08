package app.fuelog.service;

import static app.fuelog.service.InstallationAuth.sha256;

import app.fuelog.dto.PairingDtos;
import app.fuelog.entity.PairingRequest;
import app.fuelog.repository.PairingRepository;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.Locale;
import java.util.UUID;

/** 설치 ID·비밀값과 차량 화면의 최종 확인 코드를 사용하는 기존 연결 흐름. */
@Service
public class PairingService {
    private static final SecureRandom RANDOM = new SecureRandom();
    private final PairingRepository repository;
    private final InstallationAccess access;

    public PairingService(PairingRepository repository, InstallationAccess access) {
        this.repository = repository;
        this.access = access;
    }

    @Transactional
    public PairingDtos.InstallationIssued createInstallation() {
        byte[] secret = new byte[32];
        RANDOM.nextBytes(secret);
        UUID id = UUID.randomUUID();
        repository.insertInstallation(id, sha256(secret), Instant.now());
        return new PairingDtos.InstallationIssued(
                id, Base64.getUrlEncoder().withoutPadding().encodeToString(secret));
    }

    @Transactional
    public PairingDtos.Issued issue(UUID installation, String key) {
        validKey(key);
        Instant now = Instant.now();
        repository.lockInstallation(installation);
        if (access.currentUser(installation) != null) conflict("Installation already paired");
        if (repository.issueKeyExists(installation, key))
            conflict("Idempotency-Key already used; keep the original QR response");
        repository.expireForInstallation(installation, now);
        repository.revokeActive(installation, now);

        byte[] proof = new byte[32];
        RANDOM.nextBytes(proof);
        UUID id = UUID.randomUUID();
        Instant expires = now.plusSeconds(120);
        repository.insertRequest(id, installation, sha256(proof), expires, key, now);
        return new PairingDtos.Issued(
                id,
                expires,
                new PairingDtos.QrPayload(
                        1,
                        "fuelog.vehicle_pairing",
                        id,
                        Base64.getUrlEncoder().withoutPadding().encodeToString(proof)));
    }

    @Transactional
    public PairingDtos.Status current(UUID installation) {
        repository.expireForInstallation(installation, Instant.now());
        return view(repository.current(installation));
    }

    @Transactional(readOnly = true)
    public PairingDtos.Preview preview(UUID user, PairingDtos.PreviewRequest body) {
        PairingRequest request = repository.get(body.request_id(), false);
        // 완료된 QR은 새 권한을 발급하지 않고 현재 연결된 본인의 로그인 상태만 반환한다.
        if (isCurrentPairing(request, user))
            return new PairingDtos.Preview(request.id(), request.expiresAt(), "completed", request.selectedVehicleId());
        requirePending(request);
        requireProof(request, body.proof());
        return new PairingDtos.Preview(request.id(), request.expiresAt(), "pending", null);
    }

    @Transactional
    public PairingDtos.Status intent(
            UUID user, UUID id, String key, PairingDtos.IntentRequest body) {
        validKey(key);
        PairingRequest request = repository.get(id, true);
        if (user.equals(request.approvedUserId())
                && key.equals(request.intentKey())
                && ("awaiting_vehicle_confirmation".equals(request.status())
                        || "completed".equals(request.status()))) {
            if (!"completed".equals(request.status())
                    && !Instant.now().isBefore(request.expiresAt())) gone("Request expired");
            return view(request);
        }
        requirePending(request);
        requireProof(request, body.proof());

        boolean existing = body.vehicle_id() != null;
        boolean creating = body.new_vehicle_name() != null && !body.new_vehicle_name().isBlank();
        if (existing == creating)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Choose one vehicle option");
        if (existing) requireManager(user, body.vehicle_id());
        String code = String.format(Locale.ROOT, "%06d", RANDOM.nextInt(1_000_000));
        repository.selectIntent(
                id,
                user,
                body.vehicle_id(),
                creating ? body.new_vehicle_name().trim() : null,
                key,
                code,
                Instant.now());
        return view(repository.get(id, false));
    }

    @Transactional
    public PairingDtos.Status confirm(UUID installation, UUID id, String confirmationCode) {
        repository.lockInstallation(installation);
        PairingRequest request = repository.get(id, true);
        if (!installation.equals(request.installationId())) forbidden();
        if (!Instant.now().isBefore(request.expiresAt())) gone("Request expired");
        if (!"awaiting_vehicle_confirmation".equals(request.status()))
            conflict("Request is not confirmable");
        if (confirmationCode == null
                || request.confirmationCode() == null
                || !MessageDigest.isEqual(
                        confirmationCode.getBytes(java.nio.charset.StandardCharsets.US_ASCII),
                        request.confirmationCode()
                                .getBytes(java.nio.charset.StandardCharsets.US_ASCII))) forbidden();
        UUID knownVehicle = repository.linkedVehicle(installation);
        if (knownVehicle != null && !knownVehicle.equals(request.selectedVehicleId()))
            conflict("Existing vehicle must be selected; reset registration to change vehicles");
        if (access.currentUser(installation) != null) conflict("Installation already paired");

        UUID vehicle = request.selectedVehicleId();
        Instant now = Instant.now();
        if (vehicle != null) {
            requireManager(request.approvedUserId(), vehicle);
        } else {
            if (request.newVehicleName() == null || request.newVehicleName().isBlank())
                conflict("Vehicle selection missing");
            vehicle = UUID.randomUUID();
            repository.createVehicle(
                    vehicle, id, request.approvedUserId(), request.newVehicleName(), now);
        }
        if (knownVehicle == null) {
            if (!repository.linkInstallation(installation, vehicle, now)) conflict("Installation already paired");
        } else repository.resumeInstallation(installation, now);
        repository.complete(id, vehicle, now);
        return view(repository.get(id, false));
    }

    @Transactional
    public PairingDtos.Status status(UUID actor, String type, UUID id) {
        PairingRequest request = repository.get(id, false);
        if ("installation".equals(type)
                ? !actor.equals(request.installationId())
                : !actor.equals(request.approvedUserId())) forbidden();
        if (!Instant.now().isBefore(request.expiresAt()) && request.active()) {
            repository.expireOne(id, Instant.now());
            request = repository.get(id, false);
        }
        return view(request);
    }

    @Transactional
    public PairingDtos.Status cancel(UUID installation, UUID id) {
        repository.lockInstallation(installation);
        PairingRequest request = repository.get(id, true);
        if (!installation.equals(request.installationId())) forbidden();
        if (request.active()) repository.cancel(id, Instant.now());
        return view(repository.get(id, false));
    }

    @Transactional(readOnly = true)
    public PairingDtos.Installation installation(UUID installation) {
        UUID vehicle = repository.linkedVehicle(installation);
        var user = access.authenticatedUser(installation);
        return new PairingDtos.Installation(installation, vehicle, user != null,
                user == null ? null : user.subjectId(), user == null ? null : user.displayName(),
                vehicle == null ? null : repository.vehicleName(vehicle));
    }

    private void requireManager(UUID user, UUID vehicle) {
        if (user == null || !repository.userManagesVehicle(user, vehicle)) forbidden();
    }

    private boolean isCurrentPairing(PairingRequest request, UUID user) {
        return "completed".equals(request.status())
                && user.equals(request.approvedUserId())
                && request.selectedVehicleId() != null
                && request.selectedVehicleId().equals(repository.linkedVehicle(request.installationId()))
                && user.equals(access.currentUser(request.installationId()));
    }

    private static void requirePending(PairingRequest request) {
        if (!Instant.now().isBefore(request.expiresAt()) || "expired".equals(request.status()))
            gone("Request expired");
        if ("revoked".equals(request.status())) gone("Request revoked");
        if (!"pending".equals(request.status())) conflict("Request is not pending");
    }

    private static void requireProof(PairingRequest request, String encoded) {
        if (encoded == null || !encoded.matches("[A-Za-z0-9_-]{43}")) forbidden();
        byte[] proof;
        try {
            proof = Base64.getUrlDecoder().decode(encoded);
        } catch (IllegalArgumentException ex) {
            forbidden();
            return;
        }
        if (proof.length != 32
                || request.proofHash() == null
                || !MessageDigest.isEqual(request.proofHash(), sha256(proof))) forbidden();
    }

    private PairingDtos.Status view(PairingRequest request) {
        String vehicleName = request.newVehicleName();
        if (vehicleName == null && request.selectedVehicleId() != null)
            vehicleName = repository.vehicleName(request.selectedVehicleId());
        return new PairingDtos.Status(
                request.id(),
                request.status(),
                request.expiresAt(),
                request.selectedVehicleId(),
                request.approvedUserId(),
                vehicleName,
                request.confirmationCode());
    }

    private static void validKey(String key) {
        if (key == null || key.isBlank() || key.length() > 100)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid Idempotency-Key");
    }

    private static void conflict(String reason) {
        throw new ResponseStatusException(HttpStatus.CONFLICT, reason);
    }

    private static void gone(String reason) {
        throw new ResponseStatusException(HttpStatus.GONE, reason);
    }

    private static void forbidden() {
        throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Not allowed");
    }
}
