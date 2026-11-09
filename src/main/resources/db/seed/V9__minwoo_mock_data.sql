-- Mock data for minwoo9594@gmail.com (ce0b8354-c2cb-4549-be7f-5ed6f398083d)

-- 1. Vehicles
INSERT INTO vehicles (id, name, created_by, source_request_id, created_at, updated_at) VALUES
('c1000000-0000-4000-8000-000000000001', '민우의 아이오닉 5', 'ce0b8354-c2cb-4549-be7f-5ed6f398083d', NULL, '2026-11-02 13:00:00', '2026-11-02 13:00:00')
ON DUPLICATE KEY UPDATE name=VALUES(name);

-- 2. Group Membership
INSERT INTO group_members (vehicle_id, user_id, role, created_at) VALUES
('c1000000-0000-4000-8000-000000000001', 'ce0b8354-c2cb-4549-be7f-5ed6f398083d', 'OWNER', '2026-11-02 13:00:00'),
('f1000000-0000-4000-8000-000000000001', 'ce0b8354-c2cb-4549-be7f-5ed6f398083d', 'MEMBER', '2026-11-02 13:05:00')
ON DUPLICATE KEY UPDATE role=VALUES(role);

-- 3. Vehicle Installation
INSERT INTO vehicle_installations (id, vehicle_id, credential_hash, session_version, created_at, updated_at) VALUES
('c3000000-0000-4000-8000-000000000001', 'c1000000-0000-4000-8000-000000000001', NULL, 1, '2026-11-02 13:10:00', '2026-11-02 13:10:00')
ON DUPLICATE KEY UPDATE vehicle_id=VALUES(vehicle_id);

-- 4. Pairing Request
INSERT INTO pairing_requests (id, installation_id, proof_hash, status, expires_at, selected_vehicle_id,
    new_vehicle_name, approved_user_id, issue_key, intent_key, confirmation_code,
    created_at, updated_at, active_installation_id) VALUES
('c4000000-0000-4000-8000-000000000001', 'c3000000-0000-4000-8000-000000000001', NULL,
 'completed', '2026-11-02 13:20:00', 'c1000000-0000-4000-8000-000000000001', NULL,
 'ce0b8354-c2cb-4549-be7f-5ed6f398083d', 'minwoo-pair-001', 'minwoo-intent-001', NULL,
 '2026-11-02 13:10:00', '2026-11-02 13:15:00', NULL)
ON DUPLICATE KEY UPDATE status=VALUES(status);

-- 5. User Session
INSERT INTO trip_user_sessions (id, installation_id, vehicle_id, user_id, display_name, version,
    token_hash, status, expires_at, active_installation_id, created_at, revoked_at) VALUES
('c6000000-0000-4000-8000-000000000001', 'c3000000-0000-4000-8000-000000000001',
 'c1000000-0000-4000-8000-000000000001', 'ce0b8354-c2cb-4549-be7f-5ed6f398083d',
 '민우', 1, NULL, 'active', '2027-11-01 00:00:00', NULL,
 '2026-11-02 13:15:00', NULL)
ON DUPLICATE KEY UPDATE status=VALUES(status);

-- 6. Recording Intervals (Trips)
INSERT INTO recording_intervals (id, installation_id, vehicle_id, user_id, session_version,
    local_interval_id, source, status, started_at, ended_at, finish_operation_id, finish_last_sequence,
    last_accepted_sequence, last_sample_at, last_synced_at, created_at, updated_at) VALUES
('c7000000-0000-4000-8000-000000000001', 'c3000000-0000-4000-8000-000000000001',
 'c1000000-0000-4000-8000-000000000001', 'ce0b8354-c2cb-4549-be7f-5ed6f398083d', 1,
 'minwoo-interval-001', 'manual_app', 'finished', '2026-11-05 08:30:00', '2026-11-05 09:15:00',
 'minwoo-finish-001', 2, 2, '2026-11-05 09:14:55', '2026-11-05 09:15:05',
 '2026-11-05 08:30:00', '2026-11-05 09:15:05'),
('c7000000-0000-4000-8000-000000000002', 'c3000000-0000-4000-8000-000000000001',
 'c1000000-0000-4000-8000-000000000001', 'ce0b8354-c2cb-4549-be7f-5ed6f398083d', 1,
 'minwoo-interval-002', 'manual_app', 'finished', '2026-11-06 18:00:00', '2026-11-06 19:10:00',
 'minwoo-finish-002', 2, 2, '2026-11-06 19:09:50', '2026-11-06 19:10:02',
 '2026-11-06 18:00:00', '2026-11-06 19:10:02')
ON DUPLICATE KEY UPDATE status=VALUES(status);

-- 7. Trip Challenges and Candidates
INSERT INTO trip_user_challenges (id, installation_id, vehicle_id, interval_id, operation_id, proof_hash,
    status, expires_at, approved_user_id, display_name, session_id, active_installation_id,
    created_at, updated_at) VALUES
