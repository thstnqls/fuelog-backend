# Fuelog 데이터베이스 테이블·컬럼 설명

기준: 2026-11-08, Flyway V1~V12의 최종 스키마 및 현재 저장소·서비스 구현. 업무 테이블 **30개**, 컬럼 **286개**.

DB COMMENT는 `V13__document_tables_and_columns.sql`에서 추가한다. 기존 마이그레이션은 수정하지 않는다. 이미 생성된 DB에는 V13을 적용해야 COMMENT가 표시된다. 이 문서는 실제 연결 DB를 조회한 목록이 아닌 저장소 스키마 기준이다.

## 공통 규칙

- `DATETIME(6)`은 UTC로 저장한다. 한국 시간 표시는 조회 시 UTC에 9시간을 더해 변환한다.
- `BIGINT` 시각 컬럼은 각 설명에 표시한 UTC epoch milliseconds다. 모든 BIGINT가 시각은 아니다.
- `DATE`는 날짜만 표현한다. 거리 단위는 km, 연료량은 L이다.
- Supabase 사용자 UUID에는 이 DB의 사용자 테이블 외래키가 없다. 차량 멤버십은 `group_members`에서 검사한다.
- `vehicle_installations.id`는 차량 ID가 아닌 앱 설치 ID다. `vehicle_id`로 실제 차량과 연결한다.
- 신규 앱은 완료된 QR 승인에 따른 사용자 연결을 유지한다. `trip_user_sessions`와 구간 후보 관련 테이블은 이전 클라이언트 호환에도 사용된다.
- `NULL`은 컬럼별 미확인·미보고·미연결 등의 의미다. 수치 0이나 FALSE와 동일하게 취급하지 않는다.
- `flyway_schema_history`는 Flyway가 생성·관리하는 이력 테이블이다. 업무 테이블 수와 COMMENT 변경 대상에서 제외한다.

## 테이블 목록과 용도

