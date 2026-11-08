-- V1~V12와 같은 문자셋·엔진을 지정한다. DB 기본 collation을 상속하면 기존 UUID 외래키와 충돌할 수 있다.
CREATE TABLE charge_candidates (
    id CHAR(36) PRIMARY KEY,
    installation_id CHAR(36) NOT NULL,
    vehicle_id CHAR(36) NOT NULL,
    owner_id CHAR(36) NOT NULL,
    interval_id CHAR(36) NOT NULL,
    payload LONGTEXT NOT NULL,
    detected_at BIGINT NOT NULL,
    location_operation_id VARCHAR(100) NULL,
    created_at DATETIME(6) NOT NULL,
    CONSTRAINT fk_charge_installation FOREIGN KEY (installation_id) REFERENCES vehicle_installations(id),
    CONSTRAINT fk_charge_interval FOREIGN KEY (interval_id) REFERENCES recording_intervals(id),
    INDEX ix_charge_owner (owner_id,created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE charge_candidate_operations (
    candidate_id CHAR(36) NOT NULL,
    operation_id VARCHAR(100) NOT NULL,
    request_payload LONGTEXT NOT NULL,
    response_payload LONGTEXT NOT NULL,
    PRIMARY KEY(candidate_id,operation_id),
    CONSTRAINT fk_charge_operation FOREIGN KEY (candidate_id) REFERENCES charge_candidates(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE charging_tariffs (
    operator_id VARCHAR(10) NOT NULL,
    power_band VARCHAR(20) NOT NULL,
    tariff_version VARCHAR(40) NOT NULL,
    won_per_kwh DECIMAL(10,3) NOT NULL,
    source_url VARCHAR(500) NOT NULL,
    source_updated_at VARCHAR(30) NULL,
    verified_at DATETIME(6) NOT NULL,
    valid_until DATETIME(6) NOT NULL,
    PRIMARY KEY(operator_id,power_band,tariff_version),
    CONSTRAINT ck_charge_price CHECK(won_per_kwh>0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE charger_catalog (
    station_id VARCHAR(30) NOT NULL,
    charger_id VARCHAR(20) NOT NULL,
    station_name VARCHAR(200) NOT NULL,
    operator_id VARCHAR(10) NOT NULL,
    latitude DOUBLE NOT NULL,
    longitude DOUBLE NOT NULL,
    connector_kw DECIMAL(10,3) NOT NULL,
    observed_at DATETIME(6) NOT NULL,
    PRIMARY KEY(station_id,charger_id),
    INDEX ix_charger_position(latitude,longitude)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
