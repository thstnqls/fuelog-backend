package app.fuelog;

import org.springframework.jdbc.core.JdbcTemplate;

final class ConnectionSettingsTestSchema {
    private ConnectionSettingsTestSchema() {}
    static void drop(JdbcTemplate db) {
        for(String table:new String[]{"connection_reauth_uses","connection_operations","installation_capabilities",
                "recommendation_consents","recommendation_registrations","installation_sync_checkpoints"}) db.execute("DROP TABLE IF EXISTS "+table);
    }
    static void create(JdbcTemplate db) {
        new org.springframework.jdbc.datasource.init.ResourceDatabasePopulator(
                new org.springframework.core.io.ClassPathResource("db/migration/V12__connection_settings_and_operations.sql"))
                .execute(java.util.Objects.requireNonNull(db.getDataSource()));
    }
}
