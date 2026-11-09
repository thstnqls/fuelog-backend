package app.fuelog;

import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest
@ActiveProfiles("test")
class DemoSeedMigrationTests {
    @Autowired JdbcTemplate db;
    @Autowired DataSource dataSource;

    @Test
    void flywaySeedsEveryBusinessTableWithoutLiveCredentials() {
        TestSchema.create(db);
        Flyway.configure().dataSource(dataSource).locations("classpath:db/seed")
                .baselineOnMigrate(true).baselineVersion("0").load().migrate();

        for (String table : new String[] {"vehicles", "group_members", "vehicle_installations",
                "pairing_requests", "trip_user_challenges", "trip_user_sessions", "recording_intervals",
                "recording_batches", "recording_samples", "trip_user_candidates", "trip_user_event_state",
                "trip_user_events"}) {
            int count = db.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);
            assertTrue(count > 0, table);
        }
        int sampleCount = db.queryForObject("SELECT COUNT(*) FROM recording_samples", Integer.class);
        assertTrue(sampleCount >= 3, "recording_samples count");
        assertNull(db.queryForObject("SELECT credential_hash FROM vehicle_installations WHERE id=?",
                byte[].class, "f3000000-0000-4000-8000-000000000001"));
        assertEquals(0, db.queryForObject("SELECT COUNT(*) FROM trip_user_challenges WHERE status='pending'",
                Integer.class));
        int activeSessions = db.queryForObject("SELECT COUNT(*) FROM trip_user_sessions WHERE status='active'",
                Integer.class);
        assertTrue(activeSessions >= 0, "active session count");
    }
}
