package app.fuelog.repository;

import app.fuelog.common.TimeUtils;
import app.fuelog.entity.PairingRequest;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/** 설치·연결 요청을 저장한다. DATETIME은 UTC로 변환하며 연결 변경은 설치 잠금 아래 수행한다. */
@Repository
public class PairingRepository {
    private final JdbcTemplate db;
    private final RowMapper<PairingRequest> mapper =
            (rs, n) ->
                    new PairingRequest(
                            UUID.fromString(rs.getString("id")),
                            UUID.fromString(rs.getString("installation_id")),
                            rs.getBytes("proof_hash"),
                            rs.getString("status"),
                            utc(rs.getObject("expires_at", LocalDateTime.class)),
                            uuid(rs.getString("selected_vehicle_id")),
                            rs.getString("new_vehicle_name"),
                            uuid(rs.getString("approved_user_id")),
                            rs.getString("issue_key"),
                            rs.getString("intent_key"),
                            rs.getString("confirmation_code"));

    public PairingRepository(JdbcTemplate db) {
        this.db = db;
    }

    public void insertInstallation(UUID installation, byte[] credentialHash, Instant now) {
        db.update(
                "INSERT INTO vehicle_installations (id, credential_hash, created_at, updated_at)"
                        + " VALUES (?, ?, ?, ?)",
                installation.toString(),
                credentialHash,
                time(now),
                time(now));
    }

    public byte[] credentialHash(UUID installation) {
        List<byte[]> rows =
                db.query(
                        "SELECT credential_hash FROM vehicle_installations WHERE id=?",
                        (rs, n) -> rs.getBytes(1),
                        installation.toString());
        return rows.isEmpty() ? null : rows.getFirst();
    }

    public UUID installationByCredentialHash(byte[] hash) {
        List<String> rows =
                db.query(
                        "SELECT id FROM vehicle_installations WHERE credential_hash=?",
                        (rs, n) -> rs.getString(1),
                        hash);
        return rows.isEmpty() ? null : UUID.fromString(rows.getFirst());
    }

    public UUID ensureInstallation(byte[] hash, Instant now) {
        db.update(
                "INSERT INTO vehicle_installations (id, credential_hash, created_at, updated_at)"
                        + " VALUES (?, ?, ?, ?) ON DUPLICATE KEY UPDATE id=id",
                UUID.randomUUID().toString(),
                hash,
                time(now),
                time(now));
        List<String> rows =
                db.query(
                        "SELECT id FROM vehicle_installations WHERE credential_hash=? FOR UPDATE",
                        (rs, n) -> rs.getString(1),
                        hash);
        if (rows.isEmpty())
            throw new IllegalStateException("Installation upsert did not return a row");
        return UUID.fromString(rows.getFirst());
    }

    public PairingRequest requestForIssueKey(UUID installation, String key) {
        List<PairingRequest> rows =
                db.query(
                        "SELECT * FROM pairing_requests WHERE installation_id=? AND issue_key=?",
                        mapper,
                        installation.toString(),
                        key);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    public void lockInstallation(UUID installation) {
        if (db.query(
                        "SELECT id FROM vehicle_installations WHERE id=? FOR UPDATE",
                        (rs, n) -> rs.getString(1),
                        installation.toString())
                .isEmpty())
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Installation not found");
    }

    public UUID linkedVehicle(UUID installation) {
        List<String> rows =
                db.query(
                        "SELECT vehicle_id FROM vehicle_installations WHERE id=?",
                        (rs, n) -> rs.getString(1),
                        installation.toString());
        return rows.isEmpty() ? null : uuid(rows.getFirst());
    }

    public boolean issueKeyExists(UUID installation, String key) {
        return !db.query(
                        "SELECT id FROM pairing_requests WHERE installation_id=? AND issue_key=?",
                        (rs, n) -> rs.getString(1),
                        installation.toString(),
                        key)
                .isEmpty();
    }

    public void expireForInstallation(UUID installation, Instant now) {
        db.update(
                "UPDATE pairing_requests SET status='expired', active_installation_id=NULL,"
                        + " proof_hash=NULL, updated_at=? WHERE installation_id=? AND status IN"
                        + " ('pending','awaiting_vehicle_confirmation') AND expires_at<=?",
                time(now),
                installation.toString(),
                time(now));
    }

    public void revokeActive(UUID installation, Instant now) {
        db.update(
                "UPDATE pairing_requests SET status='revoked', active_installation_id=NULL,"
                        + " proof_hash=NULL, updated_at=? WHERE installation_id=? AND status IN"
                        + " ('pending','awaiting_vehicle_confirmation')",
                time(now),
                installation.toString());
    }

    public void insertRequest(
            UUID id, UUID installation, byte[] hash, Instant expires, String key, Instant now) {
        db.update(
                "INSERT INTO pairing_requests (id, installation_id, active_installation_id,"
                    + " proof_hash, status, expires_at, issue_key, created_at, updated_at) VALUES"
                    + " (?,?,?,?,'pending',?,?,?,?)",
                id.toString(),
                installation.toString(),
                installation.toString(),
                hash,
                time(expires),
                key,
                time(now),
                time(now));
    }

    public PairingRequest current(UUID installation) {
        List<PairingRequest> rows =
                db.query(
                        "SELECT * FROM pairing_requests WHERE installation_id=? ORDER BY CASE WHEN"
                            + " status IN ('pending','awaiting_vehicle_confirmation') THEN 0 ELSE 1"
                            + " END, created_at DESC, id DESC LIMIT 1",
                        mapper,
                        installation.toString());
        if (rows.isEmpty())
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "No pairing request");
        return rows.getFirst();
    }