| 테이블 | 컬럼 수 | 용도 |
|---|---:|---|
| [vehicles](#vehicles) | 6 | 차량 기본 정보. 앱 설치와 독립적으로 유지되는 차량 식별자와 표시 이름 |
| [group_members](#group_members) | 4 | 차량별 사용자 멤버십과 OWNER·MANAGER·MEMBER 권한 |
| [vehicle_installations](#vehicle_installations) | 10 | 장치 앱 설치별 인증과 차량 연결 상태. 재설치하면 별도 설치로 등록 |
| [pairing_requests](#pairing_requests) | 14 | 차량 연결 QR 요청과 모바일 승인·차량 화면 확인 이력. 완료된 승인으로 현재 사용자 식별 |
| [trip_user_challenges](#trip_user_challenges) | 14 | 사용자 확인 QR 요청과 승인 이력. 이전 개인 홈 세션·구간 동의 흐름과 호환 |
| [trip_user_sessions](#trip_user_sessions) | 12 | 이전 클라이언트용 30분 사용자 세션과 토큰 해시·폐기 상태 |
| [recording_intervals](#recording_intervals) | 17 | 수동 운행 기록 구간. 시작 시 소유자를 고정하고 샘플 수신·종료 상태 관리 |
| [recording_batches](#recording_batches) | 6 | 운행 샘플 배치 수신 이력. 같은 배치 재전송의 중복 저장과 내용 충돌 방지 |
| [recording_samples](#recording_samples) | 11 | 운행 구간의 거리 증가량·연료 사용량·누락 및 샘플별 사용자 귀속 |
| [trip_user_candidates](#trip_user_candidates) | 15 | 이전 구간별 사용자 확인용 모바일 동의 후보와 차량 화면 확인 결과 |
| [trip_user_event_state](#trip_user_event_state) | 2 | 설치별 사용자 후보 변경 이벤트의 최신 revision |
| [trip_user_events](#trip_user_events) | 4 | 개인정보를 제외한 사용자 후보·세션 변경 이벤트와 조회 커서 |
| [trip_summaries](#trip_summaries) | 10 | 설치·운행별 요약 원본 JSON과 소유자·revision·서버 저장 확인 정보 |
| [mobile_trip_inbox](#mobile_trip_inbox) | 10 | 사용자별 운행 요약·주유 후보 확인함과 읽음 상태 |
| [vehicle_maintenance_state](#vehicle_maintenance_state) | 2 | 차량 정비 데이터 변경을 식별하는 요약 revision |
| [vehicle_maintenance_profiles](#vehicle_maintenance_profiles) | 6 | 서버가 검증한 제조사·차종·연식. 제조사 정비 근거 적용 여부 판정 |
| [maintenance_manufacturer_evidence](#maintenance_manufacturer_evidence) | 14 | 제조사 정비 주기의 원본 문서·차종·연식·적용 범위와 검증 근거 |
| [maintenance_plans](#maintenance_plans) | 17 | 차량 공동 또는 사용자 개인 정비 주기와 마지막 정비·다음 예정 |
| [maintenance_events](#maintenance_events) | 16 | 정비 확정·방문 추정 이력과 상세 JSON. 논리 삭제·중복 요청 관리 |
| [maintenance_event_audit](#maintenance_event_audit) | 8 | 정비 이력 생성·수정·삭제의 행위자와 변경 전후 감사 기록 |
| [vehicle_odometer_samples](#vehicle_odometer_samples) | 11 | 장치에서 보고한 차량 원본 누적거리와 품질·중복 요청 검증 정보 |
| [fuel_candidates](#fuel_candidates) | 19 | 연료 증가로 감지한 주유 후보의 증거·서버 추정값·사용자 확인 및 보정값 |
| [vehicle_fuel_metadata](#vehicle_fuel_metadata) | 8 | 서버가 검증한 연료탱크 용량과 단가·유효기간. 주유량·금액 추정 근거 |
| [fuel_candidate_operations](#fuel_candidate_operations) | 8 | 주유 후보 확인·기각·보정 요청과 응답 보관. 재시도 중복 방지 및 감사 |
| [connection_operations](#connection_operations) | 13 | 사용자 해제·차량 초기화 요청과 최초 대상·등록 버전·관리자 결정 이력 |
| [connection_reauth_uses](#connection_reauth_uses) | 5 | 차량 초기화에 소비한 OTP·TOTP 인증 사건. JWT 갱신을 통한 증명 재사용 방지 |
| [installation_capabilities](#installation_capabilities) | 6 | 설치의 현재 차량 등록 버전에 대한 연료·거리·위치 SDK 기능 보고 |
| [recommendation_registrations](#recommendation_registrations) | 6 | 서버가 확인한 설치·차량 등록 버전별 추천 기능 등록·검증 상태 |
| [recommendation_consents](#recommendation_consents) | 6 | 설치·차량 등록 버전·사용자별 추천 기능 동의와 활성화 선택 |
| [installation_sync_checkpoints](#installation_sync_checkpoints) | 6 | 서버가 검증한 설치·차량 등록 버전·사용자별 큐 완료 확인 지점 |

## vehicles

차량 기본 정보. 앱 설치와 독립적으로 유지되는 차량 식별자와 표시 이름.

최초 생성: `V1__create_pairing_tables.sql`. 타입·기본값은 V12까지 반영한 최종 정의다.

| 컬럼 | 타입 | NULL 허용 | 기본값·자동 증가 | 설명 |
|---|---|:---:|---|---|
| `id` | `CHAR(36)` | 아니오 | — | 이 행을 식별하는 UUID |
| `name` | `VARCHAR(100)` | 아니오 | — | 차량 표시 이름 또는 별칭 |
| `created_by` | `CHAR(36)` | 아니오 | — | 차량을 생성한 Supabase 사용자 UUID |
| `source_request_id` | `CHAR(36)` | 예 | — | 차량 생성에 사용한 페어링 요청 UUID. pairing_requests.id 참조이며 중복 차량 생성 방지 |
| `created_at` | `DATETIME(6)` | 아니오 | — | 행 생성 시각. UTC DATETIME(6) |
| `updated_at` | `DATETIME(6)` | 아니오 | — | 행의 마지막 변경 시각. UTC DATETIME(6) |

## group_members

차량별 사용자 멤버십과 OWNER·MANAGER·MEMBER 권한.

최초 생성: `V1__create_pairing_tables.sql`. 타입·기본값은 V12까지 반영한 최종 정의다.

| 컬럼 | 타입 | NULL 허용 | 기본값·자동 증가 | 설명 |
|---|---|:---:|---|---|
| `vehicle_id` | `CHAR(36)` | 아니오 | — | 차량 UUID. vehicles.id 참조 |
| `user_id` | `CHAR(36)` | 아니오 | — | 데이터 주체의 Supabase 사용자 UUID |
| `role` | `VARCHAR(20)` | 아니오 | — | 차량 권한. OWNER=소유자, MANAGER=관리자, MEMBER=일반 구성원 |
| `created_at` | `DATETIME(6)` | 아니오 | — | 행 생성 시각. UTC DATETIME(6) |

## vehicle_installations

장치 앱 설치별 인증과 차량 연결 상태. 재설치하면 별도 설치로 등록.

최초 생성: `V1__create_pairing_tables.sql`. 타입·기본값은 V12까지 반영한 최종 정의다.

| 컬럼 | 타입 | NULL 허용 | 기본값·자동 증가 | 설명 |
|---|---|:---:|---|---|
| `id` | `CHAR(36)` | 아니오 | — | 앱 설치 UUID. 차량 ID와 다르며 재설치 후 새로 발급됨 |
| `vehicle_id` | `CHAR(36)` | 예 | — | 연결 차량 UUID. vehicles.id 참조이며 미연결 시 NULL. 차량당 연결 설치 최대 1개 |
| `created_at` | `DATETIME(6)` | 아니오 | — | 행 생성 시각. UTC DATETIME(6) |
| `updated_at` | `DATETIME(6)` | 아니오 | — | 행의 마지막 변경 시각. UTC DATETIME(6) |
| `credential_hash` | `BINARY(32)` | 예 | — | 설치 비밀값의 SHA-256 해시(32바이트). 비밀값·모바일 JWT 원문은 저장하지 않음 |
| `session_version` | `BIGINT` | 아니오 | 0 | 사용자 세션 폐기 판정용 설치 세션 버전 |
| `registration_version` | `BIGINT` | 아니오 | 0 | 설치와 차량의 연결 변경 때 증가하는 버전. 이전 연결의 요청·설정 재사용 방지 |
| `personal_access_blocked` | `BOOLEAN` | 아니오 | FALSE | 연결 해제·초기화 요청 후 개인 접근·수집 차단 여부. 재승인 시 해제 |
| `access_blocked_at` | `DATETIME(6)` | 예 | — | 개인 접근 차단 경계 시각(UTC). 이 시각 이후 새 사용자 증명을 요구 |
| `device_alias` | `VARCHAR(100)` | 예 | — | 장치에 표시할 별칭. 미설정이면 NULL |

## pairing_requests

차량 연결 QR 요청과 모바일 승인·차량 화면 확인 이력. 완료된 승인으로 현재 사용자 식별.

최초 생성: `V1__create_pairing_tables.sql`. 타입·기본값은 V12까지 반영한 최종 정의다.

| 컬럼 | 타입 | NULL 허용 | 기본값·자동 증가 | 설명 |
|---|---|:---:|---|---|
| `id` | `CHAR(36)` | 아니오 | — | 이 행을 식별하는 UUID |
| `installation_id` | `CHAR(36)` | 아니오 | — | 장치 앱 설치 UUID. vehicle_installations.id 참조 |
| `proof_hash` | `BINARY(32)` | 예 | — | 차량 연결 QR의 일회용 proof 또는 nonce SHA-256 해시. 소비·폐기 후 NULL |
| `status` | `VARCHAR(40)` | 아니오 | — | pending=모바일 대기, awaiting_vehicle_confirmation=차량 확인 대기, completed=완료, expired=만료, revoked=폐기 |
| `expires_at` | `DATETIME(6)` | 아니오 | — | 연결 QR 요청 만료 시각(UTC). 완료된 사용자 연결의 만료 시각이 아님 |
| `selected_vehicle_id` | `CHAR(36)` | 예 | — | 모바일이 선택하거나 연결 완료 시 생성된 차량 UUID. vehicles.id 참조 |
| `new_vehicle_name` | `VARCHAR(100)` | 예 | — | 기존 페어링에서 새 차량 생성 선택 시 입력한 이름 |
| `approved_user_id` | `CHAR(36)` | 예 | — | 연결에 동의한 Supabase 사용자 UUID. 현재 차량의 완료된 승인에서 사용자 식별에 사용 |
| `issue_key` | `VARCHAR(100)` | 아니오 | — | 설치 내 QR 발급 재시도 키. 설치 ID와 조합해 유일 |
| `intent_key` | `VARCHAR(100)` | 예 | — | 모바일 연결 의사 제출 또는 연결 승인의 재시도 키 |
| `confirmation_code` | `CHAR(6)` | 예 | — | 기존 페어링의 모바일·차량 화면 대조용 6자리 코드. 모바일 의사 접수 전 NULL |
| `created_at` | `DATETIME(6)` | 아니오 | — | 행 생성 시각. UTC DATETIME(6) |
| `updated_at` | `DATETIME(6)` | 아니오 | — | 행의 마지막 변경 시각. UTC DATETIME(6) |
| `active_installation_id` | `CHAR(36)` | 예 | — | 활성 요청·세션에만 설치 UUID를 저장하는 유일성 제어 필드. 비활성 상태는 NULL |

## trip_user_challenges

사용자 확인 QR 요청과 승인 이력. 이전 개인 홈 세션·구간 동의 흐름과 호환.

최초 생성: `V5__vehicle_home_and_recording.sql`. 타입·기본값은 V12까지 반영한 최종 정의다.

| 컬럼 | 타입 | NULL 허용 | 기본값·자동 증가 | 설명 |
|---|---|:---:|---|---|
| `id` | `CHAR(36)` | 아니오 | — | 이 행을 식별하는 UUID |
| `installation_id` | `CHAR(36)` | 아니오 | — | 장치 앱 설치 UUID. vehicle_installations.id 참조 |
| `vehicle_id` | `CHAR(36)` | 아니오 | — | 차량 UUID. vehicles.id 참조 |
| `operation_id` | `VARCHAR(100)` | 아니오 | — | 설치 또는 대상 내에서 같은 요청 재시도에 유지하는 고유 키 |
| `proof_hash` | `BINARY(32)` | 예 | — | 사용자 확인 QR 일회용 비밀값의 SHA-256 해시. 승인·폐기 후 NULL |
| `status` | `VARCHAR(20)` | 아니오 | — | pending=승인 대기, approved=승인 완료, expired=요청 만료, revoked=폐기 |
| `expires_at` | `DATETIME(6)` | 아니오 | — | 요청 또는 권한의 만료 시각. UTC |
| `approved_user_id` | `CHAR(36)` | 예 | — | 사용자 확인 QR에 동의한 Supabase 사용자 UUID |
| `display_name` | `VARCHAR(100)` | 예 | — | 사용자 확인 당시 표시 이름. 인증 수단으로 사용하지 않음 |
| `session_id` | `CHAR(36)` | 예 | — | 이전 개인 홈 승인으로 발급한 trip_user_sessions.id. 구간 동의 요청에는 NULL 가능 |
| `active_installation_id` | `CHAR(36)` | 예 | — | 활성 요청·세션에만 설치 UUID를 저장하는 유일성 제어 필드. 비활성 상태는 NULL |
| `created_at` | `DATETIME(6)` | 아니오 | — | 행 생성 시각. UTC DATETIME(6) |
| `updated_at` | `DATETIME(6)` | 아니오 | — | 행의 마지막 변경 시각. UTC DATETIME(6) |
| `interval_id` | `CHAR(36)` | 예 | — | 구간별 동의 대상 recording_intervals.id. 이전 개인 홈 확인은 NULL |

## trip_user_sessions

이전 클라이언트용 30분 사용자 세션과 토큰 해시·폐기 상태.

최초 생성: `V5__vehicle_home_and_recording.sql`. 타입·기본값은 V12까지 반영한 최종 정의다.

| 컬럼 | 타입 | NULL 허용 | 기본값·자동 증가 | 설명 |
|---|---|:---:|---|---|
| `id` | `CHAR(36)` | 아니오 | — | 이 행을 식별하는 UUID |
| `installation_id` | `CHAR(36)` | 아니오 | — | 장치 앱 설치 UUID. vehicle_installations.id 참조 |
| `vehicle_id` | `CHAR(36)` | 아니오 | — | 차량 UUID. vehicles.id 참조 |
| `user_id` | `CHAR(36)` | 아니오 | — | 데이터 주체의 Supabase 사용자 UUID |
| `display_name` | `VARCHAR(100)` | 아니오 | — | 사용자 확인 당시 표시 이름. 인증 수단으로 사용하지 않음 |
| `version` | `BIGINT` | 아니오 | — | 발급 시점 설치 session_version. 현재 버전과 다르면 폐기된 세션으로 판정 |
| `token_hash` | `BINARY(32)` | 예 | — | 이전 사용자 세션 scopedToken의 SHA-256 해시. 토큰은 장치 조회 시 파생하며 원문은 저장하지 않음 |
| `status` | `VARCHAR(20)` | 아니오 | — | 이전 사용자 세션 상태. active=활성, revoked=폐기 |
| `expires_at` | `DATETIME(6)` | 아니오 | — | 이전 사용자 세션의 만료 시각(UTC). 발급 후 30분 |
| `active_installation_id` | `CHAR(36)` | 예 | — | 활성 요청·세션에만 설치 UUID를 저장하는 유일성 제어 필드. 비활성 상태는 NULL |
| `created_at` | `DATETIME(6)` | 아니오 | — | 행 생성 시각. UTC DATETIME(6) |
| `revoked_at` | `DATETIME(6)` | 예 | — | 검증 또는 권한 폐기 시각. UTC이며 미폐기 시 NULL |

## recording_intervals

수동 운행 기록 구간. 시작 시 소유자를 고정하고 샘플 수신·종료 상태 관리.

최초 생성: `V5__vehicle_home_and_recording.sql`. 타입·기본값은 V12까지 반영한 최종 정의다.

| 컬럼 | 타입 | NULL 허용 | 기본값·자동 증가 | 설명 |
|---|---|:---:|---|---|
| `id` | `CHAR(36)` | 아니오 | — | 이 행을 식별하는 UUID |
| `installation_id` | `CHAR(36)` | 아니오 | — | 장치 앱 설치 UUID. vehicle_installations.id 참조 |
| `vehicle_id` | `CHAR(36)` | 아니오 | — | 차량 UUID. vehicles.id 참조 |
| `user_id` | `CHAR(36)` | 예 | — | 구간 시작 때 고정한 소유자의 Supabase UUID. 익명 구간은 NULL이며 자동 재귀속하지 않음 |
| `session_version` | `BIGINT` | 예 | — | 구간 시작 시 이전 사용자 세션 버전. 세션 없이 시작한 경우 NULL 가능 |
| `local_interval_id` | `VARCHAR(100)` | 아니오 | — | 장치 로컬 구간 식별자. 설치 내 유일하며 시작 재시도 중복 방지 |
| `source` | `VARCHAR(20)` | 아니오 | — | 운행 시작 출처. 현재 manual_app=앱 수동 기록만 허용 |
| `status` | `VARCHAR(20)` | 아니오 | — | recording=기록 중, finished=종료 |
| `started_at` | `DATETIME(6)` | 아니오 | — | 운행 기록 시작 시각. UTC |
| `ended_at` | `DATETIME(6)` | 예 | — | 운행 기록 종료 시각. UTC이며 진행 중이면 NULL |
| `finish_operation_id` | `VARCHAR(100)` | 예 | — | 종료 요청 재시도 키. 강제 종료·요약 업로드 경로의 서버 키도 저장 |
| `finish_last_sequence` | `BIGINT` | 예 | — | 종료 요청이 선언한 기기의 마지막 샘플 번호. 종료 전에는 NULL |
| `last_accepted_sequence` | `BIGINT` | 아니오 | 0 | 서버가 수락한 최대 샘플 번호. 중간 번호 누락이 없다는 뜻은 아님 |
| `last_sample_at` | `DATETIME(6)` | 예 | — | 수신한 샘플 중 가장 늦은 관측 시각. UTC |
| `last_synced_at` | `DATETIME(6)` | 예 | — | 구간 샘플을 마지막으로 서버에 저장한 시각. UTC |
| `created_at` | `DATETIME(6)` | 아니오 | — | 행 생성 시각. UTC DATETIME(6) |
| `updated_at` | `DATETIME(6)` | 아니오 | — | 행의 마지막 변경 시각. UTC DATETIME(6) |

## recording_batches

운행 샘플 배치 수신 이력. 같은 배치 재전송의 중복 저장과 내용 충돌 방지.

최초 생성: `V5__vehicle_home_and_recording.sql`. 타입·기본값은 V12까지 반영한 최종 정의다.

| 컬럼 | 타입 | NULL 허용 | 기본값·자동 증가 | 설명 |
|---|---|:---:|---|---|
| `interval_id` | `CHAR(36)` | 아니오 | — | 운행 기록 구간 UUID. recording_intervals.id 참조 |
| `batch_id` | `VARCHAR(100)` | 아니오 | — | 설치가 구간 내 같은 샘플 묶음 재전송에 사용하는 고유 키 |
| `payload_hash` | `BINARY(32)` | 아니오 | — | 재전송 내용의 동일성 비교용 SHA-256 해시. 원문 토큰이 아님 |
| `accepted_count` | `INT` | 아니오 | — | 최초 배치에서 수락한 샘플 개수 |
| `last_accepted_sequence` | `BIGINT` | 아니오 | — | 이 배치 수신 후 구간 전체에서 수락한 최대 샘플 번호 |
| `received_at` | `DATETIME(6)` | 아니오 | — | 서버 수신 시각. UTC |

## recording_samples

운행 구간의 거리 증가량·연료 사용량·누락 및 샘플별 사용자 귀속.

최초 생성: `V5__vehicle_home_and_recording.sql`. 타입·기본값은 V12까지 반영한 최종 정의다.

| 컬럼 | 타입 | NULL 허용 | 기본값·자동 증가 | 설명 |
|---|---|:---:|---|---|
| `interval_id` | `CHAR(36)` | 아니오 | — | 운행 기록 구간 UUID. recording_intervals.id 참조 |
| `sequence` | `BIGINT` | 아니오 | — | 구간 내 샘플 번호. interval_id와 조합해 기본키 |
| `batch_id` | `VARCHAR(100)` | 아니오 | — | 이 샘플을 수신한 배치 식별값 |
| `observed_at` | `DATETIME(6)` | 아니오 | — | 장치에서 샘플을 관측한 시각. UTC |
| `distance_km` | `DECIMAL(12,3)` | 예 | — | 이 샘플에서 증가한 거리(km). 차량 전체 누적거리가 아니며 누락이면 NULL |
| `missing_reason` | `VARCHAR(40)` | 예 | — | 거리 측정 누락 이유. distance_km와 정확히 하나만 저장 |
| `fuel_liters` | `DECIMAL(10,3)` | 예 | — | 이 샘플의 연료 사용량(L). 미지원·오류이면 NULL |
| `fuel_status` | `VARCHAR(20)` | 아니오 | — | available=유효 연료값, unsupported=미지원·미보고, error=잘못된 연료값 |
| `received_at` | `DATETIME(6)` | 아니오 | — | 서버 수신 시각. UTC |
| `user_id` | `CHAR(36)` | 예 | — | 샘플별 소유자 UUID. NULL이면 구간 소유자를 기준으로 조회하며 익명 기록은 소급 귀속하지 않음 |
| `verified_candidate_id` | `CHAR(36)` | 예 | — | 이전 구간 확인 흐름에서 검증한 trip_user_candidates.id. 후보 헤더 없이 저장하면 NULL |

## trip_user_candidates

이전 구간별 사용자 확인용 모바일 동의 후보와 차량 화면 확인 결과.

최초 생성: `V6__trip_user_candidates.sql`. 타입·기본값은 V12까지 반영한 최종 정의다.

| 컬럼 | 타입 | NULL 허용 | 기본값·자동 증가 | 설명 |
|---|---|:---:|---|---|
| `id` | `CHAR(36)` | 아니오 | — | 이 행을 식별하는 UUID |
| `challenge_id` | `CHAR(36)` | 아니오 | — | 사용자 확인 QR 요청 UUID. trip_user_challenges.id 참조 |
| `installation_id` | `CHAR(36)` | 아니오 | — | 장치 앱 설치 UUID. vehicle_installations.id 참조 |
| `vehicle_id` | `CHAR(36)` | 아니오 | — | 차량 UUID. vehicles.id 참조 |
| `interval_id` | `CHAR(36)` | 아니오 | — | 운행 기록 구간 UUID. recording_intervals.id 참조 |
| `user_id` | `CHAR(36)` | 아니오 | — | 데이터 주체의 Supabase 사용자 UUID |
| `display_name` | `VARCHAR(100)` | 아니오 | — | 사용자 확인 당시 표시 이름. 인증 수단으로 사용하지 않음 |
| `consent_operation_id` | `VARCHAR(100)` | 아니오 | — | 설치 내 모바일 구간 동의 재시도 키 |
| `proof_fingerprint` | `BINARY(32)` | 아니오 | — | 모바일 동의에 사용한 QR proof의 SHA-256 해시. 동의 재시도 동일성 확인 |
| `verified_operation_id` | `VARCHAR(100)` | 예 | — | 차량 화면 사용자 선택 확인 재시도 키. 미확인 시 NULL |
| `status` | `VARCHAR(20)` | 아니오 | — | active=선택 대기, verified=차량 확인 완료, revoked=철회·폐기, expired=만료 |
| `expires_at` | `DATETIME(6)` | 아니오 | — | 모바일 구간 동의 후보 만료 시각(UTC). 생성 후 5분 |
| `verification_expires_at` | `DATETIME(6)` | 예 | — | 차량에서 확인한 신규 기록 권한 만료 시각(UTC). 미확인 시 NULL |
| `created_at` | `DATETIME(6)` | 아니오 | — | 행 생성 시각. UTC DATETIME(6) |
| `updated_at` | `DATETIME(6)` | 아니오 | — | 행의 마지막 변경 시각. UTC DATETIME(6) |

## trip_user_event_state

설치별 사용자 후보 변경 이벤트의 최신 revision.

최초 생성: `V6__trip_user_candidates.sql`. 타입·기본값은 V12까지 반영한 최종 정의다.

| 컬럼 | 타입 | NULL 허용 | 기본값·자동 증가 | 설명 |
|---|---|:---:|---|---|
| `installation_id` | `CHAR(36)` | 아니오 | — | 장치 앱 설치 UUID. vehicle_installations.id 참조 |
| `revision` | `BIGINT` | 아니오 | 0 | 설치별 가장 최근에 발행한 사용자 변경 이벤트 번호. 기본값 0 |

## trip_user_events

개인정보를 제외한 사용자 후보·세션 변경 이벤트와 조회 커서.

최초 생성: `V6__trip_user_candidates.sql`. 타입·기본값은 V12까지 반영한 최종 정의다.

| 컬럼 | 타입 | NULL 허용 | 기본값·자동 증가 | 설명 |
|---|---|:---:|---|---|
| `installation_id` | `CHAR(36)` | 아니오 | — | 장치 앱 설치 UUID. vehicle_installations.id 참조 |
| `revision` | `BIGINT` | 아니오 | — | 설치별 증가하는 변경 이벤트 번호. 클라이언트 cursor로 사용 |
| `event_type` | `VARCHAR(30)` | 아니오 | — | 개인정보 없는 변경 종류. candidates_changed=후보 변경, session_changed=세션 변경 |
| `created_at` | `DATETIME(6)` | 아니오 | — | 행 생성 시각. UTC DATETIME(6) |

## trip_summaries

설치·운행별 요약 원본 JSON과 소유자·revision·서버 저장 확인 정보.

최초 생성: `V8__trip_summaries_and_mobile_inbox.sql`. 타입·기본값은 V12까지 반영한 최종 정의다.

| 컬럼 | 타입 | NULL 허용 | 기본값·자동 증가 | 설명 |
|---|---|:---:|---|---|
| `id` | `BIGINT` | 아니오 | AUTO_INCREMENT | 서버 내부 자동 증가 식별 번호 |
| `installation_id` | `CHAR(36)` | 아니오 | — | 장치 앱 설치 UUID. vehicle_installations.id 참조 |
| `trip_id` | `CHAR(36)` | 아니오 | — | 장치 운행 UUID. 서버 구간 id 또는 UUID 형식 local_interval_id와 대응하며 설치 내 유일 |
| `vehicle_id` | `CHAR(36)` | 아니오 | — | 차량 UUID. vehicles.id 참조 |
| `user_id` | `CHAR(36)` | 예 | — | 운행 요약의 원래 소유자 UUID. 익명 요약은 NULL |
| `consent_id` | `CHAR(36)` | 예 | — | 이전 구간 확인의 trip_user_candidates.id. 신규 지속 페어링 사용자 요약은 NULL 가능 |
| `revision` | `BIGINT` | 아니오 | — | 장치가 업로드한 요약 버전. 1 이상이며 같은 버전의 다른 본문은 거부 |
| `payload_hash` | `BINARY(32)` | 아니오 | — | 재전송 내용의 동일성 비교용 SHA-256 해시. 원문 토큰이 아님 |
| `payload` | `LONGTEXT` | 아니오 | — | 정규화한 운행 요약 업로드 JSON. 시간·거리·연료 끝점·단가·품질 포함 |
| `synced_at` | `DATETIME(6)` | 아니오 | — | 서버 요약 저장 완료 시각. UTC |

## mobile_trip_inbox

사용자별 운행 요약·주유 후보 확인함과 읽음 상태.

최초 생성: `V8__trip_summaries_and_mobile_inbox.sql`. 타입·기본값은 V12까지 반영한 최종 정의다.

| 컬럼 | 타입 | NULL 허용 | 기본값·자동 증가 | 설명 |
|---|---|:---:|---|---|
| `id` | `BIGINT` | 아니오 | AUTO_INCREMENT | 서버 내부 자동 증가 식별 번호 |
| `recipient_id` | `CHAR(36)` | 아니오 | — | 확인함 수신자의 Supabase 사용자 UUID. 서버가 원래 소유자로 결정 |
| `trip_id` | `CHAR(36)` | 예 | — | 운행 UUID. TRIP_SUMMARY 항목에 사용하고 주유 후보 항목은 NULL |
| `summary_id` | `BIGINT` | 예 | — | 운행 요약 내부 번호. trip_summaries.id 참조이며 주유 후보 항목은 NULL |
| `trip_revision` | `BIGINT` | 예 | — | 확인함에 전달한 운행 요약 버전. 주유 후보 항목은 NULL |
| `snapshot` | `LONGTEXT` | 예 | — | 전달 버전의 계산된 운행 요약 JSON. 주유 후보 항목은 NULL |
| `delivered_at` | `DATETIME(6)` | 아니오 | — | 확인함에 처음 전달한 시각(UTC). 요약 버전 갱신 시에도 최초 시각 보존 |
| `read_at` | `DATETIME(6)` | 예 | — | 첫 읽음 처리 시각(UTC). 미열람 또는 새 요약 버전 전달 시 NULL |
| `kind` | `VARCHAR(30)` | 아니오 | 'TRIP_SUMMARY' | TRIP_SUMMARY=운행 요약, FUEL_CANDIDATE=주유 후보 |
| `candidate_id` | `CHAR(36)` | 예 | — | 주유 후보 UUID. fuel_candidates.id 참조이며 운행 요약 항목은 NULL |

## vehicle_maintenance_state

차량 정비 데이터 변경을 식별하는 요약 revision.

최초 생성: `V10__maintenance_and_odometer.sql`. 타입·기본값은 V12까지 반영한 최종 정의다.

| 컬럼 | 타입 | NULL 허용 | 기본값·자동 증가 | 설명 |
|---|---|:---:|---|---|
| `vehicle_id` | `CHAR(36)` | 아니오 | — | 차량 UUID. vehicles.id 참조 |
| `revision` | `BIGINT` | 아니오 | 0 | 정비 계획·이력·누적거리 변경 시 증가하는 차량 정비 요약 버전 |

## vehicle_maintenance_profiles

서버가 검증한 제조사·차종·연식. 제조사 정비 근거 적용 여부 판정.

최초 생성: `V10__maintenance_and_odometer.sql`. 타입·기본값은 V12까지 반영한 최종 정의다.

| 컬럼 | 타입 | NULL 허용 | 기본값·자동 증가 | 설명 |
|---|---|:---:|---|---|
| `vehicle_id` | `CHAR(36)` | 아니오 | — | 차량 UUID. vehicles.id 참조 |
| `manufacturer` | `VARCHAR(100)` | 아니오 | — | 차량 제조사 이름 |
| `model` | `VARCHAR(100)` | 아니오 | — | 차종 또는 모델 이름 |
| `model_year` | `INT` | 아니오 | — | 차량 모델 연식 |
| `verified_at` | `DATETIME(6)` | 아니오 | — | 서버 검증 완료 시각. UTC |
| `verified_by` | `VARCHAR(100)` | 아니오 | — | 차량 또는 근거를 검증한 신뢰 가능한 서버 작업·관리자 식별값 |

## maintenance_manufacturer_evidence

제조사 정비 주기의 원본 문서·차종·연식·적용 범위와 검증 근거.

최초 생성: `V10__maintenance_and_odometer.sql`. 타입·기본값은 V12까지 반영한 최종 정의다.

| 컬럼 | 타입 | NULL 허용 | 기본값·자동 증가 | 설명 |
|---|---|:---:|---|---|
| `id` | `CHAR(36)` | 아니오 | — | 이 행을 식별하는 UUID |
| `document_url` | `VARCHAR(1000)` | 아니오 | — | 제조사 정비 근거 원본 문서 HTTPS 주소 |
| `document_title` | `VARCHAR(200)` | 아니오 | — | 제조사 정비 근거 문서 제목 |
| `manufacturer` | `VARCHAR(100)` | 아니오 | — | 차량 제조사 이름 |
| `model` | `VARCHAR(100)` | 아니오 | — | 차종 또는 모델 이름 |
| `model_year_from` | `INT` | 아니오 | — | 근거 적용 시작 모델 연식(포함) |
| `model_year_to` | `INT` | 아니오 | — | 근거 적용 종료 모델 연식(포함) |
| `service_name` | `VARCHAR(100)` | 아니오 | — | 근거가 적용되는 정비 항목명. 계획 이름과 일치해야 함 |
| `scope_text` | `VARCHAR(1000)` | 아니오 | — | 차종·사용 조건 등 근거 문서의 적용 범위 설명 |
| `interval_km` | `DECIMAL(12,3)` | 예 | — | 정비 반복 거리 주기(km). 거리 기준이 없으면 NULL |
| `interval_months` | `INT` | 예 | — | 정비 반복 달력 개월 수. 개월 기준이 없으면 NULL |
| `verified_at` | `DATETIME(6)` | 아니오 | — | 서버 검증 완료 시각. UTC |
| `verified_by` | `VARCHAR(100)` | 아니오 | — | 차량 또는 근거를 검증한 신뢰 가능한 서버 작업·관리자 식별값 |
| `revoked_at` | `DATETIME(6)` | 예 | — | 검증 또는 권한 폐기 시각. UTC이며 미폐기 시 NULL |

## maintenance_plans

차량 공동 또는 사용자 개인 정비 주기와 마지막 정비·다음 예정.

최초 생성: `V10__maintenance_and_odometer.sql`. 타입·기본값은 V12까지 반영한 최종 정의다.

| 컬럼 | 타입 | NULL 허용 | 기본값·자동 증가 | 설명 |
|---|---|:---:|---|---|
| `id` | `CHAR(36)` | 아니오 | — | 이 행을 식별하는 UUID |
| `vehicle_id` | `CHAR(36)` | 아니오 | — | 차량 UUID. vehicles.id 참조 |
| `owner_user_id` | `CHAR(36)` | 아니오 | — | 정비 계획을 소유한 Supabase 사용자 UUID |
| `name` | `VARCHAR(100)` | 아니오 | — | 정비 항목 표시 이름 |
| `shared` | `BOOLEAN` | 아니오 | — | 차량 공동 공개 여부. TRUE는 공동 항목, FALSE는 개인 항목 |
| `active` | `BOOLEAN` | 아니오 | — | 정비 예정 계산·요약 노출 활성 여부. FALSE여도 과거 이력은 보존 |
| `basis` | `VARCHAR(20)` | 아니오 | — | 정비 주기 기준. USER=사용자 설정, MANUFACTURER=검증된 제조사 근거 |
| `evidence_id` | `CHAR(36)` | 예 | — | 제조사 근거 UUID. maintenance_manufacturer_evidence.id 참조이며 사용자 기준은 NULL |
| `interval_km` | `DECIMAL(12,3)` | 예 | — | 정비 반복 거리 주기(km). 거리 기준이 없으면 NULL |
| `interval_months` | `INT` | 예 | — | 정비 반복 달력 개월 수. 개월 기준이 없으면 NULL |
| `revision` | `BIGINT` | 아니오 | — | 정비 계획 변경 버전. HTTP ETag·If-Match에 사용 |
| `last_service_date` | `DATE` | 예 | — | 다음 예정 계산에 사용한 마지막 확정 정비일. DATE |
| `last_service_km` | `DECIMAL(12,3)` | 예 | — | 마지막 확정 정비 당시 차량 누적거리(km) |
| `next_due_date` | `DATE` | 예 | — | 마지막 확정 정비일에 개월 주기를 더한 다음 정비 예정일 |
| `next_due_km` | `DECIMAL(15,3)` | 예 | — | 마지막 확정 정비 누적거리에 거리 주기를 더한 다음 정비 예정 누적거리(km) |
| `created_at` | `DATETIME(6)` | 아니오 | — | 행 생성 시각. UTC DATETIME(6) |
| `updated_at` | `DATETIME(6)` | 아니오 | — | 행의 마지막 변경 시각. UTC DATETIME(6) |

## maintenance_events

정비 확정·방문 추정 이력과 상세 JSON. 논리 삭제·중복 요청 관리.

최초 생성: `V10__maintenance_and_odometer.sql`. 타입·기본값은 V12까지 반영한 최종 정의다.

| 컬럼 | 타입 | NULL 허용 | 기본값·자동 증가 | 설명 |
|---|---|:---:|---|---|
| `id` | `CHAR(36)` | 아니오 | — | 이 행을 식별하는 UUID |
| `vehicle_id` | `CHAR(36)` | 아니오 | — | 차량 UUID. vehicles.id 참조 |
| `item_id` | `CHAR(36)` | 아니오 | — | 정비 계획 UUID. maintenance_plans.id 참조 |
| `author_user_id` | `CHAR(36)` | 아니오 | — | 정비 이력을 작성한 Supabase 사용자 UUID. 수정·삭제 주체 |
| `shared` | `BOOLEAN` | 아니오 | — | 차량 공동 공개 여부. TRUE는 공동 항목, FALSE는 개인 항목 |
| `status` | `VARCHAR(30)` | 아니오 | — | CONFIRMED=확정 정비, VISIT_ESTIMATE=방문 추정. 확정만 다음 정비 계산에 사용 |
| `service_date` | `DATE` | 예 | — | 정비 수행일. YYYY-MM-DD이며 누적거리만 기록한 경우 NULL 가능 |
| `odometer_km` | `DECIMAL(12,3)` | 예 | — | 정비 당시 차량 전체 누적거리(km) |
| `payload` | `LONGTEXT` | 아니오 | — | 정비 입력 JSON. 공개 범위·detailsShared·비용·통화·메모·정비소·영수증 포함 |
| `revision` | `BIGINT` | 아니오 | — | 정비 이력 변경 버전. HTTP ETag·If-Match에 사용 |
| `create_key` | `VARCHAR(100)` | 아니오 | — | 차량·작성자 내 등록 재시도 키 |
| `create_hash` | `BINARY(32)` | 아니오 | — | 정규화한 최초 등록 본문의 SHA-256. 동일 키의 다른 내용 차단 |
| `create_response` | `LONGTEXT` | 아니오 | — | 최초 등록 응답 JSON. 같은 요청 재시도에 최초 응답을 반환하기 위해 보관 |
| `created_at` | `DATETIME(6)` | 아니오 | — | 행 생성 시각. UTC DATETIME(6) |
| `updated_at` | `DATETIME(6)` | 아니오 | — | 행의 마지막 변경 시각. UTC DATETIME(6) |
| `deleted_at` | `DATETIME(6)` | 예 | — | 논리 삭제 시각. UTC이며 미삭제 시 NULL |

## maintenance_event_audit

정비 이력 생성·수정·삭제의 행위자와 변경 전후 감사 기록.

최초 생성: `V10__maintenance_and_odometer.sql`. 타입·기본값은 V12까지 반영한 최종 정의다.

| 컬럼 | 타입 | NULL 허용 | 기본값·자동 증가 | 설명 |
|---|---|:---:|---|---|
| `id` | `BIGINT` | 아니오 | AUTO_INCREMENT | 서버 내부 자동 증가 식별 번호 |
| `event_id` | `CHAR(36)` | 아니오 | — | 감사 대상 정비 이력 UUID. maintenance_events.id 참조 |
| `actor_user_id` | `CHAR(36)` | 아니오 | — | 정비 이력 변경을 수행한 Supabase 사용자 UUID |
| `action` | `VARCHAR(20)` | 아니오 | — | 감사 작업 종류. CREATE=생성, UPDATE=수정, DELETE=논리 삭제 |
| `revision` | `BIGINT` | 아니오 | — | 해당 작업을 반영한 정비 이력 버전 |
| `before_payload` | `LONGTEXT` | 예 | — | 변경 전 정비 입력 JSON. 생성 시 NULL |
| `after_payload` | `LONGTEXT` | 예 | — | 변경 후 정비 입력 JSON. 삭제 시 NULL |
| `created_at` | `DATETIME(6)` | 아니오 | — | 행 생성 시각. UTC DATETIME(6) |

## vehicle_odometer_samples

장치에서 보고한 차량 원본 누적거리와 품질·중복 요청 검증 정보.

최초 생성: `V10__maintenance_and_odometer.sql`. 타입·기본값은 V12까지 반영한 최종 정의다.

| 컬럼 | 타입 | NULL 허용 | 기본값·자동 증가 | 설명 |
|---|---|:---:|---|---|
| `id` | `BIGINT` | 아니오 | AUTO_INCREMENT | 서버 내부 자동 증가 식별 번호 |
| `sample_id` | `CHAR(36)` | 아니오 | — | 장치가 생성한 원본 누적거리 샘플 UUID. 설치 내 재시도 키 |
| `installation_id` | `CHAR(36)` | 아니오 | — | 장치 앱 설치 UUID. vehicle_installations.id 참조 |
| `vehicle_id` | `CHAR(36)` | 아니오 | — | 차량 UUID. vehicles.id 참조 |
| `km` | `DECIMAL(12,3)` | 예 | — | 차량 전체 누적거리(km). 구간 증가량이 아니며 VALID일 때만 저장 |
| `observed_at` | `DATETIME(6)` | 아니오 | — | 차량 누적거리 관측 시각(UTC). API의 epoch milliseconds를 변환해 저장 |
| `unit` | `VARCHAR(10)` | 아니오 | — | 거리 단위. km 고정 |
| `source` | `VARCHAR(40)` | 아니오 | — | 원본 출처 선언. PLEOS_VEHICLE_API 고정이며 하드웨어 진위 증명은 별도 |
| `quality` | `VARCHAR(20)` | 아니오 | — | VALID=정상, UNSUPPORTED=미지원, ERROR=측정 오류. VALID가 아니면 km는 NULL |
| `payload_hash` | `BINARY(32)` | 아니오 | — | 재전송 내용의 동일성 비교용 SHA-256 해시. 원문 토큰이 아님 |
| `received_at` | `DATETIME(6)` | 아니오 | — | 서버 수신 시각. UTC |

## fuel_candidates

연료 증가로 감지한 주유 후보의 증거·서버 추정값·사용자 확인 및 보정값.

최초 생성: `V11__fuel_candidates.sql`. 타입·기본값은 V12까지 반영한 최종 정의다.

| 컬럼 | 타입 | NULL 허용 | 기본값·자동 증가 | 설명 |
|---|---|:---:|---|---|
| `id` | `CHAR(36)` | 아니오 | — | 이 행을 식별하는 UUID |
| `installation_id` | `CHAR(36)` | 아니오 | — | 장치 앱 설치 UUID. vehicle_installations.id 참조 |
| `vehicle_id` | `CHAR(36)` | 아니오 | — | 차량 UUID. vehicles.id 참조 |
| `owner_id` | `CHAR(36)` | 아니오 | — | 주유 후보 소유자의 Supabase 사용자 UUID |
| `interval_id` | `CHAR(36)` | 아니오 | — | 운행 기록 구간 UUID. recording_intervals.id 참조 |
| `start_at` | `BIGINT` | 아니오 | — | 연료 증가 감지 시작 시각. UTC epoch milliseconds |
| `end_at` | `BIGINT` | 아니오 | — | 연료 증가 감지 종료 시각. UTC epoch milliseconds |
| `increase_percent` | `DECIMAL(6,3)` | 아니오 | — | 감지한 연료 잔량 증가율(%). 0 초과 100 이하 |
| `status` | `VARCHAR(20)` | 아니오 | 'CANDIDATE' | CANDIDATE=확인 대기, CONFIRMED=주유 확인, REJECTED=기각 |
| `revision` | `BIGINT` | 아니오 | 1 | 주유 후보 변경 버전. 모바일 expectedRevision 충돌 검사에 사용 |
| `capacity_verified` | `BOOLEAN` | 아니오 | FALSE | 서버가 연료탱크 용량의 신뢰 근거를 확인했는지 여부 |
| `estimated_liters` | `DECIMAL(12,3)` | 예 | — | 검증된 탱크 용량과 증가율로 추정한 주유량(L) |
| `estimated_amount` | `DECIMAL(15,2)` | 예 | — | 추정 주유량과 검증된 단가로 계산한 금액. 통화 필드는 이 테이블에 없음 |
| `price_at` | `BIGINT` | 예 | — | 추정에 사용한 연료 단가 확인 시각. UTC epoch milliseconds |
| `corrected_place` | `VARCHAR(200)` | 예 | — | 사용자가 직접 보정한 주유 장소. 원래 감지 증거와 별도 보관 |
| `corrected_liters` | `DECIMAL(12,3)` | 예 | — | 사용자가 직접 보정한 주유량(L) |
| `corrected_amount` | `DECIMAL(15,2)` | 예 | — | 사용자가 직접 보정한 주유 금액. 통화 필드는 이 테이블에 없음 |
| `created_at` | `DATETIME(6)` | 아니오 | — | 행 생성 시각. UTC DATETIME(6) |
| `updated_at` | `DATETIME(6)` | 아니오 | — | 행의 마지막 변경 시각. UTC DATETIME(6) |

## vehicle_fuel_metadata

서버가 검증한 연료탱크 용량과 단가·유효기간. 주유량·금액 추정 근거.

최초 생성: `V11__fuel_candidates.sql`. 타입·기본값은 V12까지 반영한 최종 정의다.

| 컬럼 | 타입 | NULL 허용 | 기본값·자동 증가 | 설명 |
|---|---|:---:|---|---|
| `vehicle_id` | `CHAR(36)` | 아니오 | — | 차량 UUID. vehicles.id 참조 |
| `capacity_liters` | `DECIMAL(10,3)` | 예 | — | 서버가 확인한 연료탱크 용량(L) |
| `capacity_verified_at` | `DATETIME(6)` | 예 | — | 연료탱크 용량 서버 검증 시각. UTC |
| `capacity_source` | `VARCHAR(200)` | 예 | — | 연료탱크 용량의 신뢰 가능한 확인 출처 |
| `unit_price` | `DECIMAL(12,3)` | 예 | — | 연료 1L당 검증 단가. 통화 필드는 이 테이블에 없음 |
| `price_at` | `BIGINT` | 예 | — | 추정에 사용한 연료 단가 확인 시각. UTC epoch milliseconds |
| `price_valid_until` | `BIGINT` | 예 | — | 검증 단가 유효기간 종료 시각. UTC epoch milliseconds |
| `price_source` | `VARCHAR(200)` | 예 | — | 연료 단가의 신뢰 가능한 확인 출처 |

## fuel_candidate_operations

주유 후보 확인·기각·보정 요청과 응답 보관. 재시도 중복 방지 및 감사.

최초 생성: `V11__fuel_candidates.sql`. 타입·기본값은 V12까지 반영한 최종 정의다.

| 컬럼 | 타입 | NULL 허용 | 기본값·자동 증가 | 설명 |
|---|---|:---:|---|---|
| `candidate_id` | `CHAR(36)` | 아니오 | — | 변경 대상 주유 후보 UUID. fuel_candidates.id 참조 |
| `operation_id` | `VARCHAR(100)` | 아니오 | — | 주유 후보 내 모바일 수정 재시도 키 |
| `actor_id` | `CHAR(36)` | 아니오 | — | 작업을 수행한 Supabase 사용자 UUID |
| `request_payload` | `LONGTEXT` | 아니오 | — | 확인·기각·보정 요청 JSON. 같은 키의 다른 요청 비교 |
| `response_payload` | `LONGTEXT` | 아니오 | — | 처음 저장한 수정 결과 JSON. 같은 요청 재시도에 반환 |
| `previous_revision` | `BIGINT` | 아니오 | — | 작업 직전 후보 버전 |
| `resulting_revision` | `BIGINT` | 아니오 | — | 작업 반영 후 후보 버전 |
| `created_at` | `DATETIME(6)` | 아니오 | — | 행 생성 시각. UTC DATETIME(6) |

## connection_operations

사용자 해제·차량 초기화 요청과 최초 대상·등록 버전·관리자 결정 이력.

최초 생성: `V12__connection_settings_and_operations.sql`. 타입·기본값은 V12까지 반영한 최종 정의다.

| 컬럼 | 타입 | NULL 허용 | 기본값·자동 증가 | 설명 |
|---|---|:---:|---|---|
| `id` | `CHAR(36)` | 아니오 | — | 이 행을 식별하는 UUID |
| `installation_id` | `CHAR(36)` | 아니오 | — | 장치 앱 설치 UUID. vehicle_installations.id 참조 |
| `operation_id` | `VARCHAR(100)` | 아니오 | — | 설치 또는 대상 내에서 같은 요청 재시도에 유지하는 고유 키 |
| `kind` | `VARCHAR(10)` | 아니오 | — | USER=사용자 해제, VEHICLE=관리자 승인이 필요한 차량 초기화 |
| `status` | `VARCHAR(30)` | 아니오 | — | AWAITING_ADMIN=관리자 승인 대기, COMPLETED=완료, REJECTED=기각 |
| `vehicle_id` | `CHAR(36)` | 예 | — | 요청 최초 대상 차량 UUID. 이후 연결 변경에도 이력 값 유지 |
| `user_id` | `CHAR(36)` | 예 | — | 요청 최초 확인된 사용자 UUID. 확인된 사용자가 없으면 NULL |
| `registration_version` | `BIGINT` | 아니오 | — | 설치와 차량의 연결 변경 때 증가하는 버전. 이전 연결의 요청·설정 재사용 방지 |
| `session_version` | `BIGINT` | 아니오 | — | 연결 해제 요청 시작 시점의 설치 세션 버전 스냅샷 |
| `decision_user_id` | `CHAR(36)` | 예 | — | 차량 초기화를 승인·기각한 관리자 Supabase UUID. 결정 전 또는 사용자 해제는 NULL 가능 |
| `pending_records_policy` | `VARCHAR(40)` | 예 | — | 승인한 기록 보존 정책. RETAIN_ORIGINAL_OWNER=기존 소유권 유지 |
| `created_at` | `DATETIME(6)` | 아니오 | — | 행 생성 시각. UTC DATETIME(6) |
| `completed_at` | `DATETIME(6)` | 예 | — | 요청 처리 완료 시각(UTC). 승인 대기 상태는 NULL |

## connection_reauth_uses

차량 초기화에 소비한 OTP·TOTP 인증 사건. JWT 갱신을 통한 증명 재사용 방지.

최초 생성: `V12__connection_settings_and_operations.sql`. 타입·기본값은 V12까지 반영한 최종 정의다.

| 컬럼 | 타입 | NULL 허용 | 기본값·자동 증가 | 설명 |
|---|---|:---:|---|---|
| `proof_hash` | `BINARY(32)` | 아니오 | — | 사용자 UUID·OTP/TOTP 방식·인증 시각 조합의 SHA-256. JWT 원문 해시가 아님 |
| `operation_id` | `CHAR(36)` | 아니오 | — | 재인증을 소비한 connection_operations.id. 클라이언트 operation_id 문자열과 다름 |
| `actor_id` | `CHAR(36)` | 아니오 | — | 작업을 수행한 Supabase 사용자 UUID |
| `authenticated_at` | `DATETIME(6)` | 아니오 | — | Supabase amr에 기록된 OTP/TOTP 인증 시각. UTC |
| `used_at` | `DATETIME(6)` | 아니오 | — | 서버가 해당 인증 사건을 초기화 승인에 소비한 시각. UTC |

## installation_capabilities

설치의 현재 차량 등록 버전에 대한 연료·거리·위치 SDK 기능 보고.

최초 생성: `V12__connection_settings_and_operations.sql`. 타입·기본값은 V12까지 반영한 최종 정의다.

| 컬럼 | 타입 | NULL 허용 | 기본값·자동 증가 | 설명 |
|---|---|:---:|---|---|
| `installation_id` | `CHAR(36)` | 아니오 | — | 장치 앱 설치 UUID. vehicle_installations.id 참조 |
| `registration_version` | `BIGINT` | 아니오 | — | 설치와 차량의 연결 변경 때 증가하는 버전. 이전 연결의 요청·설정 재사용 방지 |
| `observed_at` | `BIGINT` | 아니오 | — | 장치 SDK 기능 관측 시각. UTC epoch milliseconds |
| `fuel` | `VARCHAR(30)` | 아니오 | — | 연료 기능 상태. AVAILABLE/UNSUPPORTED/PERMISSION_REQUIRED/TEMPORARY_FAILURE/UNKNOWN |
| `distance` | `VARCHAR(30)` | 아니오 | — | 거리 기능 상태. AVAILABLE/UNSUPPORTED/PERMISSION_REQUIRED/TEMPORARY_FAILURE/UNKNOWN |
| `location` | `VARCHAR(30)` | 아니오 | — | 위치 기능 상태. AVAILABLE/UNSUPPORTED/PERMISSION_REQUIRED/TEMPORARY_FAILURE/UNKNOWN |

## recommendation_registrations

서버가 확인한 설치·차량 등록 버전별 추천 기능 등록·검증 상태.

최초 생성: `V12__connection_settings_and_operations.sql`. 타입·기본값은 V12까지 반영한 최종 정의다.

| 컬럼 | 타입 | NULL 허용 | 기본값·자동 증가 | 설명 |
|---|---|:---:|---|---|
| `installation_id` | `CHAR(36)` | 아니오 | — | 장치 앱 설치 UUID. vehicle_installations.id 참조 |
| `registration_version` | `BIGINT` | 아니오 | — | 설치와 차량의 연결 변경 때 증가하는 버전. 이전 연결의 요청·설정 재사용 방지 |
| `registered` | `BOOLEAN` | 아니오 | FALSE | 현재 설치·차량 등록 버전에 추천 기능이 서버 등록되었는지 여부 |
| `verified` | `BOOLEAN` | 아니오 | FALSE | 서버가 추천 기능 사용 조건을 검증했는지 여부. 장치 보고만으로 TRUE가 되지 않음 |
| `verified_at` | `DATETIME(6)` | 예 | — | 서버 검증 완료 시각. UTC |
| `verification_source` | `VARCHAR(200)` | 예 | — | 추천 기능 검증의 신뢰 가능한 서버 출처 |

## recommendation_consents

설치·차량 등록 버전·사용자별 추천 기능 동의와 활성화 선택.

최초 생성: `V12__connection_settings_and_operations.sql`. 타입·기본값은 V12까지 반영한 최종 정의다.

| 컬럼 | 타입 | NULL 허용 | 기본값·자동 증가 | 설명 |
|---|---|:---:|---|---|
| `installation_id` | `CHAR(36)` | 아니오 | — | 장치 앱 설치 UUID. vehicle_installations.id 참조 |
| `registration_version` | `BIGINT` | 아니오 | — | 설치와 차량의 연결 변경 때 증가하는 버전. 이전 연결의 요청·설정 재사용 방지 |
| `user_id` | `CHAR(36)` | 아니오 | — | 데이터 주체의 Supabase 사용자 UUID |
| `consented` | `BOOLEAN` | 아니오 | — | 사용자의 추천 기능 동의 여부. FALSE는 동의 철회 |
| `enabled` | `BOOLEAN` | 아니오 | — | 사용자가 선택한 추천 활성화 여부. 실제 활성에는 등록·검증·동의·현재 사용자 조건도 필요 |
| `updated_at` | `DATETIME(6)` | 아니오 | — | 행의 마지막 변경 시각. UTC DATETIME(6) |

## installation_sync_checkpoints

서버가 검증한 설치·차량 등록 버전·사용자별 큐 완료 확인 지점.

최초 생성: `V12__connection_settings_and_operations.sql`. 타입·기본값은 V12까지 반영한 최종 정의다.

| 컬럼 | 타입 | NULL 허용 | 기본값·자동 증가 | 설명 |
|---|---|:---:|---|---|
| `installation_id` | `CHAR(36)` | 아니오 | — | 장치 앱 설치 UUID. vehicle_installations.id 참조 |
| `registration_version` | `BIGINT` | 아니오 | — | 설치와 차량의 연결 변경 때 증가하는 버전. 이전 연결의 요청·설정 재사용 방지 |
| `user_id` | `CHAR(36)` | 아니오 | — | 데이터 주체의 Supabase 사용자 UUID |
| `pending_count` | `BIGINT` | 예 | — | 서버 큐 조정 작업이 확인한 사용자별 대기 건수. 모르면 NULL이며 장치 로컬 큐 건수가 아님 |
| `last_acknowledged_at` | `BIGINT` | 예 | — | 서버가 마지막 수신 완료를 확인한 시각. UTC epoch milliseconds |
| `verified_at` | `BIGINT` | 예 | — | 서버 큐 조정 작업이 상태를 검증한 시각. UTC epoch milliseconds이며 미확인 시 NULL |

## 검증 결과

격리된 임시 MariaDB 10.6.20에서 V1~V12와 V7 샘플 데이터를 적용한 뒤 V13을 실행해 확인했다.

- 테이블 COMMENT 30개, 컬럼 COMMENT 286개가 모두 저장되었다.
- 컬럼 타입·순서·NULL·기본값·AUTO_INCREMENT·문자셋·콜레이션이 유지되었다.
- 인덱스·기본키·외래키·CHECK 제약과 샘플 데이터가 적용 전후 동일했다.
- 검증용 컨테이너는 정리했으며 애플리케이션 연결 DB에는 직접 적용하지 않았다.
- 운영 대상 MariaDB 버전과 실제 DB의 스키마 차이는 별도로 확인해야 한다.

## 적용 후 확인

애플리케이션 시작 시 Flyway가 V13을 적용한다. local/prod 연결 DB의 기존 마이그레이션 상태를 따른다.

```sql
SELECT TABLE_NAME, TABLE_COMMENT
FROM information_schema.TABLES
WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME <> 'flyway_schema_history'
ORDER BY TABLE_NAME;

SELECT TABLE_NAME, COLUMN_NAME, COLUMN_TYPE, IS_NULLABLE, COLUMN_DEFAULT, COLUMN_COMMENT
FROM information_schema.COLUMNS
WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME <> 'flyway_schema_history'
ORDER BY TABLE_NAME, ORDINAL_POSITION;
```

COMMENT 변경은 DDL이므로 MariaDB의 메타데이터 잠금이 발생할 수 있다. 저장된 업무 데이터는 변경하지 않는다.

문법 근거: [MariaDB ALTER TABLE](https://mariadb.com/docs/server/reference/sql-statements/data-definition/alter/alter-table). `MODIFY COLUMN`에 기존 타입·NULL·기본값·AUTO_INCREMENT를 함께 명시해 기존 속성을 유지한다.