('c5000000-0000-4000-8000-000000000001', 'c3000000-0000-4000-8000-000000000001',
 'c1000000-0000-4000-8000-000000000001', 'c7000000-0000-4000-8000-000000000001',
 'minwoo-challenge-001', NULL, 'approved', '2026-11-05 08:35:00',
 'ce0b8354-c2cb-4549-be7f-5ed6f398083d', '민우', NULL, NULL,
 '2026-11-05 08:30:00', '2026-11-05 08:31:00'),
('c5000000-0000-4000-8000-000000000002', 'c3000000-0000-4000-8000-000000000001',
 'c1000000-0000-4000-8000-000000000001', 'c7000000-0000-4000-8000-000000000002',
 'minwoo-challenge-002', NULL, 'approved', '2026-11-06 18:05:00',
 'ce0b8354-c2cb-4549-be7f-5ed6f398083d', '민우', NULL, NULL,
 '2026-11-06 18:00:00', '2026-11-06 18:01:00')
ON DUPLICATE KEY UPDATE status=VALUES(status);

INSERT INTO trip_user_candidates (id, challenge_id, installation_id, vehicle_id, interval_id, user_id,
    display_name, consent_operation_id, proof_fingerprint, verified_operation_id, status,
    expires_at, verification_expires_at, created_at, updated_at) VALUES
('c8000000-0000-4000-8000-000000000001', 'c5000000-0000-4000-8000-000000000001',
 'c3000000-0000-4000-8000-000000000001', 'c1000000-0000-4000-8000-000000000001',
 'c7000000-0000-4000-8000-000000000001', 'ce0b8354-c2cb-4549-be7f-5ed6f398083d',
 '민우', 'minwoo-consent-001',
 X'4444444444444444444444444444444444444444444444444444444444444444',
 'minwoo-verify-001', 'verified', '2027-11-01 00:00:00', '2027-11-01 00:00:00',
 '2026-11-05 08:31:00', '2026-11-05 08:31:30'),
('c8000000-0000-4000-8000-000000000002', 'c5000000-0000-4000-8000-000000000002',
 'c3000000-0000-4000-8000-000000000001', 'c1000000-0000-4000-8000-000000000001',
 'c7000000-0000-4000-8000-000000000002', 'ce0b8354-c2cb-4549-be7f-5ed6f398083d',
 '민우', 'minwoo-consent-002',
 X'5555555555555555555555555555555555555555555555555555555555555555',
 'minwoo-verify-002', 'verified', '2027-11-01 00:00:00', '2027-11-01 00:00:00',
 '2026-11-06 18:01:00', '2026-11-06 18:01:30')
ON DUPLICATE KEY UPDATE status=VALUES(status);

-- 8. Recording Batches and Samples
INSERT INTO recording_batches (interval_id, batch_id, payload_hash, accepted_count,
    last_accepted_sequence, received_at) VALUES
('c7000000-0000-4000-8000-000000000001', 'minwoo-batch-001',
 X'6666666666666666666666666666666666666666666666666666666666666666', 2, 2, '2026-11-05 09:15:05'),
('c7000000-0000-4000-8000-000000000002', 'minwoo-batch-002',
 X'7777777777777777777777777777777777777777777777777777777777777777', 2, 2, '2026-11-06 19:10:02')
ON DUPLICATE KEY UPDATE last_accepted_sequence=VALUES(last_accepted_sequence);

INSERT INTO recording_samples (interval_id, sequence, batch_id, observed_at, distance_km,
    missing_reason, fuel_liters, fuel_status, received_at, user_id, verified_candidate_id) VALUES
('c7000000-0000-4000-8000-000000000001', 1, 'minwoo-batch-001', '2026-11-05 08:50:00',
 16.200, NULL, 1.300, 'available', '2026-11-05 09:15:05',
 'ce0b8354-c2cb-4549-be7f-5ed6f398083d', 'c8000000-0000-4000-8000-000000000001'),
('c7000000-0000-4000-8000-000000000001', 2, 'minwoo-batch-001', '2026-11-05 09:14:55',
 16.300, NULL, 1.300, 'available', '2026-11-05 09:15:05',
 'ce0b8354-c2cb-4549-be7f-5ed6f398083d', 'c8000000-0000-4000-8000-000000000001'),
('c7000000-0000-4000-8000-000000000002', 1, 'minwoo-batch-002', '2026-11-06 18:35:00',
 24.100, NULL, 1.850, 'available', '2026-11-06 19:10:02',
 'ce0b8354-c2cb-4549-be7f-5ed6f398083d', 'c8000000-0000-4000-8000-000000000002'),
('c7000000-0000-4000-8000-000000000002', 2, 'minwoo-batch-002', '2026-11-06 19:09:50',
 24.100, NULL, 1.850, 'available', '2026-11-06 19:10:02',
 'ce0b8354-c2cb-4549-be7f-5ed6f398083d', 'c8000000-0000-4000-8000-000000000002')
ON DUPLICATE KEY UPDATE distance_km=VALUES(distance_km);