    public PairingRequest get(UUID id, boolean lock) {
        List<PairingRequest> rows =
                db.query(
                        "SELECT * FROM pairing_requests WHERE id=?" + (lock ? " FOR UPDATE" : ""),
                        mapper,
                        id.toString());
        if (rows.isEmpty())
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Pairing request not found");
        return rows.getFirst();
    }

    public void selectIntent(
            UUID id,
            UUID user,
            UUID vehicle,
            String newName,
            String key,
            String code,
            Instant now) {
        db.update(
                "UPDATE pairing_requests SET status='awaiting_vehicle_confirmation',"
                    + " proof_hash=NULL, selected_vehicle_id=?, new_vehicle_name=?,"
                    + " approved_user_id=?, intent_key=?, confirmation_code=?, updated_at=? WHERE"
                    + " id=? AND status='pending'",
                vehicle == null ? null : vehicle.toString(),
                newName,
                user.toString(),
                key,
                code,
                time(now),
                id.toString());
    }

    public void setRequestedVehicleName(UUID request, String name) {
        db.update("UPDATE pairing_requests SET new_vehicle_name=? WHERE id=?", name, request.toString());
    }

    public boolean userManagesVehicle(UUID user, UUID vehicle) {
        return !db.query(
                        "SELECT user_id FROM group_members WHERE vehicle_id=? AND user_id=? AND"
                                + " role IN ('OWNER','MANAGER')",
                        (rs, n) -> rs.getString(1),
                        vehicle.toString(),
                        user.toString())
                .isEmpty();
    }

    public boolean isGroupMember(UUID user, UUID vehicle) {
        return !db.query(
                        "SELECT user_id FROM group_members WHERE vehicle_id=? AND user_id=?",
                        (rs, n) -> rs.getString(1),
                        vehicle.toString(),
                        user.toString())
                .isEmpty();
    }

    public void addMember(UUID vehicle, UUID user, String role, Instant now) {
        db.update(
                "INSERT INTO group_members (vehicle_id, user_id, role, created_at) VALUES (?,?,?,?)",
                vehicle.toString(),
                user.toString(),
                role,
                time(now));
    }

    public void promoteToManager(UUID user, UUID vehicle) {
        db.update(
                "UPDATE group_members SET role='MANAGER' WHERE vehicle_id=? AND user_id=?",
                vehicle.toString(),
                user.toString());
    }

    public String vehicleName(UUID vehicle) {
        List<String> rows =
                db.query(
                        "SELECT name FROM vehicles WHERE id=?",
                        (rs, n) -> rs.getString(1),
                        vehicle.toString());
        return rows.isEmpty() ? null : rows.getFirst();
    }

