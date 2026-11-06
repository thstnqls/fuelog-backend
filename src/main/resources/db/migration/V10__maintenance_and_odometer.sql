CREATE TABLE vehicle_maintenance_state (
    vehicle_id CHAR(36) NOT NULL PRIMARY KEY,
    revision BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT fk_maintenance_state_vehicle FOREIGN KEY (vehicle_id) REFERENCES vehicles(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- Trusted provisioning only; mobile users cannot certify vehicle identity or manufacturer documents.
CREATE TABLE vehicle_maintenance_profiles (
    vehicle_id CHAR(36) NOT NULL PRIMARY KEY,
    manufacturer VARCHAR(100) NOT NULL,
    model VARCHAR(100) NOT NULL,
    model_year INT NOT NULL,
    verified_at DATETIME(6) NOT NULL,
    verified_by VARCHAR(100) NOT NULL,
    CONSTRAINT fk_maintenance_profile_vehicle FOREIGN KEY (vehicle_id) REFERENCES vehicles(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE maintenance_manufacturer_evidence (
    id CHAR(36) NOT NULL PRIMARY KEY,
    document_url VARCHAR(1000) NOT NULL,
    document_title VARCHAR(200) NOT NULL,
    manufacturer VARCHAR(100) NOT NULL,
    model VARCHAR(100) NOT NULL,
    model_year_from INT NOT NULL,
    model_year_to INT NOT NULL,
    service_name VARCHAR(100) NOT NULL,
    scope_text VARCHAR(1000) NOT NULL,
    interval_km DECIMAL(12,3) NULL,
    interval_months INT NULL,
    verified_at DATETIME(6) NOT NULL,
    verified_by VARCHAR(100) NOT NULL,
    revoked_at DATETIME(6) NULL,
    CONSTRAINT ck_manufacturer_cycle CHECK (
        (interval_km IS NULL OR interval_km > 0) AND (interval_months IS NULL OR interval_months > 0)
        AND (interval_km IS NOT NULL OR interval_months IS NOT NULL) AND model_year_to >= model_year_from)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE maintenance_plans (
    id CHAR(36) NOT NULL PRIMARY KEY,
    vehicle_id CHAR(36) NOT NULL,
    owner_user_id CHAR(36) NOT NULL,
    name VARCHAR(100) NOT NULL,
    shared BOOLEAN NOT NULL,
    active BOOLEAN NOT NULL,
    basis VARCHAR(20) NOT NULL,
    evidence_id CHAR(36) NULL,
    interval_km DECIMAL(12,3) NULL,
    interval_months INT NULL,
    revision BIGINT NOT NULL,
    last_service_date DATE NULL,
    last_service_km DECIMAL(12,3) NULL,
    next_due_date DATE NULL,
    next_due_km DECIMAL(15,3) NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    KEY ix_maintenance_plan_vehicle (vehicle_id, shared, active),
    CONSTRAINT fk_maintenance_plan_vehicle FOREIGN KEY (vehicle_id) REFERENCES vehicles(id),
    CONSTRAINT fk_maintenance_plan_evidence FOREIGN KEY (evidence_id) REFERENCES maintenance_manufacturer_evidence(id),
    CONSTRAINT ck_maintenance_basis CHECK (basis IN ('USER','MANUFACTURER')),
    CONSTRAINT ck_maintenance_cycle CHECK (
        (interval_km IS NULL OR interval_km > 0) AND (interval_months IS NULL OR interval_months > 0)
        AND (interval_km IS NOT NULL OR interval_months IS NOT NULL))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE maintenance_events (
    id CHAR(36) NOT NULL PRIMARY KEY,
    vehicle_id CHAR(36) NOT NULL,
    item_id CHAR(36) NOT NULL,
    author_user_id CHAR(36) NOT NULL,
    shared BOOLEAN NOT NULL,
    status VARCHAR(30) NOT NULL,
    service_date DATE NULL,
    odometer_km DECIMAL(12,3) NULL,
    payload LONGTEXT NOT NULL,
    revision BIGINT NOT NULL,
    create_key VARCHAR(100) NOT NULL,
    create_hash BINARY(32) NOT NULL,
    create_response LONGTEXT NOT NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    deleted_at DATETIME(6) NULL,
    UNIQUE KEY uq_maintenance_event_key (vehicle_id, author_user_id, create_key),
    KEY ix_maintenance_event_item (vehicle_id, item_id, deleted_at),
    CONSTRAINT fk_maintenance_event_vehicle FOREIGN KEY (vehicle_id) REFERENCES vehicles(id),
    CONSTRAINT fk_maintenance_event_plan FOREIGN KEY (item_id) REFERENCES maintenance_plans(id),
    CONSTRAINT ck_maintenance_event_status CHECK (status IN ('CONFIRMED','VISIT_ESTIMATE'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE maintenance_event_audit (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    event_id CHAR(36) NOT NULL,
    actor_user_id CHAR(36) NOT NULL,
    action VARCHAR(20) NOT NULL,
    revision BIGINT NOT NULL,
    before_payload LONGTEXT NULL,
    after_payload LONGTEXT NULL,
    created_at DATETIME(6) NOT NULL,
    CONSTRAINT fk_maintenance_audit_event FOREIGN KEY (event_id) REFERENCES maintenance_events(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE vehicle_odometer_samples (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    sample_id CHAR(36) NOT NULL,
    installation_id CHAR(36) NOT NULL,
    vehicle_id CHAR(36) NOT NULL,
    km DECIMAL(12,3) NULL,
    observed_at DATETIME(6) NOT NULL,
    unit VARCHAR(10) NOT NULL,
    source VARCHAR(40) NOT NULL,
    quality VARCHAR(20) NOT NULL,
    payload_hash BINARY(32) NOT NULL,
    received_at DATETIME(6) NOT NULL,
    UNIQUE KEY uq_odometer_installation_sample (installation_id, sample_id),
    KEY ix_odometer_latest (vehicle_id, quality, observed_at),
    CONSTRAINT fk_odometer_installation FOREIGN KEY (installation_id) REFERENCES vehicle_installations(id),
    CONSTRAINT fk_odometer_vehicle FOREIGN KEY (vehicle_id) REFERENCES vehicles(id),
    CONSTRAINT ck_odometer_unit CHECK (unit='km'),
    CONSTRAINT ck_odometer_source CHECK (source='PLEOS_VEHICLE_API'),
    CONSTRAINT ck_odometer_quality CHECK (quality IN ('VALID','UNSUPPORTED','ERROR')),
    CONSTRAINT ck_odometer_value CHECK ((quality='VALID' AND km IS NOT NULL AND km>=0) OR (quality<>'VALID' AND km IS NULL))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
