CREATE TABLE fuel_candidates (
    id CHAR(36) NOT NULL PRIMARY KEY,
    installation_id CHAR(36) NOT NULL,
    vehicle_id CHAR(36) NOT NULL,
    owner_id CHAR(36) NOT NULL,
    interval_id CHAR(36) NOT NULL,
    start_at BIGINT NOT NULL,
    end_at BIGINT NOT NULL,
    increase_percent DECIMAL(6,3) NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'CANDIDATE',
    revision BIGINT NOT NULL DEFAULT 1,
    capacity_verified BOOLEAN NOT NULL DEFAULT FALSE,
    estimated_liters DECIMAL(12,3) NULL,
    estimated_amount DECIMAL(15,2) NULL,
    price_at BIGINT NULL,
    corrected_place VARCHAR(200) NULL,
    corrected_liters DECIMAL(12,3) NULL,
    corrected_amount DECIMAL(15,2) NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    UNIQUE KEY uq_fuel_installation_candidate (installation_id,id),
    CONSTRAINT fk_fuel_installation FOREIGN KEY (installation_id) REFERENCES vehicle_installations(id),
    CONSTRAINT fk_fuel_vehicle FOREIGN KEY (vehicle_id) REFERENCES vehicles(id),
    CONSTRAINT fk_fuel_interval FOREIGN KEY (interval_id) REFERENCES recording_intervals(id),
    CONSTRAINT ck_fuel_status CHECK (status IN ('CANDIDATE','CONFIRMED','REJECTED')),
    CONSTRAINT ck_fuel_evidence CHECK (start_at <= end_at AND increase_percent > 0 AND increase_percent <= 100),
    CONSTRAINT ck_fuel_revision CHECK (revision > 0),
    CONSTRAINT ck_fuel_capacity CHECK (capacity_verified OR (estimated_liters IS NULL AND estimated_amount IS NULL)),
    CONSTRAINT ck_fuel_price CHECK (estimated_amount IS NULL OR price_at IS NOT NULL)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- Written only by a trusted server-side vehicle/price verification process, never by vehicle sync.
CREATE TABLE vehicle_fuel_metadata (
    vehicle_id CHAR(36) NOT NULL PRIMARY KEY,
    capacity_liters DECIMAL(10,3) NULL,
    capacity_verified_at DATETIME(6) NULL,
    capacity_source VARCHAR(200) NULL,
    unit_price DECIMAL(12,3) NULL,
    price_at BIGINT NULL,
    price_valid_until BIGINT NULL,
    price_source VARCHAR(200) NULL,
    CONSTRAINT fk_fuel_metadata_vehicle FOREIGN KEY (vehicle_id) REFERENCES vehicles(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE fuel_candidate_operations (
    candidate_id CHAR(36) NOT NULL,
    operation_id VARCHAR(100) NOT NULL,
    actor_id CHAR(36) NOT NULL,
    request_payload LONGTEXT NOT NULL,
    response_payload LONGTEXT NOT NULL,
    previous_revision BIGINT NOT NULL,
    resulting_revision BIGINT NOT NULL,
    created_at DATETIME(6) NOT NULL,
    PRIMARY KEY (candidate_id,operation_id),
    CONSTRAINT fk_fuel_operation_candidate FOREIGN KEY (candidate_id) REFERENCES fuel_candidates(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

ALTER TABLE mobile_trip_inbox
    MODIFY trip_id CHAR(36) NULL,
    MODIFY summary_id BIGINT NULL,
    MODIFY trip_revision BIGINT NULL,
    MODIFY snapshot LONGTEXT NULL,
    ADD COLUMN kind VARCHAR(30) NOT NULL DEFAULT 'TRIP_SUMMARY',
    ADD COLUMN candidate_id CHAR(36) NULL,
    ADD UNIQUE KEY uq_mobile_fuel_recipient (recipient_id,kind,candidate_id),
    ADD CONSTRAINT fk_mobile_fuel_candidate FOREIGN KEY (candidate_id) REFERENCES fuel_candidates(id);