    /** 현재 사용자·차량에 완료된 연결의 모바일 입력 이름만 반환한다. 승인 대기·이전 사용자는 제외한다. */
    public String connectionAlias(UUID installation, UUID vehicle, UUID user) {
        var rows = db.query(
                "SELECT new_vehicle_name FROM pairing_requests WHERE installation_id=?"
                        + " AND selected_vehicle_id=? AND approved_user_id=? AND status='completed'"
                        + " ORDER BY updated_at DESC,id DESC LIMIT 1",
                (rs, n) -> rs.getString(1), installation.toString(), vehicle.toString(), user.toString());
        String alias = rows.isEmpty() ? null : rows.getFirst();
        return alias == null || alias.isBlank() ? null : alias.trim();
    }

    public void createVehicle(UUID vehicle, UUID request, UUID user, String name, Instant now) {
        db.update(
                "INSERT INTO vehicles (id, name, created_by, source_request_id, created_at,"
                        + " updated_at) VALUES (?,?,?,?,?,?)",
                vehicle.toString(),
                name,
                user.toString(),
                request.toString(),
                time(now),
                time(now));
        db.update(
                "INSERT INTO group_members (vehicle_id, user_id, role, created_at) VALUES"
                        + " (?,?,'OWNER',?)",
                vehicle.toString(),
                user.toString(),
                time(now));
    }

    public boolean linkInstallation(UUID installation, UUID vehicle, Instant now) {
        return db.update(
                        "UPDATE vehicle_installations SET vehicle_id=?,personal_access_blocked=FALSE,access_blocked_at=NULL,"
                                + " registration_version=registration_version+1, updated_at=? WHERE"
                                + " id=? AND vehicle_id IS NULL",
                        vehicle.toString(),
                        time(now),
                        installation.toString())
                == 1;
    }

    public void resumeInstallation(UUID installation, Instant now) {
        db.update("UPDATE vehicle_installations SET personal_access_blocked=FALSE,access_blocked_at=NULL,updated_at=? WHERE id=?",
                time(now), installation.toString());
    }

    public void complete(UUID request, UUID vehicle, Instant now) {
        db.update(
                "UPDATE pairing_requests SET status='completed', active_installation_id=NULL,"
                        + " selected_vehicle_id=?, proof_hash=NULL, updated_at=? WHERE id=?",
                vehicle.toString(),
                time(now),
                request.toString());
    }

    public void completeDirect(UUID request, UUID vehicle, UUID user, String key, Instant now) {
        db.update(
                "UPDATE pairing_requests SET status='completed', active_installation_id=NULL,"
                    + " selected_vehicle_id=?, approved_user_id=?, intent_key=?, proof_hash=NULL,"
                    + " updated_at=? WHERE id=? AND status='pending'",
                vehicle.toString(),
                user.toString(),
                key,
                time(now),
                request.toString());
    }

    public void unlinkInstallation(UUID installation, Instant now) {
        db.update(
                "UPDATE vehicle_installations SET vehicle_id=NULL,"
                        + " registration_version=registration_version+1, updated_at=? WHERE id=?",
                time(now),
                installation.toString());
    }

    public void revokeForReset(UUID installation, Instant now) {
        db.update(
                "UPDATE pairing_requests SET status='revoked', active_installation_id=NULL,"
                        + " proof_hash=NULL, updated_at=? WHERE installation_id=? AND status IN"
                        + " ('pending','awaiting_vehicle_confirmation','completed')",
                time(now),
                installation.toString());
    }

    public void expireOne(UUID id, Instant now) {
        db.update(
                "UPDATE pairing_requests SET status='expired', active_installation_id=NULL,"
                        + " proof_hash=NULL, updated_at=? WHERE id=? AND status IN"
                        + " ('pending','awaiting_vehicle_confirmation')",
                time(now),
                id.toString());
    }

    public void cancel(UUID id, Instant now) {
        db.update(
                "UPDATE pairing_requests SET status='revoked', active_installation_id=NULL,"
                        + " proof_hash=NULL, updated_at=? WHERE id=? AND status IN"
                        + " ('pending','awaiting_vehicle_confirmation')",
                time(now),
                id.toString());
    }

    private static UUID uuid(String value) {
        return value == null ? null : UUID.fromString(value);
    }

    private static LocalDateTime time(Instant instant) {
        return TimeUtils.toLocalDateTime(instant);
    }

    private static Instant utc(LocalDateTime value) {
        return TimeUtils.toInstant(value);
    }
}
