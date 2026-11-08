package app.fuelog.repository;

import app.fuelog.common.TimeUtils;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/** 운행 요약 revision과 모바일 확인함 스냅샷을 저장한다. 시각은 UTC다. */
@Repository
public class TripSummaryRepository {
    private final JdbcTemplate db;

    public TripSummaryRepository(JdbcTemplate db) {
        this.db = db;
    }

    public record Trip(
            long id,
            UUID installation,
            UUID trip,
            UUID vehicle,
            UUID user,
            UUID consent,
            long revision,
            byte[] hash,
            String payload,
            Instant syncedAt) {}

    public record Item(
            long id,
            UUID recipient,
            UUID trip,
            long summaryId,
            long revision,
            String snapshot,
            Instant deliveredAt,
            Instant readAt,
            String kind,
            UUID candidateId,
            Long candidateRevision) {}

    private final RowMapper<Trip> tripMapper =
            (r, n) ->
                    new Trip(
                            r.getLong("id"),
                            id(r.getString("installation_id")),
                            id(r.getString("trip_id")),
                            id(r.getString("vehicle_id")),
                            id(r.getString("user_id")),
                            id(r.getString("consent_id")),
                            r.getLong("revision"),
                            r.getBytes("payload_hash"),
                            r.getString("payload"),
                            instant(r.getObject("synced_at", LocalDateTime.class)));
    private final RowMapper<Item> itemMapper =
            (r, n) ->
                    new Item(
                            r.getLong("id"),
                            id(r.getString("recipient_id")),
                            id(r.getString("trip_id")),
                            r.getLong("summary_id"),
                            r.getLong("trip_revision"),
                            r.getString("snapshot"),
                            instant(r.getObject("delivered_at", LocalDateTime.class)),
                            instant(r.getObject("read_at", LocalDateTime.class)),
                            r.getString("kind"),
                            id(r.getString("candidate_id")),
                            r.getObject("candidate_revision", Long.class));

    public Trip trip(UUID installation, UUID trip) {
        var rows =
                db.query(
                        "SELECT * FROM trip_summaries WHERE installation_id=? AND trip_id=? FOR"
                                + " UPDATE",
                        tripMapper,
                        installation.toString(),
                        trip.toString());
        return rows.isEmpty() ? null : rows.getFirst();
    }

    public void save(
            UUID installation,
            UUID trip,
            UUID vehicle,
            UUID user,
            UUID consent,
            long revision,
            byte[] hash,
            String payload,
            Instant now,
            boolean insert) {
        if (insert)
            db.update(
                    "INSERT INTO trip_summaries"
                        + " (installation_id,trip_id,vehicle_id,user_id,consent_id,revision,payload_hash,payload,synced_at)"
                        + " VALUES (?,?,?,?,?,?,?,?,?)",
                    installation.toString(),
                    trip.toString(),
                    vehicle.toString(),
                    string(user),
                    string(consent),
                    revision,
                    hash,
                    payload,
                    time(now));
        else
            db.update(
                    "UPDATE trip_summaries SET revision=?,payload_hash=?,payload=?,synced_at=?"
                            + " WHERE installation_id=? AND trip_id=? AND revision<?",
                    revision,
                    hash,
                    payload,
                    time(now),
                    installation.toString(),
                    trip.toString(),
                    revision);
    }

    public Item item(UUID recipient, UUID trip) {
        var rows =
                db.query(
                        "SELECT b.*,NULL AS candidate_revision FROM mobile_trip_inbox b WHERE"
                                + " recipient_id=? AND trip_id=? FOR UPDATE",
                        itemMapper,
                        recipient.toString(),
                        trip.toString());
        return rows.isEmpty() ? null : rows.getFirst();
    }

    public void deliver(Trip trip, String snapshot, Instant now, Item prior) {
        if (prior == null)
            db.update(
                    "INSERT INTO mobile_trip_inbox"
                        + " (recipient_id,trip_id,summary_id,trip_revision,snapshot,delivered_at)"
                        + " VALUES (?,?,?,?,?,?)",
                    trip.user().toString(),
                    trip.trip().toString(),
                    trip.id(),
                    trip.revision(),
                    snapshot,
                    time(now));
        else
            db.update(
                    "UPDATE mobile_trip_inbox SET trip_revision=?,snapshot=?,read_at=NULL WHERE"
                            + " id=?",
                    trip.revision(),
                    snapshot,
                    prior.id());
    }

    public List<Item> inbox(UUID user, long cursor) {
        return db.query(
                "SELECT b.*,f.revision AS candidate_revision FROM mobile_trip_inbox b LEFT JOIN"
                        + " trip_summaries t ON t.id=b.summary_id LEFT JOIN fuel_candidates f ON"
                        + " f.id=b.candidate_id JOIN group_members m ON"
                        + " m.vehicle_id=COALESCE(t.vehicle_id,f.vehicle_id) AND"
                        + " m.user_id=b.recipient_id WHERE b.recipient_id=? AND"
                        + " COALESCE(t.user_id,f.owner_id)=b.recipient_id AND b.id>? ORDER BY b.id"
                        + " LIMIT 51",
                itemMapper,
                user.toString(),
                cursor);
    }

    public Item readable(UUID user, long id) {
        var rows =
                db.query(
                        "SELECT b.*,f.revision AS candidate_revision FROM mobile_trip_inbox b LEFT"
                            + " JOIN trip_summaries t ON t.id=b.summary_id LEFT JOIN"
                            + " fuel_candidates f ON f.id=b.candidate_id JOIN group_members m ON"
                            + " m.vehicle_id=COALESCE(t.vehicle_id,f.vehicle_id) AND"
                            + " m.user_id=b.recipient_id WHERE b.id=? AND b.recipient_id=? AND"
                            + " COALESCE(t.user_id,f.owner_id)=b.recipient_id FOR UPDATE",
                        itemMapper,
                        id,
                        user.toString());
        return rows.isEmpty() ? null : rows.getFirst();
    }
    public void read(long id, Instant at) {
        db.update(
                "UPDATE mobile_trip_inbox SET read_at=COALESCE(read_at,?) WHERE id=?",
                time(at),
                id);
    }

    private static UUID id(String s) {
        return s == null ? null : UUID.fromString(s);
    }

    private static String string(UUID id) {
        return id == null ? null : id.toString();
    }

    private static LocalDateTime time(Instant at) {
        return TimeUtils.toLocalDateTime(at);
    }

    private static Instant instant(LocalDateTime at) {
        return at == null ? null : TimeUtils.toInstant(at);
    }
}
