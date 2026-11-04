ALTER TABLE vehicle_installations
    ADD COLUMN session_version BIGINT NOT NULL DEFAULT 0;

CREATE TABLE trip_user_challenges (
    id CHAR(36) NOT NULL PRIMARY KEY,
    installation_id CHAR(36) NOT NULL,
    vehicle_id CHAR(36) NOT NULL,
    operation_id VARCHAR(100) NOT NULL,
    proof_hash BINARY(32) NULL,
    status VARCHAR(20) NOT NULL,
    expires_at DATETIME(6) NOT NULL,
    approved_user_id CHAR(36) NULL,
    display_name VARCHAR(100) NULL,
    session_id CHAR(36) NULL,
    active_installation_id CHAR(36) NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    UNIQUE KEY uq_trip_challenge_operation (installation_id, operation_id),
    UNIQUE KEY uq_trip_challenge_active (active_installation_id),
    KEY ix_trip_challenge_installation (installation_id, created_at),
    CONSTRAINT fk_trip_challenge_installation FOREIGN KEY (installation_id) REFERENCES vehicle_installations(id),
    CONSTRAINT fk_trip_challenge_vehicle FOREIGN KEY (vehicle_id) REFERENCES vehicles(id),
    CONSTRAINT ck_trip_challenge_status CHECK (status IN ('pending','approved','expired','revoked'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE trip_user_sessions (
    id CHAR(36) NOT NULL PRIMARY KEY,
    installation_id CHAR(36) NOT NULL,
    vehicle_id CHAR(36) NOT NULL,
    user_id CHAR(36) NOT NULL,
    display_name VARCHAR(100) NOT NULL,
    version BIGINT NOT NULL,
    token_hash BINARY(32) NULL,
    status VARCHAR(20) NOT NULL,
    expires_at DATETIME(6) NOT NULL,
    active_installation_id CHAR(36) NULL,
    created_at DATETIME(6) NOT NULL,
    revoked_at DATETIME(6) NULL,
    UNIQUE KEY uq_trip_session_token (token_hash),
    UNIQUE KEY uq_trip_session_active (active_installation_id),
    UNIQUE KEY uq_trip_session_version (installation_id, version),
    CONSTRAINT fk_trip_session_installation FOREIGN KEY (installation_id) REFERENCES vehicle_installations(id),
    CONSTRAINT fk_trip_session_vehicle FOREIGN KEY (vehicle_id) REFERENCES vehicles(id),
    CONSTRAINT ck_trip_session_status CHECK (status IN ('active','revoked'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE recording_intervals (
    id CHAR(36) NOT NULL PRIMARY KEY,
    installation_id CHAR(36) NOT NULL,
    vehicle_id CHAR(36) NOT NULL,
    user_id CHAR(36) NULL,
    session_version BIGINT NULL,
    local_interval_id VARCHAR(100) NOT NULL,
    source VARCHAR(20) NOT NULL,
    status VARCHAR(20) NOT NULL,
    started_at DATETIME(6) NOT NULL,
    ended_at DATETIME(6) NULL,
    finish_operation_id VARCHAR(100) NULL,
    finish_last_sequence BIGINT NULL,
    last_accepted_sequence BIGINT NOT NULL DEFAULT 0,
    last_sample_at DATETIME(6) NULL,
    last_synced_at DATETIME(6) NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    UNIQUE KEY uq_recording_local_id (installation_id, local_interval_id),
    KEY ix_recording_vehicle_user (vehicle_id, user_id, started_at),
    KEY ix_recording_installation (installation_id, started_at),
    CONSTRAINT fk_recording_installation FOREIGN KEY (installation_id) REFERENCES vehicle_installations(id),
    CONSTRAINT fk_recording_vehicle FOREIGN KEY (vehicle_id) REFERENCES vehicles(id),
    CONSTRAINT ck_recording_source CHECK (source = 'manual_app'),
    CONSTRAINT ck_recording_status CHECK (status IN ('recording','finished'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE recording_batches (
    interval_id CHAR(36) NOT NULL,
    batch_id VARCHAR(100) NOT NULL,
    payload_hash BINARY(32) NOT NULL,
    accepted_count INT NOT NULL,
    last_accepted_sequence BIGINT NOT NULL,
    received_at DATETIME(6) NOT NULL,
    PRIMARY KEY (interval_id, batch_id),
    CONSTRAINT fk_recording_batch_interval FOREIGN KEY (interval_id) REFERENCES recording_intervals(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE recording_samples (
    interval_id CHAR(36) NOT NULL,
    sequence BIGINT NOT NULL,
    batch_id VARCHAR(100) NOT NULL,
    observed_at DATETIME(6) NOT NULL,
    distance_km DECIMAL(12,3) NULL,
    missing_reason VARCHAR(40) NULL,
    fuel_liters DECIMAL(10,3) NULL,
    fuel_status VARCHAR(20) NOT NULL,
    received_at DATETIME(6) NOT NULL,
    PRIMARY KEY (interval_id, sequence),
    KEY ix_recording_sample_observed (interval_id, observed_at),
    CONSTRAINT fk_recording_sample_interval FOREIGN KEY (interval_id) REFERENCES recording_intervals(id),
    CONSTRAINT ck_recording_sample_distance CHECK (
        (distance_km IS NOT NULL AND distance_km >= 0 AND missing_reason IS NULL)
        OR (distance_km IS NULL AND missing_reason IS NOT NULL)
    ),
    CONSTRAINT ck_recording_sample_fuel CHECK (fuel_status IN ('available','unsupported','error'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
