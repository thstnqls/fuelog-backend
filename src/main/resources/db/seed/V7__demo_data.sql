-- Opt-in Flyway seed. Synthetic users have no Supabase account, and every proof/session is expired or revoked.
-- Dates are UTC. Fixed IDs make rows easy to inspect and join in a SQL client.

INSERT INTO vehicles (id, name, created_by, source_request_id, created_at, updated_at) VALUES
('f1000000-0000-4000-8000-000000000001', '테스트 차량 A', 'f2000000-0000-4000-8000-000000000001', NULL, '2026-11-01 09:00:00', '2026-11-01 09:00:00'),
('f1000000-0000-4000-8000-000000000002', '테스트 차량 B', 'f2000000-0000-4000-8000-000000000002', NULL, '2026-11-01 09:00:00', '2026-11-01 09:00:00');

INSERT INTO group_members (vehicle_id, user_id, role, created_at) VALUES
('f1000000-0000-4000-8000-000000000001', 'f2000000-0000-4000-8000-000000000001', 'OWNER', '2026-11-01 09:00:00'),
('f1000000-0000-4000-8000-000000000001', 'f2000000-0000-4000-8000-000000000002', 'MEMBER', '2026-11-01 09:00:00'),
('f1000000-0000-4000-8000-000000000002', 'f2000000-0000-4000-8000-000000000002', 'OWNER', '2026-11-01 09:00:00');

INSERT INTO vehicle_installations (id, vehicle_id, credential_hash, session_version, created_at, updated_at) VALUES
('f3000000-0000-4000-8000-000000000001', 'f1000000-0000-4000-8000-000000000001', NULL, 3, '2026-11-01 09:05:00', '2026-11-01 09:05:00'),
('f3000000-0000-4000-8000-000000000002', 'f1000000-0000-4000-8000-000000000002', NULL, 0, '2026-11-01 09:05:00', '2026-11-01 09:05:00');

INSERT INTO pairing_requests (id, installation_id, proof_hash, status, expires_at, selected_vehicle_id,
    new_vehicle_name, approved_user_id, issue_key, intent_key, confirmation_code,
    created_at, updated_at, active_installation_id) VALUES
('f4000000-0000-4000-8000-000000000001', 'f3000000-0000-4000-8000-000000000001', NULL,
 'completed', '2026-11-01 09:07:00', 'f1000000-0000-4000-8000-000000000001', NULL,
 'f2000000-0000-4000-8000-000000000001', 'demo-pair-001', 'demo-intent-001', NULL,
 '2026-11-01 09:05:00', '2026-11-01 09:06:00', NULL),
('f4000000-0000-4000-8000-000000000002', 'f3000000-0000-4000-8000-000000000002', NULL,
 'expired', '2026-11-01 09:07:00', NULL, NULL, NULL, 'demo-pair-002', NULL, NULL,
 '2026-11-01 09:05:00', '2026-11-01 09:07:00', NULL);

INSERT INTO trip_user_sessions (id, installation_id, vehicle_id, user_id, display_name, version,
    token_hash, status, expires_at, active_installation_id, created_at, revoked_at) VALUES
('f6000000-0000-4000-8000-000000000001', 'f3000000-0000-4000-8000-000000000001',
 'f1000000-0000-4000-8000-000000000001', 'f2000000-0000-4000-8000-000000000001',
 '테스트 운전자 A', 1, NULL, 'revoked', '2026-11-01 09:40:00', NULL,
 '2026-11-01 09:10:00', '2026-11-01 09:20:00');

INSERT INTO recording_intervals (id, installation_id, vehicle_id, user_id, session_version,
    local_interval_id, source, status, started_at, ended_at, finish_operation_id, finish_last_sequence,
    last_accepted_sequence, last_sample_at, last_synced_at, created_at, updated_at) VALUES
('f7000000-0000-4000-8000-000000000001', 'f3000000-0000-4000-8000-000000000001',
 'f1000000-0000-4000-8000-000000000001', 'f2000000-0000-4000-8000-000000000001', 1,
 'demo-interval-001', 'manual_app', 'finished', '2026-11-01 09:11:00', '2026-11-01 09:20:00',
 'demo-finish-001', 1, 1, '2026-11-01 09:11:03', '2026-11-01 09:11:04',
 '2026-11-01 09:11:00', '2026-11-01 09:20:00'),