-- 9. Trip Summaries
INSERT INTO trip_summaries (installation_id, trip_id, vehicle_id, user_id, consent_id, revision, payload_hash, payload, synced_at) VALUES
('c3000000-0000-4000-8000-000000000001', 'c7000000-0000-4000-8000-000000000001', 'c1000000-0000-4000-8000-000000000001',
 'ce0b8354-c2cb-4549-be7f-5ed6f398083d', 'c8000000-0000-4000-8000-000000000001', 1,
 X'8888888888888888888888888888888888888888888888888888888888888888',
 '{"tripId":"c7000000-0000-4000-8000-000000000001","revision":1,"syncedAt":"2026-11-05T09:15:05Z","startedAt":"2026-11-05T08:30:00Z","endedAt":"2026-11-05T09:15:00Z","distanceKm":{"value":32.500,"status":"available","reason":null},"fuelConsumedLiters":{"value":2.600,"status":"available","reason":null},"fuelEfficiencyKmPerLiter":{"value":12.500,"status":"available","reason":null},"estimatedCost":{"value":4368.00,"currency":"KRW","status":"available","reason":null},"priceSource":"오피넷 전국 평균가","priceObservedAt":"2026-11-05T00:00:00Z","quality":{"missing":false,"endEstimated":false,"refueled":false,"ownerChanged":false,"measurementSource":"client_reported"}}',
 '2026-11-05 09:15:05'),
('c3000000-0000-4000-8000-000000000001', 'c7000000-0000-4000-8000-000000000002', 'c1000000-0000-4000-8000-000000000001',
 'ce0b8354-c2cb-4549-be7f-5ed6f398083d', 'c8000000-0000-4000-8000-000000000002', 1,
 X'9999999999999999999999999999999999999999999999999999999999999999',
 '{"tripId":"c7000000-0000-4000-8000-000000000002","revision":1,"syncedAt":"2026-11-06T19:10:02Z","startedAt":"2026-11-06T18:00:00Z","endedAt":"2026-11-06T19:10:00Z","distanceKm":{"value":48.200,"status":"available","reason":null},"fuelConsumedLiters":{"value":3.700,"status":"available","reason":null},"fuelEfficiencyKmPerLiter":{"value":13.027,"status":"available","reason":null},"estimatedCost":{"value":6216.00,"currency":"KRW","status":"available","reason":null},"priceSource":"오피넷 전국 평균가","priceObservedAt":"2026-11-06T00:00:00Z","quality":{"missing":false,"endEstimated":false,"refueled":false,"ownerChanged":false,"measurementSource":"client_reported"}}',
 '2026-11-06 19:10:02')
ON DUPLICATE KEY UPDATE payload=VALUES(payload);

-- 10. Mobile Trip Inbox
INSERT INTO mobile_trip_inbox (recipient_id, trip_id, summary_id, trip_revision, snapshot, delivered_at, read_at)
SELECT 'ce0b8354-c2cb-4549-be7f-5ed6f398083d', t.trip_id, t.id, t.revision, t.payload,
       '2026-11-05 09:15:10', '2026-11-05 10:00:00'
FROM trip_summaries t
WHERE t.trip_id = 'c7000000-0000-4000-8000-000000000001'
ON DUPLICATE KEY UPDATE snapshot=VALUES(snapshot);

INSERT INTO mobile_trip_inbox (recipient_id, trip_id, summary_id, trip_revision, snapshot, delivered_at, read_at)
SELECT 'ce0b8354-c2cb-4549-be7f-5ed6f398083d', t.trip_id, t.id, t.revision, t.payload,
       '2026-11-06 19:10:05', NULL
FROM trip_summaries t
WHERE t.trip_id = 'c7000000-0000-4000-8000-000000000002'
ON DUPLICATE KEY UPDATE snapshot=VALUES(snapshot);

-- 11. Maintenance Plan & Vehicle Fuel Metadata
INSERT INTO maintenance_plans (id, vehicle_id, owner_user_id, name, shared, active, basis, interval_km, interval_months, revision, last_service_date, last_service_km, next_due_date, next_due_km, created_at, updated_at) VALUES
('d1000000-0000-4000-8000-000000000001', 'c1000000-0000-4000-8000-000000000001', 'ce0b8354-c2cb-4549-be7f-5ed6f398083d',
 '에어컨 필터 교체 및 기본 점검', 1, 1, 'MANUFACTURER', 15000.000, 12, 1, '2026-05-10', 10000.000, '2027-05-10', 25000.000, '2026-11-02 13:00:00', '2026-11-02 13:00:00')
ON DUPLICATE KEY UPDATE name=VALUES(name);

INSERT INTO vehicle_fuel_metadata (vehicle_id, capacity_liters, capacity_verified_at, capacity_source, unit_price, price_at, price_valid_until, price_source) VALUES
('c1000000-0000-4000-8000-000000000001', 60.000, '2026-11-02 13:00:00', '제조사 공식 제원표', 1680.000, 1793700000, 1794304800, '오피넷 전국 평균가')
ON DUPLICATE KEY UPDATE unit_price=VALUES(unit_price);
