ALTER TABLE vehicle_installations
    ADD COLUMN registration_version BIGINT NOT NULL DEFAULT 0;
ALTER TABLE vehicle_installations ADD COLUMN personal_access_blocked BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE vehicle_installations ADD COLUMN access_blocked_at DATETIME(6) NULL;
ALTER TABLE vehicle_installations ADD COLUMN device_alias VARCHAR(100) NULL;

CREATE TABLE connection_operations (
    id CHAR(36) NOT NULL PRIMARY KEY,
    installation_id CHAR(36) NOT NULL,
    operation_id VARCHAR(100) NOT NULL,
    kind VARCHAR(10) NOT NULL,
    status VARCHAR(30) NOT NULL,
    vehicle_id CHAR(36) NULL,
    user_id CHAR(36) NULL,
    registration_version BIGINT NOT NULL,
    session_version BIGINT NOT NULL,
    decision_user_id CHAR(36) NULL,
    pending_records_policy VARCHAR(40) NULL,
    created_at DATETIME(6) NOT NULL,
    completed_at DATETIME(6) NULL,
    UNIQUE KEY uq_disconnect_operation (installation_id,operation_id),
    KEY ix_disconnect_pending (vehicle_id,status),
    CONSTRAINT fk_disconnect_installation FOREIGN KEY (installation_id) REFERENCES vehicle_installations(id),
    CONSTRAINT ck_disconnect_kind CHECK (kind IN ('USER','VEHICLE')),
    CONSTRAINT ck_disconnect_status CHECK (status IN ('AWAITING_ADMIN','COMPLETED','REJECTED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE connection_reauth_uses (
    proof_hash BINARY(32) NOT NULL PRIMARY KEY,
    operation_id CHAR(36) NOT NULL,
    actor_id CHAR(36) NOT NULL,
    authenticated_at DATETIME(6) NOT NULL,
    used_at DATETIME(6) NOT NULL,
    CONSTRAINT fk_reauth_operation FOREIGN KEY (operation_id) REFERENCES connection_operations(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE installation_capabilities (
    installation_id CHAR(36) NOT NULL PRIMARY KEY,
    registration_version BIGINT NOT NULL,
    observed_at BIGINT NOT NULL,
    fuel VARCHAR(30) NOT NULL,
    distance VARCHAR(30) NOT NULL,
    location VARCHAR(30) NOT NULL,
    CONSTRAINT fk_capability_installation FOREIGN KEY (installation_id) REFERENCES vehicle_installations(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- Provisioned by trusted server feature verification, never by capability reporting.
CREATE TABLE recommendation_registrations (
    installation_id CHAR(36) NOT NULL,
    registration_version BIGINT NOT NULL,
    registered BOOLEAN NOT NULL DEFAULT FALSE,
    verified BOOLEAN NOT NULL DEFAULT FALSE,
    verified_at DATETIME(6) NULL,
    verification_source VARCHAR(200) NULL,
    PRIMARY KEY (installation_id,registration_version),
    CONSTRAINT fk_recommendation_installation FOREIGN KEY (installation_id) REFERENCES vehicle_installations(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE recommendation_consents (
    installation_id CHAR(36) NOT NULL,
    registration_version BIGINT NOT NULL,
    user_id CHAR(36) NOT NULL,
    consented BOOLEAN NOT NULL,
    enabled BOOLEAN NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    PRIMARY KEY (installation_id,registration_version,user_id),
    CONSTRAINT fk_recommendation_consent_installation FOREIGN KEY (installation_id) REFERENCES vehicle_installations(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- Only a server queue reconciler may write these checkpoints. No client-complete flag is accepted.
CREATE TABLE installation_sync_checkpoints (
    installation_id CHAR(36) NOT NULL,
    registration_version BIGINT NOT NULL,
    user_id CHAR(36) NOT NULL,
    pending_count BIGINT NULL,
    last_acknowledged_at BIGINT NULL,
    verified_at BIGINT NULL,
    PRIMARY KEY (installation_id,registration_version,user_id),
    CONSTRAINT fk_sync_checkpoint_installation FOREIGN KEY (installation_id) REFERENCES vehicle_installations(id),
    CONSTRAINT ck_sync_pending CHECK (pending_count IS NULL OR pending_count >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
