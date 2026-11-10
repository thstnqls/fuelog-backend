package app.fuelog.service;

import app.fuelog.dto.MobileVehicleDtos.VehicleItem;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.util.List;
import java.util.UUID;

@Service
public class MobileVehicleService {
    private final JdbcTemplate db;

    public MobileVehicleService(JdbcTemplate db) {
        this.db = db;
    }

    @Transactional(readOnly = true)
    public List<VehicleItem> listVehicles(UUID userId) {
        String sql = """
                SELECT
                    v.id,
                    v.name,
                    m.role,
                    v.created_at,
                    i.id AS installation_id
                FROM vehicles v
                JOIN group_members m ON v.id = m.vehicle_id
                LEFT JOIN vehicle_installations i ON v.id = i.vehicle_id
                WHERE m.user_id = ?
                ORDER BY v.created_at DESC
                """;

        return db.query(sql, (rs, rowNum) -> {
            UUID id = UUID.fromString(rs.getString("id"));
            String name = rs.getString("name");
            String role = rs.getString("role");
            Timestamp createdAtTs = rs.getTimestamp("created_at");
            String instIdStr = rs.getString("installation_id");
            UUID installationId = instIdStr != null ? UUID.fromString(instIdStr) : null;

            return VehicleItem.of(
                    id,
                    name,
                    role,
                    createdAtTs != null ? createdAtTs.toInstant() : null,
                    installationId
            );
        }, userId.toString());
    }
}
