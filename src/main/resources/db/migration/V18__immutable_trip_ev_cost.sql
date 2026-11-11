CREATE TABLE trip_ev_costs (
    summary_id BIGINT NOT NULL PRIMARY KEY,
    payload LONGTEXT NOT NULL,
    price_evidence LONGTEXT NOT NULL,
    calculated_at DATETIME(6) NOT NULL,
    CONSTRAINT fk_trip_ev_cost_summary FOREIGN KEY (summary_id) REFERENCES trip_summaries(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- Existing estimates may be preserved; future client-supplied EV prices are not legacy amounts.
ALTER TABLE trip_summaries ADD COLUMN legacy_ev_cost_eligible BOOLEAN NOT NULL DEFAULT FALSE;
UPDATE trip_summaries SET legacy_ev_cost_eligible=TRUE;