('f7000000-0000-4000-8000-000000000002', 'f3000000-0000-4000-8000-000000000001',
 'f1000000-0000-4000-8000-000000000001', NULL, NULL,
 'demo-interval-002', 'manual_app', 'recording', '2026-11-01 10:00:00', NULL,
 NULL, NULL, 2, '2026-11-01 10:00:06', '2026-11-01 10:00:07',
 '2026-11-01 10:00:00', '2026-11-01 10:00:07');

INSERT INTO trip_user_challenges (id, installation_id, vehicle_id, interval_id, operation_id, proof_hash,
    status, expires_at, approved_user_id, display_name, session_id, active_installation_id,
    created_at, updated_at) VALUES
('f5000000-0000-4000-8000-000000000001', 'f3000000-0000-4000-8000-000000000001',
 'f1000000-0000-4000-8000-000000000001', 'f7000000-0000-4000-8000-000000000002',
 'demo-challenge-001', NULL, 'approved', '2026-11-01 10:02:00',
 'f2000000-0000-4000-8000-000000000002', '테스트 운전자 B', NULL, NULL,
 '2026-11-01 10:00:00', '2026-11-01 10:01:00'),
('f5000000-0000-4000-8000-000000000002', 'f3000000-0000-4000-8000-000000000001',
 'f1000000-0000-4000-8000-000000000001', NULL, 'demo-challenge-002', NULL,
 'revoked', '2026-11-01 09:12:00', NULL, NULL, NULL, NULL,
 '2026-11-01 09:10:00', '2026-11-01 09:20:00');

INSERT INTO trip_user_candidates (id, challenge_id, installation_id, vehicle_id, interval_id, user_id,
    display_name, consent_operation_id, proof_fingerprint, verified_operation_id, status,
    expires_at, verification_expires_at, created_at, updated_at) VALUES
('f8000000-0000-4000-8000-000000000001', 'f5000000-0000-4000-8000-000000000001',
 'f3000000-0000-4000-8000-000000000001', 'f1000000-0000-4000-8000-000000000001',
 'f7000000-0000-4000-8000-000000000002', 'f2000000-0000-4000-8000-000000000002',
 '테스트 운전자 B', 'demo-consent-001',
 X'1111111111111111111111111111111111111111111111111111111111111111',
 'demo-verify-001', 'verified', '2026-11-01 10:05:00', '2026-11-01 10:05:00',
 '2026-11-01 10:01:00', '2026-11-01 10:01:30');

INSERT INTO recording_batches (interval_id, batch_id, payload_hash, accepted_count,
    last_accepted_sequence, received_at) VALUES
('f7000000-0000-4000-8000-000000000001', 'demo-batch-001',
 X'2222222222222222222222222222222222222222222222222222222222222222', 1, 1,
 '2026-11-01 09:11:04'),
('f7000000-0000-4000-8000-000000000002', 'demo-batch-002',
 X'3333333333333333333333333333333333333333333333333333333333333333', 2, 2,
 '2026-11-01 10:00:07');

INSERT INTO recording_samples (interval_id, sequence, batch_id, observed_at, distance_km,
    missing_reason, fuel_liters, fuel_status, received_at, user_id, verified_candidate_id) VALUES
('f7000000-0000-4000-8000-000000000001', 1, 'demo-batch-001', '2026-11-01 09:11:03',
 0.125, NULL, 0.010, 'available', '2026-11-01 09:11:04',
 'f2000000-0000-4000-8000-000000000001', NULL),
('f7000000-0000-4000-8000-000000000002', 1, 'demo-batch-002', '2026-11-01 10:00:03',
 0.080, NULL, NULL, 'unsupported', '2026-11-01 10:00:07', NULL, NULL),
('f7000000-0000-4000-8000-000000000002', 2, 'demo-batch-002', '2026-11-01 10:00:06',
 0.045, NULL, NULL, 'unsupported', '2026-11-01 10:00:07',
 'f2000000-0000-4000-8000-000000000002', 'f8000000-0000-4000-8000-000000000001');

INSERT INTO trip_user_event_state (installation_id, revision) VALUES
('f3000000-0000-4000-8000-000000000001', 2);

INSERT INTO trip_user_events (installation_id, revision, event_type, created_at) VALUES
('f3000000-0000-4000-8000-000000000001', 1, 'candidates_changed', '2026-11-01 10:01:00'),
('f3000000-0000-4000-8000-000000000001', 2, 'candidates_changed', '2026-11-01 10:01:30');
