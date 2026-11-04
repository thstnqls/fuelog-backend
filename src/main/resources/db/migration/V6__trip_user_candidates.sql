ALTER TABLE trip_user_challenges
    ADD COLUMN interval_id CHAR(36) NULL,
    ADD KEY ix_trip_challenge_interval (interval_id),
    ADD CONSTRAINT fk_trip_challenge_interval FOREIGN KEY (interval_id) REFERENCES recording_intervals(id);

CREATE TABLE trip_user_candidates (
    id CHAR(36) NOT NULL PRIMARY KEY,
    challenge_id CHAR(36) NOT NULL,
    installation_id CHAR(36) NOT NULL,
    vehicle_id CHAR(36) NOT NULL,
    interval_id CHAR(36) NOT NULL,
    user_id CHAR(36) NOT NULL,
    display_name VARCHAR(100) NOT NULL,
    consent_operation_id VARCHAR(100) NOT NULL,
    proof_fingerprint BINARY(32) NOT NULL,
    verified_operation_id VARCHAR(100) NULL,
    status VARCHAR(20) NOT NULL,
    expires_at DATETIME(6) NOT NULL,
    verification_expires_at DATETIME(6) NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    UNIQUE KEY uq_trip_candidate_challenge (challenge_id),
    UNIQUE KEY uq_trip_candidate_consent_op (installation_id, consent_operation_id),
    UNIQUE KEY uq_trip_candidate_verify_op (installation_id, verified_operation_id),
    KEY ix_trip_candidate_interval (installation_id, vehicle_id, interval_id, status, expires_at),
    CONSTRAINT fk_trip_candidate_challenge FOREIGN KEY (challenge_id) REFERENCES trip_user_challenges(id),
    CONSTRAINT fk_trip_candidate_installation FOREIGN KEY (installation_id) REFERENCES vehicle_installations(id),
    CONSTRAINT fk_trip_candidate_vehicle FOREIGN KEY (vehicle_id) REFERENCES vehicles(id),
    CONSTRAINT fk_trip_candidate_interval FOREIGN KEY (interval_id) REFERENCES recording_intervals(id),
    CONSTRAINT ck_trip_candidate_status CHECK (status IN ('active','verified','revoked','expired'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE trip_user_event_state (
    installation_id CHAR(36) NOT NULL PRIMARY KEY,
    revision BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT fk_trip_event_state_installation FOREIGN KEY (installation_id) REFERENCES vehicle_installations(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE trip_user_events (
    installation_id CHAR(36) NOT NULL,
    revision BIGINT NOT NULL,
    event_type VARCHAR(30) NOT NULL,
    created_at DATETIME(6) NOT NULL,
    PRIMARY KEY (installation_id, revision),
    CONSTRAINT fk_trip_event_installation FOREIGN KEY (installation_id) REFERENCES vehicle_installations(id),
    CONSTRAINT ck_trip_event_type CHECK (event_type IN ('candidates_changed','session_changed'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

ALTER TABLE recording_samples
    ADD COLUMN user_id CHAR(36) NULL,
    ADD COLUMN verified_candidate_id CHAR(36) NULL,
    ADD KEY ix_recording_sample_user (user_id, observed_at),
    ADD CONSTRAINT fk_recording_sample_candidate FOREIGN KEY (verified_candidate_id) REFERENCES trip_user_candidates(id);
