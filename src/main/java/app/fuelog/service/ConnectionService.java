package app.fuelog.service;

import app.fuelog.dto.ConnectionDtos;
import app.fuelog.entity.PairingRequest;
import app.fuelog.repository.PairingRepository;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class ConnectionService {
    private final PairingRepository repository;
    private final VehicleHomeService homeService;

    public ConnectionService(PairingRepository repository, VehicleHomeService homeService) {
        this.repository = repository;
        this.homeService = homeService;
    }

    @Transactional
    public ConnectionDtos.Response issue(String bearer, ConnectionDtos.RequestBody body) {
        byte[] credential = credential(bearer);
        byte[] hash = sha256(credential);
        Instant now = Instant.now();
        UUID suppliedRequest = parseOptionalRequestId(body.requestId());
        if (suppliedRequest != null && repository.installationByCredentialHash(hash) == null)
            fail(HttpStatus.UNAUTHORIZED, "UNKNOWN_INSTALLATION");
        UUID installation = repository.ensureInstallation(hash, now);

        String key = body.operationId();
        PairingRequest existing = repository.requestForIssueKey(installation, key);
        if (existing != null) {
            if (suppliedRequest != null && !suppliedRequest.equals(existing.id()))
                fail(HttpStatus.CONFLICT, "REQUEST_ID_MISMATCH");
            return response(existing, credential, now);
        }
        if (repository.linkedVehicle(installation) != null) {
            PairingRequest latest = repository.current(installation);
            return new ConnectionDtos.Response(latest.id(), "ALREADY_LINKED", null, null);
        }
        if (suppliedRequest != null) {
            PairingRequest previous = repository.get(suppliedRequest, false);
            if (!installation.equals(previous.installationId())) fail(HttpStatus.FORBIDDEN, "INSTALLATION_MISMATCH");
        }
        repository.expireForInstallation(installation, now);
        repository.revokeActive(installation, now);
        UUID id = UUID.randomUUID();
        byte[] nonce = nonce(credential, id);
        repository.insertRequest(id, installation, sha256(nonce), now.plusSeconds(120), key, now);
        return response(repository.get(id, false), credential, now);
    }

    @Transactional
    public ConnectionDtos.Response status(String bearer, ConnectionDtos.StatusBody body) {
        UUID installation = knownInstallation(credential(bearer));
        PairingRequest request = repository.get(parseRequestId(body.requestId()), true);
        if (!installation.equals(request.installationId())) fail(HttpStatus.FORBIDDEN, "INSTALLATION_MISMATCH");
        if (!body.operationId().equals(request.issueKey()))
            fail(HttpStatus.CONFLICT, "OPERATION_ID_MISMATCH");
        Instant now = Instant.now();
        if (request.active() && !now.isBefore(request.expiresAt())) {
            repository.expireOne(request.id(), now);
            request = repository.get(request.id(), false);
        }
        return response(request, credential(bearer), now);
    }

    @Transactional(readOnly = true)
    public ConnectionDtos.Inspection inspect(UUID user, ConnectionDtos.Envelope envelope) {
        if (!"fuelog.vehicle-registration".equals(envelope.type()) || envelope.version() != 1)
            fail(HttpStatus.BAD_REQUEST, "INVALID_QR_TYPE");
        PairingRequest request = repository.get(envelope.requestId(), false);
        requireWaiting(request);
        requireNonce(request, envelope.nonce());
        return new ConnectionDtos.Inspection(request.id(), remaining(request.expiresAt(), Instant.now()));
    }

    @Transactional
    public ConnectionDtos.Approval approve(UUID user, ConnectionDtos.ApproveBody body) {
        PairingRequest initial = repository.get(body.requestId(), false);
        repository.lockInstallation(initial.installationId());
        PairingRequest request = repository.get(body.requestId(), true);
        String key = body.idempotencyKey();
        if ("completed".equals(request.status()) && user.equals(request.approvedUserId())
                && body.vehicleId().equals(request.selectedVehicleId()) && key.equals(request.intentKey())) {
            if (!body.vehicleId().equals(repository.linkedVehicle(request.installationId())))
                fail(HttpStatus.CONFLICT, "CONNECTION_RESET");
            return new ConnectionDtos.Approval(request.id(), body.vehicleId(), "COMPLETED");
        }
        requireWaiting(request);
        requireNonce(request, body.nonce());
        if (!repository.userManagesVehicle(user, body.vehicleId()))
            fail(HttpStatus.FORBIDDEN, "VEHICLE_PERMISSION_DENIED");
        if (repository.linkedVehicle(request.installationId()) != null)
            fail(HttpStatus.CONFLICT, "ALREADY_LINKED");
        Instant now = Instant.now();
        try {
            if (!repository.linkInstallation(request.installationId(), body.vehicleId(), now))
                fail(HttpStatus.CONFLICT, "ALREADY_LINKED");
            repository.completeDirect(request.id(), body.vehicleId(), user, key, now);
        } catch (DataIntegrityViolationException ex) {
            fail(HttpStatus.CONFLICT, "VEHICLE_ALREADY_LINKED");
        }
        return new ConnectionDtos.Approval(request.id(), body.vehicleId(), "COMPLETED");
    }

    @Transactional
    public ConnectionDtos.ResetResult reset(String bearer, UUID user, UUID vehicle) {
        UUID installation = knownInstallation(credential(bearer));
        repository.lockInstallation(installation);
        if (!vehicle.equals(repository.linkedVehicle(installation)))
            fail(HttpStatus.CONFLICT, "VEHICLE_MISMATCH");
        if (!repository.userManagesVehicle(user, vehicle))
            fail(HttpStatus.FORBIDDEN, "VEHICLE_PERMISSION_DENIED");
        Instant now = Instant.now();
        homeService.revokeForInstallationReset(installation);
        repository.revokeForReset(installation, now);
        repository.unlinkInstallation(installation, now);
        return new ConnectionDtos.ResetResult(installation, "RESET");
    }

    private UUID knownInstallation(byte[] credential) {
        UUID id = repository.installationByCredentialHash(sha256(credential));
        if (id == null) fail(HttpStatus.UNAUTHORIZED, "UNKNOWN_INSTALLATION");
        return id;
    }

    private static UUID parseOptionalRequestId(String value) {
        return value == null || value.isBlank() ? null : parseRequestId(value);
    }

    private static UUID parseRequestId(String value) {
        try { return UUID.fromString(value); }
        catch (IllegalArgumentException ex) { fail(HttpStatus.BAD_REQUEST, "INVALID_REQUEST_ID"); return null; }
    }

    private static ConnectionDtos.Response response(PairingRequest request, byte[] credential, Instant now) {
        if ("pending".equals(request.status()) && now.isBefore(request.expiresAt())) {
            byte[] nonce = nonce(credential, request.id());
            if (request.proofHash() == null || !MessageDigest.isEqual(request.proofHash(), sha256(nonce)))
                fail(HttpStatus.CONFLICT, "REQUEST_NOT_RECOVERABLE");
            return new ConnectionDtos.Response(request.id(), "WAITING",
                    remaining(request.expiresAt(), now), Base64.getUrlEncoder().withoutPadding().encodeToString(nonce));
        }
        String state = switch (request.status()) {
            case "completed" -> "COMPLETED";
            case "revoked" -> "REVOKED";
            case "pending", "awaiting_vehicle_confirmation", "expired" -> "EXPIRED";
            default -> throw new IllegalStateException("Unknown pairing status");
        };
        return new ConnectionDtos.Response(request.id(), state, null, null);
    }

    private static int remaining(Instant expires, Instant now) {
        long millis = Duration.between(now, expires).toMillis();
        return (int) Math.max(1, Math.min(120, (millis + 999) / 1000));
    }

    private static void requireWaiting(PairingRequest request) {
        if ("revoked".equals(request.status())) fail(HttpStatus.GONE, "REQUEST_REVOKED");
        if (!Instant.now().isBefore(request.expiresAt()) || "expired".equals(request.status()))
            fail(HttpStatus.GONE, "REQUEST_EXPIRED");
        if (!"pending".equals(request.status())) fail(HttpStatus.CONFLICT, "REQUEST_ALREADY_USED");
    }

    private static void requireNonce(PairingRequest request, String encoded) {
        if (encoded == null || !encoded.matches("[A-Za-z0-9_-]{43}"))
            fail(HttpStatus.FORBIDDEN, "INVALID_NONCE");
        byte[] supplied;
        try { supplied = Base64.getUrlDecoder().decode(encoded); }
        catch (IllegalArgumentException ex) { fail(HttpStatus.FORBIDDEN, "INVALID_NONCE"); return; }
        if (supplied.length != 32) fail(HttpStatus.FORBIDDEN, "INVALID_NONCE");
        if (request.proofHash() == null || !MessageDigest.isEqual(request.proofHash(), sha256(supplied)))
            fail(HttpStatus.FORBIDDEN, "INVALID_NONCE");
    }

    private static byte[] credential(String bearer) {
        if (bearer == null || !bearer.startsWith("Bearer ")) fail(HttpStatus.UNAUTHORIZED, "INVALID_INSTALLATION_CREDENTIAL");
        byte[] value = decode(bearer.substring(7));
        if (value.length < 32) fail(HttpStatus.UNAUTHORIZED, "INVALID_INSTALLATION_CREDENTIAL");
        return value;
    }

    private static byte[] decode(String encoded) {
        if (encoded == null || !encoded.matches("[A-Za-z0-9_-]{43,}"))
            fail(HttpStatus.UNAUTHORIZED, "INVALID_CREDENTIAL_FORMAT");
        try { return Base64.getUrlDecoder().decode(encoded); }
        catch (IllegalArgumentException ex) { fail(HttpStatus.UNAUTHORIZED, "INVALID_CREDENTIAL_FORMAT"); return null; }
    }

    private static byte[] nonce(byte[] credential, UUID request) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(credential, "HmacSHA256"));
            return mac.doFinal(("fuelog.connection.v1:" + request).getBytes(StandardCharsets.US_ASCII));
        } catch (Exception ex) { throw new IllegalStateException(ex); }
    }

    private static byte[] sha256(byte[] value) {
        try { return MessageDigest.getInstance("SHA-256").digest(value); }
        catch (NoSuchAlgorithmException ex) { throw new IllegalStateException(ex); }
    }

    private static void fail(HttpStatus status, String code) { throw new ResponseStatusException(status, code); }
}
