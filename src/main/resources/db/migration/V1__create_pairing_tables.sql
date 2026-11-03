CREATE TABLE IF NOT EXISTS vehicles (
    id CHAR(36) NOT NULL PRIMARY KEY,
    name VARCHAR(100) NOT NULL,
    created_by CHAR(36) NOT NULL,
    source_request_id CHAR(36) NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    UNIQUE KEY uq_vehicle_source_request (source_request_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS group_members (
    vehicle_id CHAR(36) NOT NULL,
    user_id CHAR(36) NOT NULL,
    role VARCHAR(20) NOT NULL,
    created_at DATETIME(6) NOT NULL,
    PRIMARY KEY (vehicle_id, user_id),
    CONSTRAINT fk_member_vehicle FOREIGN KEY (vehicle_id) REFERENCES vehicles(id),
    CONSTRAINT ck_member_role CHECK (role IN ('OWNER', 'MANAGER', 'MEMBER'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS vehicle_installations (
    id CHAR(36) NOT NULL PRIMARY KEY,
    vehicle_id CHAR(36) NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    UNIQUE KEY uq_installation_vehicle (vehicle_id),
    CONSTRAINT fk_installation_vehicle FOREIGN KEY (vehicle_id) REFERENCES vehicles(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS pairing_requests (
    id CHAR(36) NOT NULL PRIMARY KEY,
    installation_id CHAR(36) NOT NULL,
    proof_hash BINARY(32) NULL,
    status VARCHAR(40) NOT NULL,
    expires_at DATETIME(6) NOT NULL,
    selected_vehicle_id CHAR(36) NULL,
    new_vehicle_name VARCHAR(100) NULL,
    approved_user_id CHAR(36) NULL,
    issue_key VARCHAR(100) NOT NULL,
    intent_key VARCHAR(100) NULL,
    confirmation_code CHAR(6) NOT NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    active_installation_id CHAR(36) NULL,
    UNIQUE KEY uq_pairing_active_installation (active_installation_id),
    UNIQUE KEY uq_pairing_issue_key (installation_id, issue_key),
    KEY ix_pairing_installation (installation_id, created_at),
    CONSTRAINT fk_pairing_installation FOREIGN KEY (installation_id) REFERENCES vehicle_installations(id),
    CONSTRAINT fk_pairing_vehicle FOREIGN KEY (selected_vehicle_id) REFERENCES vehicles(id),
    CONSTRAINT ck_pairing_status CHECK (status IN ('pending', 'awaiting_vehicle_confirmation', 'completed', 'expired', 'revoked')),
    CONSTRAINT ck_pairing_active CHECK (
        (status IN ('pending', 'awaiting_vehicle_confirmation') AND active_installation_id = installation_id)
        OR (status IN ('completed', 'expired', 'revoked') AND active_installation_id IS NULL)
    )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

ALTER TABLE vehicles ADD CONSTRAINT fk_vehicle_source_request
    FOREIGN KEY (source_request_id) REFERENCES pairing_requests(id);
