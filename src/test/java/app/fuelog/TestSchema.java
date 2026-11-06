package app.fuelog;

import org.springframework.jdbc.core.JdbcTemplate;

final class TestSchema {
    private TestSchema() {}

    static void create(JdbcTemplate db) {
        db.execute("DROP TABLE IF EXISTS mobile_trip_inbox");
        db.execute("DROP TABLE IF EXISTS trip_summaries");
        db.execute("DROP TABLE IF EXISTS recording_samples");
        db.execute("DROP TABLE IF EXISTS recording_batches");
        db.execute("DROP TABLE IF EXISTS trip_user_events");
        db.execute("DROP TABLE IF EXISTS trip_user_event_state");
        db.execute("DROP TABLE IF EXISTS trip_user_candidates");
        db.execute("DROP TABLE IF EXISTS recording_intervals");
        db.execute("DROP TABLE IF EXISTS trip_user_sessions");
        db.execute("DROP TABLE IF EXISTS trip_user_challenges");
        db.execute("DROP TABLE IF EXISTS group_members");
        db.execute("DROP TABLE IF EXISTS pairing_requests");
        db.execute("DROP TABLE IF EXISTS vehicle_installations");
        db.execute("DROP TABLE IF EXISTS vehicles");
        db.execute("CREATE TABLE vehicles (id VARCHAR(36) PRIMARY KEY, name VARCHAR(100), created_by VARCHAR(36), source_request_id VARCHAR(36) UNIQUE, created_at TIMESTAMP, updated_at TIMESTAMP)");
        db.execute("CREATE TABLE vehicle_installations (id VARCHAR(36) PRIMARY KEY, vehicle_id VARCHAR(36) UNIQUE, credential_hash BINARY(32) UNIQUE, session_version BIGINT DEFAULT 0 NOT NULL, created_at TIMESTAMP, updated_at TIMESTAMP)");
        db.execute("CREATE TABLE pairing_requests (id VARCHAR(36) PRIMARY KEY, installation_id VARCHAR(36), active_installation_id VARCHAR(36) UNIQUE, proof_hash BINARY(32), status VARCHAR(40), expires_at TIMESTAMP, selected_vehicle_id VARCHAR(36), new_vehicle_name VARCHAR(100), approved_user_id VARCHAR(36), issue_key VARCHAR(100), intent_key VARCHAR(100), confirmation_code VARCHAR(6), created_at TIMESTAMP, updated_at TIMESTAMP, UNIQUE(installation_id, issue_key))");
        db.execute("CREATE TABLE group_members (vehicle_id VARCHAR(36), user_id VARCHAR(36), role VARCHAR(20), created_at TIMESTAMP, PRIMARY KEY(vehicle_id,user_id))");
        db.execute("CREATE TABLE trip_user_challenges (id VARCHAR(36) PRIMARY KEY, installation_id VARCHAR(36), vehicle_id VARCHAR(36), interval_id VARCHAR(36), operation_id VARCHAR(100), proof_hash BINARY(32), status VARCHAR(20), expires_at TIMESTAMP, approved_user_id VARCHAR(36), display_name VARCHAR(100), session_id VARCHAR(36), active_installation_id VARCHAR(36) UNIQUE, created_at TIMESTAMP, updated_at TIMESTAMP, UNIQUE(installation_id,operation_id))");
        db.execute("CREATE TABLE trip_user_sessions (id VARCHAR(36) PRIMARY KEY, installation_id VARCHAR(36), vehicle_id VARCHAR(36), user_id VARCHAR(36), display_name VARCHAR(100), version BIGINT, token_hash BINARY(32) UNIQUE, status VARCHAR(20), expires_at TIMESTAMP, active_installation_id VARCHAR(36) UNIQUE, created_at TIMESTAMP, revoked_at TIMESTAMP, UNIQUE(installation_id,version))");
        db.execute("CREATE TABLE recording_intervals (id VARCHAR(36) PRIMARY KEY, installation_id VARCHAR(36), vehicle_id VARCHAR(36), user_id VARCHAR(36), session_version BIGINT, local_interval_id VARCHAR(100), source VARCHAR(20), status VARCHAR(20), started_at TIMESTAMP, ended_at TIMESTAMP, finish_operation_id VARCHAR(100), finish_last_sequence BIGINT, last_accepted_sequence BIGINT DEFAULT 0, last_sample_at TIMESTAMP, last_synced_at TIMESTAMP, created_at TIMESTAMP, updated_at TIMESTAMP, UNIQUE(installation_id,local_interval_id))");
        db.execute("CREATE TABLE trip_user_candidates (id VARCHAR(36) PRIMARY KEY, challenge_id VARCHAR(36) UNIQUE, installation_id VARCHAR(36), vehicle_id VARCHAR(36), interval_id VARCHAR(36), user_id VARCHAR(36), display_name VARCHAR(100), consent_operation_id VARCHAR(100), proof_fingerprint BINARY(32), verified_operation_id VARCHAR(100), status VARCHAR(20), expires_at TIMESTAMP, verification_expires_at TIMESTAMP, created_at TIMESTAMP, updated_at TIMESTAMP, UNIQUE(installation_id,consent_operation_id), UNIQUE(installation_id,verified_operation_id))");
        db.execute("CREATE TABLE trip_user_event_state (installation_id VARCHAR(36) PRIMARY KEY, revision BIGINT)");
        db.execute("CREATE TABLE trip_user_events (installation_id VARCHAR(36), revision BIGINT, event_type VARCHAR(30), created_at TIMESTAMP, PRIMARY KEY(installation_id,revision))");
        db.execute("CREATE TABLE recording_batches (interval_id VARCHAR(36), batch_id VARCHAR(100), payload_hash BINARY(32), accepted_count INT, last_accepted_sequence BIGINT, received_at TIMESTAMP, PRIMARY KEY(interval_id,batch_id))");
        db.execute("CREATE TABLE recording_samples (interval_id VARCHAR(36), sequence BIGINT, batch_id VARCHAR(100), observed_at TIMESTAMP, distance_km DECIMAL(12,3), missing_reason VARCHAR(40), fuel_liters DECIMAL(10,3), fuel_status VARCHAR(20), received_at TIMESTAMP, user_id VARCHAR(36), verified_candidate_id VARCHAR(36), PRIMARY KEY(interval_id,sequence))");
        db.execute("CREATE TABLE trip_summaries (id BIGINT AUTO_INCREMENT PRIMARY KEY, installation_id VARCHAR(36) NOT NULL, trip_id VARCHAR(36) NOT NULL, vehicle_id VARCHAR(36) NOT NULL, user_id VARCHAR(36), consent_id VARCHAR(36), revision BIGINT NOT NULL, payload_hash BINARY(32) NOT NULL, payload CLOB NOT NULL, synced_at TIMESTAMP NOT NULL, UNIQUE(installation_id,trip_id))");
        db.execute("CREATE TABLE mobile_trip_inbox (id BIGINT AUTO_INCREMENT PRIMARY KEY, recipient_id VARCHAR(36) NOT NULL, trip_id VARCHAR(36) NOT NULL, summary_id BIGINT NOT NULL, trip_revision BIGINT NOT NULL, snapshot CLOB NOT NULL, delivered_at TIMESTAMP NOT NULL, read_at TIMESTAMP, UNIQUE(recipient_id,trip_id))");
    }
}
