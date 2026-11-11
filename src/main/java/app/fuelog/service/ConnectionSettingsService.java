package app.fuelog.service;

import app.fuelog.dto.ConnectionSettingsDtos.*;
import app.fuelog.repository.PairingRepository;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** 장치 설정 조회와 사용자 해제·관리자 초기화를 처리하고 기록 소유권을 보존한다. */
@Service
public class ConnectionSettingsService {
    private static final long FRESHNESS_MILLIS = 60_000;
    private final JdbcTemplate db;
    private final InstallationAuth auth;
    private final PairingRepository pairing;
    private final VehicleHomeService home;
    private final InstallationAccess access;
    private final PairingActor actor;
    private final JwtDecoder decoder;

    public ConnectionSettingsService(
            JdbcTemplate db,
            InstallationAuth auth,
            PairingRepository pairing,
            VehicleHomeService home,
            InstallationAccess access,
            PairingActor actor,
            JwtDecoder decoder) {
        this.db = db;
        this.auth = auth;
        this.pairing = pairing;
        this.home = home;
        this.access = access;
        this.actor = actor;
        this.decoder = decoder;
    }

    private record Operation(
            UUID id,
            UUID installation,
            String operationId,
            String kind,
            String status,
            UUID vehicle,
            long registration) {}

    private Operation operation(UUID installation, String key) {
        var rows =
                db.query(
                        "SELECT * FROM connection_operations WHERE installation_id=? AND"
                                + " operation_id=? FOR UPDATE",
                        (r, n) ->
                                new Operation(
                                        UUID.fromString(r.getString("id")),
                                        installation,
                                        key,
                                        r.getString("kind"),
                                        r.getString("status"),
                                        uuid(r.getString("vehicle_id")),
                                        r.getLong("registration_version")),
                        installation.toString(),
                        key);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    @Transactional
    public Settings settings(String bearer, Envelope body) {
        UUID installation = installation(bearer);
        UUID user = access.currentUser(installation);
        var sync = syncState(installation, user);
        var recommendation = recommendation(installation, user);
        String alias =
                db.queryForObject(
                        "SELECT device_alias FROM vehicle_installations WHERE id=?",
                        String.class,
                        installation.toString());
        return new Settings(
                alias,
                sync.status(),
                sync.lastAcknowledgedAt(),
                features(installation),
                recommendation.registered(),
                recommendation.consented(),
                recommendation.verified(),
                recommendation.enabled());
    }

    @Transactional
    public OperationResult disconnect(String bearer, Disconnect b) {
        return disconnect(bearer, null, b);
    }

    @Transactional
    public OperationResult disconnect(String bearer, Jwt jwt, Disconnect b) {
        UUID installation = resolveInstallation(bearer, jwt);
        return disconnectInstallation(installation, b);
    }

    private OperationResult disconnectInstallation(UUID installation, Disconnect b) {
        Operation prior = operation(installation, b.operationId());
        if (prior != null) {
            if (!prior.kind().equals(b.kind().name()))
                fail(HttpStatus.CONFLICT, "OPERATION_ID_REUSED");
            return result(prior);
        }
        UUID vehicle = pairing.linkedVehicle(installation);
        if (b.kind() == Kind.VEHICLE && vehicle == null)
            fail(HttpStatus.CONFLICT, "INSTALLATION_NOT_LINKED");
        UUID user = access.currentUser(installation);
        long registration = access.registrationVersion(installation);
        long session =
                db.queryForObject(
                        "SELECT session_version FROM vehicle_installations WHERE id=?",
                        Long.class,
                        installation.toString());
        String state = b.kind() == Kind.USER ? "COMPLETED" : "AWAITING_ADMIN";
        db.update(
                "INSERT INTO connection_operations"
                    + " (id,installation_id,operation_id,kind,status,vehicle_id,user_id,registration_version,session_version,created_at,completed_at)"
                    + " VALUES (?,?,?,?,?,?,?,?,?,?,?)",
                UUID.randomUUID().toString(),
                installation.toString(),
                b.operationId(),
                b.kind().name(),
                state,
                string(vehicle),
                string(user),
                registration,
                session,
                now(),
                b.kind() == Kind.USER ? now() : null);
        // 관리자 승인 대기 중에도 개인 권한은 즉시 폐기한다. 설치 잠금으로 사용자 확인·기록 시작과 직렬화한다.
        revokeAndPreserve(installation);
        if (b.kind() == Kind.USER) {
            pairing.revokeForReset(installation, Instant.now());
            // Retain the physical vehicle so shared maintenance remains readable without a user.
        }
        return new OperationResult(b.operationId(), state);
    }

    @Transactional(readOnly = true)
    public java.util.Map<String, Object> mobileConnection(Jwt jwt, UUID vehicle) {
        UUID user = actor.requireUser(jwt);
        var installations = mobileInstallations(user, vehicle);
        boolean paired = installations.stream().anyMatch(id -> user.equals(access.currentUser(id)));
        return java.util.Map.of("paired", paired, "vehicleName", db.queryForObject("SELECT name FROM vehicles WHERE id=?", String.class, vehicle.toString()));
    }

    @Transactional
    public OperationResult disconnectMobile(Jwt jwt, UUID vehicle, Disconnect body) {
        UUID user = actor.requireUser(jwt);
        if (body.kind() != Kind.USER) fail(HttpStatus.BAD_REQUEST, "USER_DISCONNECT_ONLY");
        for (UUID id : mobileInstallations(user, vehicle)) {
            pairing.lockInstallation(id);
            Integer prior = db.queryForObject("SELECT COUNT(*) FROM connection_operations WHERE installation_id=? AND user_id=? AND operation_id=?",
                    Integer.class, id.toString(), user.toString(), body.operationId());
            if (user.equals(access.currentUser(id)) || prior != null && prior > 0)
                return disconnectInstallation(id, body);
        }
        fail(HttpStatus.CONFLICT, "CONNECTION_ALREADY_DISCONNECTED");
        return null;
    }

    @Transactional
    public Map<String, String> renameInstallationVehicle(String bearer, String name) {
        UUID installation = installation(bearer);
        UUID vehicle = pairing.linkedVehicle(installation);
        UUID user = access.currentUser(installation);
        if (vehicle == null || user == null) fail(HttpStatus.FORBIDDEN, "VERIFIED_OWNER_REQUIRED");
        return renameVehicle(user, vehicle, name);
    }

    @Transactional
    public Map<String, String> renameMobileVehicle(Jwt jwt, UUID vehicle, String name) {
        return renameVehicle(actor.requireUser(jwt), vehicle, name);
    }

    private Map<String, String> renameVehicle(UUID user, UUID vehicle, String name) {
        if (name == null || name.isBlank() || name.trim().length() > 100)
            fail(HttpStatus.BAD_REQUEST, "INVALID_VEHICLE_NAME");
        Integer owner = db.queryForObject("SELECT COUNT(*) FROM group_members WHERE vehicle_id=? AND user_id=? AND role='OWNER'",
                Integer.class, vehicle.toString(), user.toString());
        if (owner == null || owner == 0) fail(HttpStatus.FORBIDDEN, "OWNER_REQUIRED");
        String trimmed = name.trim();
        db.update("UPDATE vehicles SET name=?,updated_at=? WHERE id=?", trimmed, now(), vehicle.toString());
        return Map.of("vehicle_name", trimmed);
    }

    private List<UUID> mobileInstallations(UUID user, UUID vehicle) {
        Integer members = db.queryForObject("SELECT COUNT(*) FROM group_members WHERE vehicle_id=? AND user_id=?",
                Integer.class, vehicle.toString(), user.toString());
        if (members == null || members == 0) fail(HttpStatus.FORBIDDEN, "VEHICLE_ACCESS_DENIED");
        return db.query("SELECT id FROM vehicle_installations WHERE vehicle_id=?",
                (rs, n) -> UUID.fromString(rs.getString(1)), vehicle.toString());
    }

    private UUID resolveInstallation(String bearer, Jwt jwt) {
        if (bearer != null && bearer.startsWith("Bearer ")) {
            String encoded = bearer.substring(7);
            if (encoded.matches("[A-Za-z0-9_-]{43,}")) {
                try {
                    return installation(bearer);
                } catch (ResponseStatusException ex) {
                    if (jwt == null) throw ex;
                }
            }
        }
        if (jwt != null) {
            UUID user = actor.requireUser(jwt);
            List<String> list = db.query(
                    "SELECT i.id FROM vehicle_installations i JOIN group_members m ON m.vehicle_id = i.vehicle_id AND m.user_id = ? ORDER BY i.updated_at DESC LIMIT 1",
                    (rs, n) -> rs.getString(1),
                    user.toString());
            if (!list.isEmpty()) {
                UUID id = UUID.fromString(list.getFirst());
                pairing.lockInstallation(id);
                return id;
            }
            fail(HttpStatus.NOT_FOUND, "INSTALLATION_NOT_FOUND");
        }
        fail(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED");
        return null;
    }

    @Transactional(readOnly = true)
    public Operations pending(UUID user) {
        var rows =
                db.query(
                        "SELECT o.* FROM connection_operations o JOIN vehicle_installations i ON"
                                + " i.id=o.installation_id AND"
                                + " i.registration_version=o.registration_version AND"
                                + " i.vehicle_id=o.vehicle_id JOIN group_members m ON"
                                + " m.vehicle_id=o.vehicle_id AND m.user_id=? AND m.role IN"
                                + " ('OWNER','MANAGER') WHERE o.kind='VEHICLE' AND"
                                + " o.status='AWAITING_ADMIN' ORDER BY o.created_at,o.id LIMIT 100",
                        (r, n) ->
                                new PendingOperation(
                                        UUID.fromString(r.getString("id")),
                                        r.getString("operation_id"),
                                        UUID.fromString(r.getString("installation_id")),
                                        UUID.fromString(r.getString("vehicle_id")),
                                        r.getLong("registration_version"),
                                        r.getString("status"),
                                        r.getObject("created_at", LocalDateTime.class)
                                                .toInstant(ZoneOffset.UTC)
                                                .toEpochMilli()),
                        user.toString());
        return new Operations(rows);
    }

    @Transactional
    public OperationResult approve(Jwt jwt, String key, Approve b) {
        UUID user = actor.requireUser(jwt);
        if (!"RETAIN_ORIGINAL_OWNER".equals(b.pendingRecordsPolicy()))
            fail(HttpStatus.BAD_REQUEST, "RECORDS_POLICY_REQUIRED");
        pairing.lockInstallation(b.installationId());
        Operation op = requireOperation(b.installationId(), key);
        manager(user, op.vehicle());
        requireVersion(op, b.registrationVersion());
        if ("COMPLETED".equals(op.status())) return result(op);
        if (!"AWAITING_ADMIN".equals(op.status()))
            fail(HttpStatus.CONFLICT, "OPERATION_ALREADY_DECIDED");
        requireCurrentRegistration(op);
        consumeReauthentication(user, op, b.reauthenticationProof());
        revokeAndPreserve(op.installation());
        pairing.revokeForReset(op.installation(), Instant.now());
        pairing.unlinkInstallation(op.installation(), Instant.now());
        db.update(
                "UPDATE connection_operations SET"
                    + " status='COMPLETED',decision_user_id=?,pending_records_policy=?,completed_at=?"
                    + " WHERE id=?",
                user.toString(),
                b.pendingRecordsPolicy(),
                now(),
                op.id().toString());
        return new OperationResult(key, "COMPLETED");
    }

    @Transactional
    public OperationResult reject(UUID user, String key, Reject b) {
        pairing.lockInstallation(b.installationId());
        Operation op = requireOperation(b.installationId(), key);
        manager(user, op.vehicle());
        requireVersion(op, b.registrationVersion());
        if ("REJECTED".equals(op.status())) return result(op);
        if (!"AWAITING_ADMIN".equals(op.status()))
            fail(HttpStatus.CONFLICT, "OPERATION_ALREADY_DECIDED");
        requireCurrentRegistration(op);
        db.update(
                "UPDATE connection_operations SET"
                        + " status='REJECTED',decision_user_id=?,completed_at=? WHERE id=?",
                user.toString(),
                now(),
                op.id().toString());
        return new OperationResult(key, "REJECTED");
    }

    @Transactional
    public Features capabilities(String bearer, Capabilities b) {
        UUID installation = installation(bearer);
        if (b.observedAt() > System.currentTimeMillis() + 1000)
            fail(HttpStatus.BAD_REQUEST, "INVALID_OBSERVED_AT");
        long version = access.registrationVersion(installation);
        var previous =
                db.query(
                        "SELECT observed_at FROM installation_capabilities WHERE installation_id=?"
                                + " AND registration_version=?",
                        (r, n) -> r.getLong(1),
                        installation.toString(),
                        version);
        if (!previous.isEmpty() && previous.getFirst() >= b.observedAt())
            return features(installation);
        db.update(
                "DELETE FROM installation_capabilities WHERE installation_id=?",
                installation.toString());
        db.update(
                "INSERT INTO installation_capabilities"
                    + " (installation_id,registration_version,observed_at,fuel,distance,location)"
                    + " VALUES (?,?,?,?,?,?)",
                installation.toString(),
                version,
                b.observedAt(),
                b.features().fuel().name(),
                b.features().distance().name(),
                b.features().location().name());
        return features(installation);
    }

    @Transactional
    public RecommendationResult setRecommendation(UUID user, UUID vehicle, Recommendation b) {
        var ids =
                db.query(
                        "SELECT id FROM vehicle_installations WHERE vehicle_id=?",
                        (r, n) -> UUID.fromString(r.getString(1)),
                        vehicle.toString());
        if (ids.isEmpty()) fail(HttpStatus.CONFLICT, "INSTALLATION_NOT_LINKED");
        UUID installation = ids.getFirst();
        pairing.lockInstallation(installation);
        if (!vehicle.equals(pairing.linkedVehicle(installation)))
            fail(HttpStatus.CONFLICT, "REGISTRATION_CHANGED");
        member(user, vehicle);
        long version = access.registrationVersion(installation);
        boolean enabled = Boolean.TRUE.equals(b.enabled());
        if (enabled && !b.consented())
            fail(HttpStatus.BAD_REQUEST, "RECOMMENDATION_CONSENT_REQUIRED");
        if (b.consented() || enabled) {
            var state = recommendation(installation, user);
            if (!state.registered() || !state.verified())
                fail(HttpStatus.CONFLICT, "RECOMMENDATION_NOT_VERIFIED");
            if (!user.equals(access.currentUser(installation)))
                fail(HttpStatus.FORBIDDEN, "CURRENT_USER_VERIFICATION_REQUIRED");
        }
        db.update(
                "DELETE FROM recommendation_consents WHERE installation_id=? AND"
                        + " registration_version=? AND user_id=?",
                installation.toString(),
                version,
                user.toString());
        db.update(
                "INSERT INTO recommendation_consents"
                    + " (installation_id,registration_version,user_id,consented,enabled,updated_at)"
                    + " VALUES (?,?,?,?,?,?)",
                installation.toString(),
                version,
                user.toString(),
                b.consented(),
                enabled,
                now());
        return recommendation(installation, user);
    }

    @Transactional
    public SyncStatus syncStatus(String bearer) {
        UUID installation = installation(bearer);
        return syncState(installation, access.currentUser(installation));
    }

    private SyncStatus syncState(UUID installation, UUID user) {
        if (user == null) return new SyncStatus("UNKNOWN", null, List.of());
        var rows =
                db.query(
                        "SELECT * FROM installation_sync_checkpoints WHERE installation_id=? AND"
                                + " registration_version=? AND user_id=?",
                        (r, n) -> {
                            Long pending = r.getObject("pending_count", Long.class),
                                    verified = r.getObject("verified_at", Long.class),
                                    ack = r.getObject("last_acknowledged_at", Long.class);
                            boolean fresh =
                                    verified != null
                                            && verified <= System.currentTimeMillis()
                                            && verified
                                                    >= System.currentTimeMillis()
                                                            - FRESHNESS_MILLIS;
                            // 큐가 비었다는 보고만으로는 완료가 아니다. 최근 서버 검증과 수신 확인이 모두 필요하다.
                            String state = "UNKNOWN";
                            if (pending != null && pending > 0) {
                                state = "PENDING";
                            } else if (fresh
                                    && pending != null
                                    && pending == 0
                                    && ack != null
                                    && ack <= verified) {
                                state = "SYNCED";
                            }
                            return new SyncStatus(
                                    state, ack, List.of(new OwnerSync(user, state, pending, ack)));
                        },
                        installation.toString(),
                        access.registrationVersion(installation),
                        user.toString());
        return rows.isEmpty()
                ? new SyncStatus(
                        "UNKNOWN", null, List.of(new OwnerSync(user, "UNKNOWN", null, null)))
                : rows.getFirst();
    }

    private Features features(UUID installation) {
        var rows =
                db.query(
                        "SELECT * FROM installation_capabilities WHERE installation_id=? AND"
                                + " registration_version=?",
                        (r, n) -> {
                            long observed = r.getLong("observed_at");
                            if (observed > System.currentTimeMillis()
                                    || observed < System.currentTimeMillis() - FRESHNESS_MILLIS)
                                return unknownFeatures();
                            return new Features(
                                    Feature.valueOf(r.getString("fuel")),
                                    Feature.valueOf(r.getString("distance")),
                                    Feature.valueOf(r.getString("location")));
                        },
                        installation.toString(),
                        access.registrationVersion(installation));
        return rows.isEmpty() ? unknownFeatures() : rows.getFirst();
    }

    private RecommendationResult recommendation(UUID installation, UUID user) {
        long version = access.registrationVersion(installation);
        var registrations =
                db.query(
                        "SELECT * FROM recommendation_registrations WHERE installation_id=? AND"
                                + " registration_version=?",
                        (r, n) ->
                                new boolean[] {
                                    r.getBoolean("registered"),
                                    r.getBoolean("verified")
                                            && r.getObject("verified_at") != null
                                            && r.getString("verification_source") != null
                                            && !r.getString("verification_source").isBlank()
                                },
                        installation.toString(),
                        version);
        boolean registered =
                pairing.linkedVehicle(installation) != null
                        && !registrations.isEmpty()
                        && registrations.getFirst()[0];
        boolean verified = registered && registrations.getFirst()[1];
        var consents =
                user == null
                        ? List.<boolean[]>of()
                        : db.query(
                                "SELECT consented,enabled FROM recommendation_consents WHERE"
                                    + " installation_id=? AND registration_version=? AND user_id=?",
                                (r, n) -> new boolean[] {r.getBoolean(1), r.getBoolean(2)},
                                installation.toString(),
                                version,
                                user.toString());
        boolean consented = !consents.isEmpty() && consents.getFirst()[0];
        boolean enabled =
                registered
                        && verified
                        && consented
                        && consents.getFirst()[1]
                        && user != null
                        && user.equals(access.currentUser(installation));
        return new RecommendationResult(registered, consented, verified, enabled);
    }

    private void revokeAndPreserve(UUID installation) {
        access.block(installation);
        home.revokeForInstallationReset(installation);
        // 기존 소유자·샘플을 보존하고 기록 중인 구간만 해제 경계에서 종료한다.
        db.update(
                "UPDATE recording_intervals SET"
                    + " status='finished',ended_at=GREATEST(started_at,COALESCE(last_sample_at,started_at),?),finish_operation_id=CONCAT('disconnect:',id),finish_last_sequence=last_accepted_sequence,updated_at=?"
                    + " WHERE installation_id=? AND status='recording'",
                now(),
                now(),
                installation.toString());
    }

    private void consumeReauthentication(UUID user, Operation op, String encoded) {
        Jwt proof;
        try {
            proof = decoder.decode(encoded);
        } catch (JwtException ex) {
            fail(HttpStatus.UNAUTHORIZED, "INVALID_REAUTH_PROOF");
            return;
        }
        if (!user.equals(actor.requireUser(proof)))
            fail(HttpStatus.FORBIDDEN, "REAUTH_ACTOR_MISMATCH");
        if (proof.getExpiresAt() == null || !Instant.now().isBefore(proof.getExpiresAt()))
            fail(HttpStatus.UNAUTHORIZED, "REAUTH_PROOF_EXPIRED");
        Object claim = proof.getClaim("amr");
        long timestamp = -1;
        String method = null;
        if (claim instanceof List<?> entries)
            for (Object entry : entries) {
                if (entry instanceof Map<?, ?> amr
                        && amr.get("timestamp") instanceof Number stamp
                        && ("otp".equals(amr.get("method")) || "totp".equals(amr.get("method")))) {
                    long value = stamp.longValue();
                    long now = Instant.now().getEpochSecond();
                    if (value <= now && value > now - 300 && value > timestamp) {
                        timestamp = value;
                        method = amr.get("method").toString();
                    }
                }
            }
        if (timestamp < 0) fail(HttpStatus.FORBIDDEN, "RECENT_REAUTH_REQUIRED");
        // JWT 갱신으로 같은 OTP를 재사용하지 못하도록 인증 사건 자체를 해시한다.
        byte[] fingerprint =
                InstallationAuth.sha256(
                        (user + ":" + method + ":" + timestamp).getBytes(StandardCharsets.UTF_8));
        var uses =
                db.query(
                        "SELECT operation_id FROM connection_reauth_uses WHERE proof_hash=? FOR"
                                + " UPDATE",
                        (r, n) -> r.getString(1),
                        fingerprint);
        if (!uses.isEmpty()) fail(HttpStatus.CONFLICT, "REAUTH_PROOF_ALREADY_USED");
        db.update(
                "INSERT INTO connection_reauth_uses"
                        + " (proof_hash,operation_id,actor_id,authenticated_at,used_at) VALUES"
                        + " (?,?,?,?,?)",
                fingerprint,
                op.id().toString(),
                user.toString(),
                LocalDateTime.ofInstant(Instant.ofEpochSecond(timestamp), ZoneOffset.UTC),
                now());
    }

    private Operation requireOperation(UUID installation, String key) {
        Operation op = operation(installation, key);
        if (op == null) fail(HttpStatus.NOT_FOUND, "CONNECTION_OPERATION_NOT_FOUND");
        if (!"VEHICLE".equals(op.kind())) fail(HttpStatus.CONFLICT, "VEHICLE_OPERATION_REQUIRED");
        return op;
    }

    private void requireVersion(Operation op, long expected) {
        if (op.registration() != expected)
            fail(HttpStatus.CONFLICT, "REGISTRATION_VERSION_MISMATCH");
    }

    private void requireCurrentRegistration(Operation op) {
        if (op.registration() != access.registrationVersion(op.installation())
                || !Objects.equals(op.vehicle(), pairing.linkedVehicle(op.installation())))
            fail(HttpStatus.CONFLICT, "REGISTRATION_CHANGED");
    }

    private void manager(UUID user, UUID vehicle) {
        if (!List.of("OWNER", "MANAGER").contains(member(user, vehicle)))
            fail(HttpStatus.FORBIDDEN, "VEHICLE_MANAGEMENT_REQUIRED");
    }

    private String member(UUID user, UUID vehicle) {
        if (vehicle == null) fail(HttpStatus.FORBIDDEN, "VEHICLE_MEMBERSHIP_REQUIRED");
        var roles =
                db.query(
                        "SELECT role FROM group_members WHERE vehicle_id=? AND user_id=? FOR"
                                + " UPDATE",
                        (r, n) -> r.getString(1),
                        vehicle.toString(),
                        user.toString());
        if (roles.isEmpty()) fail(HttpStatus.FORBIDDEN, "VEHICLE_MEMBERSHIP_REQUIRED");
        return roles.getFirst();
    }

    private UUID installation(String bearer) {
        UUID id = auth.require(bearer).id();
        pairing.lockInstallation(id);
        return id;
    }

    private static Features unknownFeatures() {
        return new Features(Feature.UNKNOWN, Feature.UNKNOWN, Feature.UNKNOWN);
    }

    private static OperationResult result(Operation op) {
        return new OperationResult(op.operationId(), op.status());
    }

    private static UUID uuid(String s) {
        return s == null ? null : UUID.fromString(s);
    }

    private static String string(UUID id) {
        return id == null ? null : id.toString();
    }

    private static LocalDateTime now() {
        return LocalDateTime.now(ZoneOffset.UTC);
    }

    private static void fail(HttpStatus status, String code) {
        throw new ResponseStatusException(status, code);
    }
}
