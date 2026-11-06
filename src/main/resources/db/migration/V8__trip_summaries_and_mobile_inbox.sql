CREATE TABLE trip_summaries (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    installation_id CHAR(36) NOT NULL,
    trip_id CHAR(36) NOT NULL,
    vehicle_id CHAR(36) NOT NULL,
    user_id CHAR(36) NULL,
    consent_id CHAR(36) NULL,
    revision BIGINT NOT NULL,
    payload_hash BINARY(32) NOT NULL,
    payload LONGTEXT NOT NULL,
    synced_at DATETIME(6) NOT NULL,
    UNIQUE KEY uq_trip_summary_installation (installation_id, trip_id),
    KEY ix_trip_summary_user (user_id, synced_at),
    CONSTRAINT fk_trip_summary_installation FOREIGN KEY (installation_id) REFERENCES vehicle_installations(id),
    CONSTRAINT fk_trip_summary_vehicle FOREIGN KEY (vehicle_id) REFERENCES vehicles(id),
    CONSTRAINT fk_trip_summary_consent FOREIGN KEY (consent_id) REFERENCES trip_user_candidates(id),
    CONSTRAINT ck_trip_summary_revision CHECK (revision > 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE mobile_trip_inbox (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    recipient_id CHAR(36) NOT NULL,
    trip_id CHAR(36) NOT NULL,
    summary_id BIGINT NOT NULL,
    trip_revision BIGINT NOT NULL,
    snapshot LONGTEXT NOT NULL,
    delivered_at DATETIME(6) NOT NULL,
    read_at DATETIME(6) NULL,
    UNIQUE KEY uq_mobile_inbox_recipient_trip (recipient_id, trip_id),
    KEY ix_mobile_inbox_cursor (recipient_id, id),
    CONSTRAINT fk_mobile_inbox_summary FOREIGN KEY (summary_id) REFERENCES trip_summaries(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
