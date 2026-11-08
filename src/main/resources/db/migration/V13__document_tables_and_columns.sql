-- 기존 V1~V12의 타입·NULL 허용·기본값·AUTO_INCREMENT와 인덱스·제약 조건을 보존한다.
-- 테이블 30개와 컬럼 286개에 한국어 COMMENT를 추가한다. DATETIME은 UTC다.

ALTER TABLE `vehicles`
    COMMENT = '차량 기본 정보. 앱 설치와 독립적으로 유지되는 차량 식별자와 표시 이름',
    MODIFY COLUMN `id` CHAR(36) NOT NULL COMMENT '이 행을 식별하는 UUID',
    MODIFY COLUMN `name` VARCHAR(100) NOT NULL COMMENT '차량 표시 이름 또는 별칭',
    MODIFY COLUMN `created_by` CHAR(36) NOT NULL COMMENT '차량을 생성한 Supabase 사용자 UUID',
    MODIFY COLUMN `source_request_id` CHAR(36) NULL COMMENT '차량 생성에 사용한 페어링 요청 UUID. pairing_requests.id 참조이며 중복 차량 생성 방지',
    MODIFY COLUMN `created_at` DATETIME(6) NOT NULL COMMENT '행 생성 시각. UTC DATETIME(6)',
    MODIFY COLUMN `updated_at` DATETIME(6) NOT NULL COMMENT '행의 마지막 변경 시각. UTC DATETIME(6)';

ALTER TABLE `group_members`
    COMMENT = '차량별 사용자 멤버십과 OWNER·MANAGER·MEMBER 권한',
    MODIFY COLUMN `vehicle_id` CHAR(36) NOT NULL COMMENT '차량 UUID. vehicles.id 참조',
    MODIFY COLUMN `user_id` CHAR(36) NOT NULL COMMENT '데이터 주체의 Supabase 사용자 UUID',
    MODIFY COLUMN `role` VARCHAR(20) NOT NULL COMMENT '차량 권한. OWNER=소유자, MANAGER=관리자, MEMBER=일반 구성원',
    MODIFY COLUMN `created_at` DATETIME(6) NOT NULL COMMENT '행 생성 시각. UTC DATETIME(6)';

ALTER TABLE `vehicle_installations`
    COMMENT = '장치 앱 설치별 인증과 차량 연결 상태. 재설치하면 별도 설치로 등록',
    MODIFY COLUMN `id` CHAR(36) NOT NULL COMMENT '앱 설치 UUID. 차량 ID와 다르며 재설치 후 새로 발급됨',
    MODIFY COLUMN `vehicle_id` CHAR(36) NULL COMMENT '연결 차량 UUID. vehicles.id 참조이며 미연결 시 NULL. 차량당 연결 설치 최대 1개',
    MODIFY COLUMN `created_at` DATETIME(6) NOT NULL COMMENT '행 생성 시각. UTC DATETIME(6)',
    MODIFY COLUMN `updated_at` DATETIME(6) NOT NULL COMMENT '행의 마지막 변경 시각. UTC DATETIME(6)',
    MODIFY COLUMN `credential_hash` BINARY(32) NULL COMMENT '설치 비밀값의 SHA-256 해시(32바이트). 비밀값·모바일 JWT 원문은 저장하지 않음',
    MODIFY COLUMN `session_version` BIGINT NOT NULL DEFAULT 0 COMMENT '사용자 세션 폐기 판정용 설치 세션 버전',
    MODIFY COLUMN `registration_version` BIGINT NOT NULL DEFAULT 0 COMMENT '설치와 차량의 연결 변경 때 증가하는 버전. 이전 연결의 요청·설정 재사용 방지',
    MODIFY COLUMN `personal_access_blocked` BOOLEAN NOT NULL DEFAULT FALSE COMMENT '연결 해제·초기화 요청 후 개인 접근·수집 차단 여부. 재승인 시 해제',
    MODIFY COLUMN `access_blocked_at` DATETIME(6) NULL COMMENT '개인 접근 차단 경계 시각(UTC). 이 시각 이후 새 사용자 증명을 요구',
    MODIFY COLUMN `device_alias` VARCHAR(100) NULL COMMENT '장치에 표시할 별칭. 미설정이면 NULL';

ALTER TABLE `pairing_requests`
    COMMENT = '차량 연결 QR 요청과 모바일 승인·차량 화면 확인 이력. 완료된 승인으로 현재 사용자 식별',
    MODIFY COLUMN `id` CHAR(36) NOT NULL COMMENT '이 행을 식별하는 UUID',
    MODIFY COLUMN `installation_id` CHAR(36) NOT NULL COMMENT '장치 앱 설치 UUID. vehicle_installations.id 참조',
    MODIFY COLUMN `proof_hash` BINARY(32) NULL COMMENT '차량 연결 QR의 일회용 proof 또는 nonce SHA-256 해시. 소비·폐기 후 NULL',
    MODIFY COLUMN `status` VARCHAR(40) NOT NULL COMMENT 'pending=모바일 대기, awaiting_vehicle_confirmation=차량 확인 대기, completed=완료, expired=만료, revoked=폐기',
    MODIFY COLUMN `expires_at` DATETIME(6) NOT NULL COMMENT '연결 QR 요청 만료 시각(UTC). 완료된 사용자 연결의 만료 시각이 아님',
    MODIFY COLUMN `selected_vehicle_id` CHAR(36) NULL COMMENT '모바일이 선택하거나 연결 완료 시 생성된 차량 UUID. vehicles.id 참조',
    MODIFY COLUMN `new_vehicle_name` VARCHAR(100) NULL COMMENT '기존 페어링에서 새 차량 생성 선택 시 입력한 이름',
    MODIFY COLUMN `approved_user_id` CHAR(36) NULL COMMENT '연결에 동의한 Supabase 사용자 UUID. 현재 차량의 완료된 승인에서 사용자 식별에 사용',
    MODIFY COLUMN `issue_key` VARCHAR(100) NOT NULL COMMENT '설치 내 QR 발급 재시도 키. 설치 ID와 조합해 유일',
    MODIFY COLUMN `intent_key` VARCHAR(100) NULL COMMENT '모바일 연결 의사 제출 또는 연결 승인의 재시도 키',
    MODIFY COLUMN `confirmation_code` CHAR(6) NULL COMMENT '기존 페어링의 모바일·차량 화면 대조용 6자리 코드. 모바일 의사 접수 전 NULL',
    MODIFY COLUMN `created_at` DATETIME(6) NOT NULL COMMENT '행 생성 시각. UTC DATETIME(6)',
    MODIFY COLUMN `updated_at` DATETIME(6) NOT NULL COMMENT '행의 마지막 변경 시각. UTC DATETIME(6)',
    MODIFY COLUMN `active_installation_id` CHAR(36) NULL COMMENT '활성 요청·세션에만 설치 UUID를 저장하는 유일성 제어 필드. 비활성 상태는 NULL';

ALTER TABLE `trip_user_challenges`
    COMMENT = '사용자 확인 QR 요청과 승인 이력. 이전 개인 홈 세션·구간 동의 흐름과 호환',
    MODIFY COLUMN `id` CHAR(36) NOT NULL COMMENT '이 행을 식별하는 UUID',
    MODIFY COLUMN `installation_id` CHAR(36) NOT NULL COMMENT '장치 앱 설치 UUID. vehicle_installations.id 참조',
    MODIFY COLUMN `vehicle_id` CHAR(36) NOT NULL COMMENT '차량 UUID. vehicles.id 참조',
    MODIFY COLUMN `operation_id` VARCHAR(100) NOT NULL COMMENT '설치 또는 대상 내에서 같은 요청 재시도에 유지하는 고유 키',
    MODIFY COLUMN `proof_hash` BINARY(32) NULL COMMENT '사용자 확인 QR 일회용 비밀값의 SHA-256 해시. 승인·폐기 후 NULL',
    MODIFY COLUMN `status` VARCHAR(20) NOT NULL COMMENT 'pending=승인 대기, approved=승인 완료, expired=요청 만료, revoked=폐기',
    MODIFY COLUMN `expires_at` DATETIME(6) NOT NULL COMMENT '요청 또는 권한의 만료 시각. UTC',
    MODIFY COLUMN `approved_user_id` CHAR(36) NULL COMMENT '사용자 확인 QR에 동의한 Supabase 사용자 UUID',
    MODIFY COLUMN `display_name` VARCHAR(100) NULL COMMENT '사용자 확인 당시 표시 이름. 인증 수단으로 사용하지 않음',
    MODIFY COLUMN `session_id` CHAR(36) NULL COMMENT '이전 개인 홈 승인으로 발급한 trip_user_sessions.id. 구간 동의 요청에는 NULL 가능',
    MODIFY COLUMN `active_installation_id` CHAR(36) NULL COMMENT '활성 요청·세션에만 설치 UUID를 저장하는 유일성 제어 필드. 비활성 상태는 NULL',
    MODIFY COLUMN `created_at` DATETIME(6) NOT NULL COMMENT '행 생성 시각. UTC DATETIME(6)',
    MODIFY COLUMN `updated_at` DATETIME(6) NOT NULL COMMENT '행의 마지막 변경 시각. UTC DATETIME(6)',
    MODIFY COLUMN `interval_id` CHAR(36) NULL COMMENT '구간별 동의 대상 recording_intervals.id. 이전 개인 홈 확인은 NULL';

ALTER TABLE `trip_user_sessions`
    COMMENT = '이전 클라이언트용 30분 사용자 세션과 토큰 해시·폐기 상태',
    MODIFY COLUMN `id` CHAR(36) NOT NULL COMMENT '이 행을 식별하는 UUID',
    MODIFY COLUMN `installation_id` CHAR(36) NOT NULL COMMENT '장치 앱 설치 UUID. vehicle_installations.id 참조',
    MODIFY COLUMN `vehicle_id` CHAR(36) NOT NULL COMMENT '차량 UUID. vehicles.id 참조',
    MODIFY COLUMN `user_id` CHAR(36) NOT NULL COMMENT '데이터 주체의 Supabase 사용자 UUID',
    MODIFY COLUMN `display_name` VARCHAR(100) NOT NULL COMMENT '사용자 확인 당시 표시 이름. 인증 수단으로 사용하지 않음',
    MODIFY COLUMN `version` BIGINT NOT NULL COMMENT '발급 시점 설치 session_version. 현재 버전과 다르면 폐기된 세션으로 판정',
    MODIFY COLUMN `token_hash` BINARY(32) NULL COMMENT '이전 사용자 세션 scopedToken의 SHA-256 해시. 토큰은 장치 조회 시 파생하며 원문은 저장하지 않음',
    MODIFY COLUMN `status` VARCHAR(20) NOT NULL COMMENT '이전 사용자 세션 상태. active=활성, revoked=폐기',
    MODIFY COLUMN `expires_at` DATETIME(6) NOT NULL COMMENT '이전 사용자 세션의 만료 시각(UTC). 발급 후 30분',
    MODIFY COLUMN `active_installation_id` CHAR(36) NULL COMMENT '활성 요청·세션에만 설치 UUID를 저장하는 유일성 제어 필드. 비활성 상태는 NULL',
    MODIFY COLUMN `created_at` DATETIME(6) NOT NULL COMMENT '행 생성 시각. UTC DATETIME(6)',
    MODIFY COLUMN `revoked_at` DATETIME(6) NULL COMMENT '검증 또는 권한 폐기 시각. UTC이며 미폐기 시 NULL';

ALTER TABLE `recording_intervals`
    COMMENT = '수동 운행 기록 구간. 시작 시 소유자를 고정하고 샘플 수신·종료 상태 관리',
    MODIFY COLUMN `id` CHAR(36) NOT NULL COMMENT '이 행을 식별하는 UUID',
    MODIFY COLUMN `installation_id` CHAR(36) NOT NULL COMMENT '장치 앱 설치 UUID. vehicle_installations.id 참조',
    MODIFY COLUMN `vehicle_id` CHAR(36) NOT NULL COMMENT '차량 UUID. vehicles.id 참조',
    MODIFY COLUMN `user_id` CHAR(36) NULL COMMENT '구간 시작 때 고정한 소유자의 Supabase UUID. 익명 구간은 NULL이며 자동 재귀속하지 않음',
    MODIFY COLUMN `session_version` BIGINT NULL COMMENT '구간 시작 시 이전 사용자 세션 버전. 세션 없이 시작한 경우 NULL 가능',
    MODIFY COLUMN `local_interval_id` VARCHAR(100) NOT NULL COMMENT '장치 로컬 구간 식별자. 설치 내 유일하며 시작 재시도 중복 방지',
    MODIFY COLUMN `source` VARCHAR(20) NOT NULL COMMENT '운행 시작 출처. 현재 manual_app=앱 수동 기록만 허용',
    MODIFY COLUMN `status` VARCHAR(20) NOT NULL COMMENT 'recording=기록 중, finished=종료',
    MODIFY COLUMN `started_at` DATETIME(6) NOT NULL COMMENT '운행 기록 시작 시각. UTC',
    MODIFY COLUMN `ended_at` DATETIME(6) NULL COMMENT '운행 기록 종료 시각. UTC이며 진행 중이면 NULL',
    MODIFY COLUMN `finish_operation_id` VARCHAR(100) NULL COMMENT '종료 요청 재시도 키. 강제 종료·요약 업로드 경로의 서버 키도 저장',
    MODIFY COLUMN `finish_last_sequence` BIGINT NULL COMMENT '종료 요청이 선언한 기기의 마지막 샘플 번호. 종료 전에는 NULL',
    MODIFY COLUMN `last_accepted_sequence` BIGINT NOT NULL DEFAULT 0 COMMENT '서버가 수락한 최대 샘플 번호. 중간 번호 누락이 없다는 뜻은 아님',
    MODIFY COLUMN `last_sample_at` DATETIME(6) NULL COMMENT '수신한 샘플 중 가장 늦은 관측 시각. UTC',
    MODIFY COLUMN `last_synced_at` DATETIME(6) NULL COMMENT '구간 샘플을 마지막으로 서버에 저장한 시각. UTC',
    MODIFY COLUMN `created_at` DATETIME(6) NOT NULL COMMENT '행 생성 시각. UTC DATETIME(6)',
    MODIFY COLUMN `updated_at` DATETIME(6) NOT NULL COMMENT '행의 마지막 변경 시각. UTC DATETIME(6)';

ALTER TABLE `recording_batches`
    COMMENT = '운행 샘플 배치 수신 이력. 같은 배치 재전송의 중복 저장과 내용 충돌 방지',
    MODIFY COLUMN `interval_id` CHAR(36) NOT NULL COMMENT '운행 기록 구간 UUID. recording_intervals.id 참조',
    MODIFY COLUMN `batch_id` VARCHAR(100) NOT NULL COMMENT '설치가 구간 내 같은 샘플 묶음 재전송에 사용하는 고유 키',
    MODIFY COLUMN `payload_hash` BINARY(32) NOT NULL COMMENT '재전송 내용의 동일성 비교용 SHA-256 해시. 원문 토큰이 아님',
    MODIFY COLUMN `accepted_count` INT NOT NULL COMMENT '최초 배치에서 수락한 샘플 개수',
    MODIFY COLUMN `last_accepted_sequence` BIGINT NOT NULL COMMENT '이 배치 수신 후 구간 전체에서 수락한 최대 샘플 번호',
    MODIFY COLUMN `received_at` DATETIME(6) NOT NULL COMMENT '서버 수신 시각. UTC';

ALTER TABLE `recording_samples`
    COMMENT = '운행 구간의 거리 증가량·연료 사용량·누락 및 샘플별 사용자 귀속',
    MODIFY COLUMN `interval_id` CHAR(36) NOT NULL COMMENT '운행 기록 구간 UUID. recording_intervals.id 참조',
    MODIFY COLUMN `sequence` BIGINT NOT NULL COMMENT '구간 내 샘플 번호. interval_id와 조합해 기본키',
    MODIFY COLUMN `batch_id` VARCHAR(100) NOT NULL COMMENT '이 샘플을 수신한 배치 식별값',
    MODIFY COLUMN `observed_at` DATETIME(6) NOT NULL COMMENT '장치에서 샘플을 관측한 시각. UTC',
    MODIFY COLUMN `distance_km` DECIMAL(12,3) NULL COMMENT '이 샘플에서 증가한 거리(km). 차량 전체 누적거리가 아니며 누락이면 NULL',
    MODIFY COLUMN `missing_reason` VARCHAR(40) NULL COMMENT '거리 측정 누락 이유. distance_km와 정확히 하나만 저장',
    MODIFY COLUMN `fuel_liters` DECIMAL(10,3) NULL COMMENT '이 샘플의 연료 사용량(L). 미지원·오류이면 NULL',
    MODIFY COLUMN `fuel_status` VARCHAR(20) NOT NULL COMMENT 'available=유효 연료값, unsupported=미지원·미보고, error=잘못된 연료값',
    MODIFY COLUMN `received_at` DATETIME(6) NOT NULL COMMENT '서버 수신 시각. UTC',
    MODIFY COLUMN `user_id` CHAR(36) NULL COMMENT '샘플별 소유자 UUID. NULL이면 구간 소유자를 기준으로 조회하며 익명 기록은 소급 귀속하지 않음',
    MODIFY COLUMN `verified_candidate_id` CHAR(36) NULL COMMENT '이전 구간 확인 흐름에서 검증한 trip_user_candidates.id. 후보 헤더 없이 저장하면 NULL';

ALTER TABLE `trip_user_candidates`
    COMMENT = '이전 구간별 사용자 확인용 모바일 동의 후보와 차량 화면 확인 결과',
    MODIFY COLUMN `id` CHAR(36) NOT NULL COMMENT '이 행을 식별하는 UUID',
    MODIFY COLUMN `challenge_id` CHAR(36) NOT NULL COMMENT '사용자 확인 QR 요청 UUID. trip_user_challenges.id 참조',
    MODIFY COLUMN `installation_id` CHAR(36) NOT NULL COMMENT '장치 앱 설치 UUID. vehicle_installations.id 참조',
    MODIFY COLUMN `vehicle_id` CHAR(36) NOT NULL COMMENT '차량 UUID. vehicles.id 참조',
    MODIFY COLUMN `interval_id` CHAR(36) NOT NULL COMMENT '운행 기록 구간 UUID. recording_intervals.id 참조',
    MODIFY COLUMN `user_id` CHAR(36) NOT NULL COMMENT '데이터 주체의 Supabase 사용자 UUID',
    MODIFY COLUMN `display_name` VARCHAR(100) NOT NULL COMMENT '사용자 확인 당시 표시 이름. 인증 수단으로 사용하지 않음',
    MODIFY COLUMN `consent_operation_id` VARCHAR(100) NOT NULL COMMENT '설치 내 모바일 구간 동의 재시도 키',
    MODIFY COLUMN `proof_fingerprint` BINARY(32) NOT NULL COMMENT '모바일 동의에 사용한 QR proof의 SHA-256 해시. 동의 재시도 동일성 확인',
    MODIFY COLUMN `verified_operation_id` VARCHAR(100) NULL COMMENT '차량 화면 사용자 선택 확인 재시도 키. 미확인 시 NULL',
    MODIFY COLUMN `status` VARCHAR(20) NOT NULL COMMENT 'active=선택 대기, verified=차량 확인 완료, revoked=철회·폐기, expired=만료',
    MODIFY COLUMN `expires_at` DATETIME(6) NOT NULL COMMENT '모바일 구간 동의 후보 만료 시각(UTC). 생성 후 5분',
    MODIFY COLUMN `verification_expires_at` DATETIME(6) NULL COMMENT '차량에서 확인한 신규 기록 권한 만료 시각(UTC). 미확인 시 NULL',
    MODIFY COLUMN `created_at` DATETIME(6) NOT NULL COMMENT '행 생성 시각. UTC DATETIME(6)',
    MODIFY COLUMN `updated_at` DATETIME(6) NOT NULL COMMENT '행의 마지막 변경 시각. UTC DATETIME(6)';

ALTER TABLE `trip_user_event_state`
    COMMENT = '설치별 사용자 후보 변경 이벤트의 최신 revision',
    MODIFY COLUMN `installation_id` CHAR(36) NOT NULL COMMENT '장치 앱 설치 UUID. vehicle_installations.id 참조',
    MODIFY COLUMN `revision` BIGINT NOT NULL DEFAULT 0 COMMENT '설치별 가장 최근에 발행한 사용자 변경 이벤트 번호. 기본값 0';

ALTER TABLE `trip_user_events`
    COMMENT = '개인정보를 제외한 사용자 후보·세션 변경 이벤트와 조회 커서',
    MODIFY COLUMN `installation_id` CHAR(36) NOT NULL COMMENT '장치 앱 설치 UUID. vehicle_installations.id 참조',
    MODIFY COLUMN `revision` BIGINT NOT NULL COMMENT '설치별 증가하는 변경 이벤트 번호. 클라이언트 cursor로 사용',
    MODIFY COLUMN `event_type` VARCHAR(30) NOT NULL COMMENT '개인정보 없는 변경 종류. candidates_changed=후보 변경, session_changed=세션 변경',
    MODIFY COLUMN `created_at` DATETIME(6) NOT NULL COMMENT '행 생성 시각. UTC DATETIME(6)';

ALTER TABLE `trip_summaries`
    COMMENT = '설치·운행별 요약 원본 JSON과 소유자·revision·서버 저장 확인 정보',
    MODIFY COLUMN `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '서버 내부 자동 증가 식별 번호',
    MODIFY COLUMN `installation_id` CHAR(36) NOT NULL COMMENT '장치 앱 설치 UUID. vehicle_installations.id 참조',
    MODIFY COLUMN `trip_id` CHAR(36) NOT NULL COMMENT '장치 운행 UUID. 서버 구간 id 또는 UUID 형식 local_interval_id와 대응하며 설치 내 유일',
    MODIFY COLUMN `vehicle_id` CHAR(36) NOT NULL COMMENT '차량 UUID. vehicles.id 참조',
    MODIFY COLUMN `user_id` CHAR(36) NULL COMMENT '운행 요약의 원래 소유자 UUID. 익명 요약은 NULL',
    MODIFY COLUMN `consent_id` CHAR(36) NULL COMMENT '이전 구간 확인의 trip_user_candidates.id. 신규 지속 페어링 사용자 요약은 NULL 가능',
    MODIFY COLUMN `revision` BIGINT NOT NULL COMMENT '장치가 업로드한 요약 버전. 1 이상이며 같은 버전의 다른 본문은 거부',
    MODIFY COLUMN `payload_hash` BINARY(32) NOT NULL COMMENT '재전송 내용의 동일성 비교용 SHA-256 해시. 원문 토큰이 아님',
    MODIFY COLUMN `payload` LONGTEXT NOT NULL COMMENT '정규화한 운행 요약 업로드 JSON. 시간·거리·연료 끝점·단가·품질 포함',
    MODIFY COLUMN `synced_at` DATETIME(6) NOT NULL COMMENT '서버 요약 저장 완료 시각. UTC';

ALTER TABLE `mobile_trip_inbox`
    COMMENT = '사용자별 운행 요약·주유 후보 확인함과 읽음 상태',
    MODIFY COLUMN `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '서버 내부 자동 증가 식별 번호',
    MODIFY COLUMN `recipient_id` CHAR(36) NOT NULL COMMENT '확인함 수신자의 Supabase 사용자 UUID. 서버가 원래 소유자로 결정',
    MODIFY COLUMN `trip_id` CHAR(36) NULL COMMENT '운행 UUID. TRIP_SUMMARY 항목에 사용하고 주유 후보 항목은 NULL',
    MODIFY COLUMN `summary_id` BIGINT NULL COMMENT '운행 요약 내부 번호. trip_summaries.id 참조이며 주유 후보 항목은 NULL',
    MODIFY COLUMN `trip_revision` BIGINT NULL COMMENT '확인함에 전달한 운행 요약 버전. 주유 후보 항목은 NULL',
    MODIFY COLUMN `snapshot` LONGTEXT NULL COMMENT '전달 버전의 계산된 운행 요약 JSON. 주유 후보 항목은 NULL',
    MODIFY COLUMN `delivered_at` DATETIME(6) NOT NULL COMMENT '확인함에 처음 전달한 시각(UTC). 요약 버전 갱신 시에도 최초 시각 보존',
    MODIFY COLUMN `read_at` DATETIME(6) NULL COMMENT '첫 읽음 처리 시각(UTC). 미열람 또는 새 요약 버전 전달 시 NULL',
    MODIFY COLUMN `kind` VARCHAR(30) NOT NULL DEFAULT 'TRIP_SUMMARY' COMMENT 'TRIP_SUMMARY=운행 요약, FUEL_CANDIDATE=주유 후보',
    MODIFY COLUMN `candidate_id` CHAR(36) NULL COMMENT '주유 후보 UUID. fuel_candidates.id 참조이며 운행 요약 항목은 NULL';

ALTER TABLE `vehicle_maintenance_state`
    COMMENT = '차량 정비 데이터 변경을 식별하는 요약 revision',
    MODIFY COLUMN `vehicle_id` CHAR(36) NOT NULL COMMENT '차량 UUID. vehicles.id 참조',
    MODIFY COLUMN `revision` BIGINT NOT NULL DEFAULT 0 COMMENT '정비 계획·이력·누적거리 변경 시 증가하는 차량 정비 요약 버전';

ALTER TABLE `vehicle_maintenance_profiles`
    COMMENT = '서버가 검증한 제조사·차종·연식. 제조사 정비 근거 적용 여부 판정',
    MODIFY COLUMN `vehicle_id` CHAR(36) NOT NULL COMMENT '차량 UUID. vehicles.id 참조',
    MODIFY COLUMN `manufacturer` VARCHAR(100) NOT NULL COMMENT '차량 제조사 이름',
    MODIFY COLUMN `model` VARCHAR(100) NOT NULL COMMENT '차종 또는 모델 이름',
    MODIFY COLUMN `model_year` INT NOT NULL COMMENT '차량 모델 연식',
    MODIFY COLUMN `verified_at` DATETIME(6) NOT NULL COMMENT '서버 검증 완료 시각. UTC',
    MODIFY COLUMN `verified_by` VARCHAR(100) NOT NULL COMMENT '차량 또는 근거를 검증한 신뢰 가능한 서버 작업·관리자 식별값';

ALTER TABLE `maintenance_manufacturer_evidence`
    COMMENT = '제조사 정비 주기의 원본 문서·차종·연식·적용 범위와 검증 근거',
    MODIFY COLUMN `id` CHAR(36) NOT NULL COMMENT '이 행을 식별하는 UUID',
    MODIFY COLUMN `document_url` VARCHAR(1000) NOT NULL COMMENT '제조사 정비 근거 원본 문서 HTTPS 주소',
    MODIFY COLUMN `document_title` VARCHAR(200) NOT NULL COMMENT '제조사 정비 근거 문서 제목',
    MODIFY COLUMN `manufacturer` VARCHAR(100) NOT NULL COMMENT '차량 제조사 이름',
    MODIFY COLUMN `model` VARCHAR(100) NOT NULL COMMENT '차종 또는 모델 이름',
    MODIFY COLUMN `model_year_from` INT NOT NULL COMMENT '근거 적용 시작 모델 연식(포함)',
    MODIFY COLUMN `model_year_to` INT NOT NULL COMMENT '근거 적용 종료 모델 연식(포함)',
    MODIFY COLUMN `service_name` VARCHAR(100) NOT NULL COMMENT '근거가 적용되는 정비 항목명. 계획 이름과 일치해야 함',
    MODIFY COLUMN `scope_text` VARCHAR(1000) NOT NULL COMMENT '차종·사용 조건 등 근거 문서의 적용 범위 설명',
    MODIFY COLUMN `interval_km` DECIMAL(12,3) NULL COMMENT '정비 반복 거리 주기(km). 거리 기준이 없으면 NULL',
    MODIFY COLUMN `interval_months` INT NULL COMMENT '정비 반복 달력 개월 수. 개월 기준이 없으면 NULL',
    MODIFY COLUMN `verified_at` DATETIME(6) NOT NULL COMMENT '서버 검증 완료 시각. UTC',
    MODIFY COLUMN `verified_by` VARCHAR(100) NOT NULL COMMENT '차량 또는 근거를 검증한 신뢰 가능한 서버 작업·관리자 식별값',
    MODIFY COLUMN `revoked_at` DATETIME(6) NULL COMMENT '검증 또는 권한 폐기 시각. UTC이며 미폐기 시 NULL';

ALTER TABLE `maintenance_plans`
    COMMENT = '차량 공동 또는 사용자 개인 정비 주기와 마지막 정비·다음 예정',
    MODIFY COLUMN `id` CHAR(36) NOT NULL COMMENT '이 행을 식별하는 UUID',
    MODIFY COLUMN `vehicle_id` CHAR(36) NOT NULL COMMENT '차량 UUID. vehicles.id 참조',
    MODIFY COLUMN `owner_user_id` CHAR(36) NOT NULL COMMENT '정비 계획을 소유한 Supabase 사용자 UUID',
    MODIFY COLUMN `name` VARCHAR(100) NOT NULL COMMENT '정비 항목 표시 이름',
    MODIFY COLUMN `shared` BOOLEAN NOT NULL COMMENT '차량 공동 공개 여부. TRUE는 공동 항목, FALSE는 개인 항목',
    MODIFY COLUMN `active` BOOLEAN NOT NULL COMMENT '정비 예정 계산·요약 노출 활성 여부. FALSE여도 과거 이력은 보존',
    MODIFY COLUMN `basis` VARCHAR(20) NOT NULL COMMENT '정비 주기 기준. USER=사용자 설정, MANUFACTURER=검증된 제조사 근거',
    MODIFY COLUMN `evidence_id` CHAR(36) NULL COMMENT '제조사 근거 UUID. maintenance_manufacturer_evidence.id 참조이며 사용자 기준은 NULL',
    MODIFY COLUMN `interval_km` DECIMAL(12,3) NULL COMMENT '정비 반복 거리 주기(km). 거리 기준이 없으면 NULL',
    MODIFY COLUMN `interval_months` INT NULL COMMENT '정비 반복 달력 개월 수. 개월 기준이 없으면 NULL',
    MODIFY COLUMN `revision` BIGINT NOT NULL COMMENT '정비 계획 변경 버전. HTTP ETag·If-Match에 사용',
    MODIFY COLUMN `last_service_date` DATE NULL COMMENT '다음 예정 계산에 사용한 마지막 확정 정비일. DATE',
    MODIFY COLUMN `last_service_km` DECIMAL(12,3) NULL COMMENT '마지막 확정 정비 당시 차량 누적거리(km)',
    MODIFY COLUMN `next_due_date` DATE NULL COMMENT '마지막 확정 정비일에 개월 주기를 더한 다음 정비 예정일',
    MODIFY COLUMN `next_due_km` DECIMAL(15,3) NULL COMMENT '마지막 확정 정비 누적거리에 거리 주기를 더한 다음 정비 예정 누적거리(km)',
    MODIFY COLUMN `created_at` DATETIME(6) NOT NULL COMMENT '행 생성 시각. UTC DATETIME(6)',
    MODIFY COLUMN `updated_at` DATETIME(6) NOT NULL COMMENT '행의 마지막 변경 시각. UTC DATETIME(6)';

ALTER TABLE `maintenance_events`
    COMMENT = '정비 확정·방문 추정 이력과 상세 JSON. 논리 삭제·중복 요청 관리',
    MODIFY COLUMN `id` CHAR(36) NOT NULL COMMENT '이 행을 식별하는 UUID',
    MODIFY COLUMN `vehicle_id` CHAR(36) NOT NULL COMMENT '차량 UUID. vehicles.id 참조',
    MODIFY COLUMN `item_id` CHAR(36) NOT NULL COMMENT '정비 계획 UUID. maintenance_plans.id 참조',
    MODIFY COLUMN `author_user_id` CHAR(36) NOT NULL COMMENT '정비 이력을 작성한 Supabase 사용자 UUID. 수정·삭제 주체',
    MODIFY COLUMN `shared` BOOLEAN NOT NULL COMMENT '차량 공동 공개 여부. TRUE는 공동 항목, FALSE는 개인 항목',
    MODIFY COLUMN `status` VARCHAR(30) NOT NULL COMMENT 'CONFIRMED=확정 정비, VISIT_ESTIMATE=방문 추정. 확정만 다음 정비 계산에 사용',
    MODIFY COLUMN `service_date` DATE NULL COMMENT '정비 수행일. YYYY-MM-DD이며 누적거리만 기록한 경우 NULL 가능',
    MODIFY COLUMN `odometer_km` DECIMAL(12,3) NULL COMMENT '정비 당시 차량 전체 누적거리(km)',
    MODIFY COLUMN `payload` LONGTEXT NOT NULL COMMENT '정비 입력 JSON. 공개 범위·detailsShared·비용·통화·메모·정비소·영수증 포함',
    MODIFY COLUMN `revision` BIGINT NOT NULL COMMENT '정비 이력 변경 버전. HTTP ETag·If-Match에 사용',
    MODIFY COLUMN `create_key` VARCHAR(100) NOT NULL COMMENT '차량·작성자 내 등록 재시도 키',
    MODIFY COLUMN `create_hash` BINARY(32) NOT NULL COMMENT '정규화한 최초 등록 본문의 SHA-256. 동일 키의 다른 내용 차단',
    MODIFY COLUMN `create_response` LONGTEXT NOT NULL COMMENT '최초 등록 응답 JSON. 같은 요청 재시도에 최초 응답을 반환하기 위해 보관',
    MODIFY COLUMN `created_at` DATETIME(6) NOT NULL COMMENT '행 생성 시각. UTC DATETIME(6)',
    MODIFY COLUMN `updated_at` DATETIME(6) NOT NULL COMMENT '행의 마지막 변경 시각. UTC DATETIME(6)',
    MODIFY COLUMN `deleted_at` DATETIME(6) NULL COMMENT '논리 삭제 시각. UTC이며 미삭제 시 NULL';

ALTER TABLE `maintenance_event_audit`
    COMMENT = '정비 이력 생성·수정·삭제의 행위자와 변경 전후 감사 기록',
    MODIFY COLUMN `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '서버 내부 자동 증가 식별 번호',
    MODIFY COLUMN `event_id` CHAR(36) NOT NULL COMMENT '감사 대상 정비 이력 UUID. maintenance_events.id 참조',
    MODIFY COLUMN `actor_user_id` CHAR(36) NOT NULL COMMENT '정비 이력 변경을 수행한 Supabase 사용자 UUID',
    MODIFY COLUMN `action` VARCHAR(20) NOT NULL COMMENT '감사 작업 종류. CREATE=생성, UPDATE=수정, DELETE=논리 삭제',
    MODIFY COLUMN `revision` BIGINT NOT NULL COMMENT '해당 작업을 반영한 정비 이력 버전',
    MODIFY COLUMN `before_payload` LONGTEXT NULL COMMENT '변경 전 정비 입력 JSON. 생성 시 NULL',
    MODIFY COLUMN `after_payload` LONGTEXT NULL COMMENT '변경 후 정비 입력 JSON. 삭제 시 NULL',
    MODIFY COLUMN `created_at` DATETIME(6) NOT NULL COMMENT '행 생성 시각. UTC DATETIME(6)';

ALTER TABLE `vehicle_odometer_samples`
    COMMENT = '장치에서 보고한 차량 원본 누적거리와 품질·중복 요청 검증 정보',
    MODIFY COLUMN `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '서버 내부 자동 증가 식별 번호',
    MODIFY COLUMN `sample_id` CHAR(36) NOT NULL COMMENT '장치가 생성한 원본 누적거리 샘플 UUID. 설치 내 재시도 키',
    MODIFY COLUMN `installation_id` CHAR(36) NOT NULL COMMENT '장치 앱 설치 UUID. vehicle_installations.id 참조',
    MODIFY COLUMN `vehicle_id` CHAR(36) NOT NULL COMMENT '차량 UUID. vehicles.id 참조',
    MODIFY COLUMN `km` DECIMAL(12,3) NULL COMMENT '차량 전체 누적거리(km). 구간 증가량이 아니며 VALID일 때만 저장',
    MODIFY COLUMN `observed_at` DATETIME(6) NOT NULL COMMENT '차량 누적거리 관측 시각(UTC). API의 epoch milliseconds를 변환해 저장',
    MODIFY COLUMN `unit` VARCHAR(10) NOT NULL COMMENT '거리 단위. km 고정',
    MODIFY COLUMN `source` VARCHAR(40) NOT NULL COMMENT '원본 출처 선언. PLEOS_VEHICLE_API 고정이며 하드웨어 진위 증명은 별도',
    MODIFY COLUMN `quality` VARCHAR(20) NOT NULL COMMENT 'VALID=정상, UNSUPPORTED=미지원, ERROR=측정 오류. VALID가 아니면 km는 NULL',
    MODIFY COLUMN `payload_hash` BINARY(32) NOT NULL COMMENT '재전송 내용의 동일성 비교용 SHA-256 해시. 원문 토큰이 아님',
    MODIFY COLUMN `received_at` DATETIME(6) NOT NULL COMMENT '서버 수신 시각. UTC';

ALTER TABLE `fuel_candidates`
    COMMENT = '연료 증가로 감지한 주유 후보의 증거·서버 추정값·사용자 확인 및 보정값',
    MODIFY COLUMN `id` CHAR(36) NOT NULL COMMENT '이 행을 식별하는 UUID',
    MODIFY COLUMN `installation_id` CHAR(36) NOT NULL COMMENT '장치 앱 설치 UUID. vehicle_installations.id 참조',
    MODIFY COLUMN `vehicle_id` CHAR(36) NOT NULL COMMENT '차량 UUID. vehicles.id 참조',
    MODIFY COLUMN `owner_id` CHAR(36) NOT NULL COMMENT '주유 후보 소유자의 Supabase 사용자 UUID',
    MODIFY COLUMN `interval_id` CHAR(36) NOT NULL COMMENT '운행 기록 구간 UUID. recording_intervals.id 참조',
    MODIFY COLUMN `start_at` BIGINT NOT NULL COMMENT '연료 증가 감지 시작 시각. UTC epoch milliseconds',
    MODIFY COLUMN `end_at` BIGINT NOT NULL COMMENT '연료 증가 감지 종료 시각. UTC epoch milliseconds',
    MODIFY COLUMN `increase_percent` DECIMAL(6,3) NOT NULL COMMENT '감지한 연료 잔량 증가율(%). 0 초과 100 이하',
    MODIFY COLUMN `status` VARCHAR(20) NOT NULL DEFAULT 'CANDIDATE' COMMENT 'CANDIDATE=확인 대기, CONFIRMED=주유 확인, REJECTED=기각',
    MODIFY COLUMN `revision` BIGINT NOT NULL DEFAULT 1 COMMENT '주유 후보 변경 버전. 모바일 expectedRevision 충돌 검사에 사용',
    MODIFY COLUMN `capacity_verified` BOOLEAN NOT NULL DEFAULT FALSE COMMENT '서버가 연료탱크 용량의 신뢰 근거를 확인했는지 여부',
    MODIFY COLUMN `estimated_liters` DECIMAL(12,3) NULL COMMENT '검증된 탱크 용량과 증가율로 추정한 주유량(L)',
    MODIFY COLUMN `estimated_amount` DECIMAL(15,2) NULL COMMENT '추정 주유량과 검증된 단가로 계산한 금액. 통화 필드는 이 테이블에 없음',
    MODIFY COLUMN `price_at` BIGINT NULL COMMENT '추정에 사용한 연료 단가 확인 시각. UTC epoch milliseconds',
    MODIFY COLUMN `corrected_place` VARCHAR(200) NULL COMMENT '사용자가 직접 보정한 주유 장소. 원래 감지 증거와 별도 보관',
    MODIFY COLUMN `corrected_liters` DECIMAL(12,3) NULL COMMENT '사용자가 직접 보정한 주유량(L)',
    MODIFY COLUMN `corrected_amount` DECIMAL(15,2) NULL COMMENT '사용자가 직접 보정한 주유 금액. 통화 필드는 이 테이블에 없음',
    MODIFY COLUMN `created_at` DATETIME(6) NOT NULL COMMENT '행 생성 시각. UTC DATETIME(6)',
    MODIFY COLUMN `updated_at` DATETIME(6) NOT NULL COMMENT '행의 마지막 변경 시각. UTC DATETIME(6)';

ALTER TABLE `vehicle_fuel_metadata`
    COMMENT = '서버가 검증한 연료탱크 용량과 단가·유효기간. 주유량·금액 추정 근거',
    MODIFY COLUMN `vehicle_id` CHAR(36) NOT NULL COMMENT '차량 UUID. vehicles.id 참조',
    MODIFY COLUMN `capacity_liters` DECIMAL(10,3) NULL COMMENT '서버가 확인한 연료탱크 용량(L)',
    MODIFY COLUMN `capacity_verified_at` DATETIME(6) NULL COMMENT '연료탱크 용량 서버 검증 시각. UTC',
    MODIFY COLUMN `capacity_source` VARCHAR(200) NULL COMMENT '연료탱크 용량의 신뢰 가능한 확인 출처',
    MODIFY COLUMN `unit_price` DECIMAL(12,3) NULL COMMENT '연료 1L당 검증 단가. 통화 필드는 이 테이블에 없음',
    MODIFY COLUMN `price_at` BIGINT NULL COMMENT '추정에 사용한 연료 단가 확인 시각. UTC epoch milliseconds',
    MODIFY COLUMN `price_valid_until` BIGINT NULL COMMENT '검증 단가 유효기간 종료 시각. UTC epoch milliseconds',
    MODIFY COLUMN `price_source` VARCHAR(200) NULL COMMENT '연료 단가의 신뢰 가능한 확인 출처';

ALTER TABLE `fuel_candidate_operations`
    COMMENT = '주유 후보 확인·기각·보정 요청과 응답 보관. 재시도 중복 방지 및 감사',
    MODIFY COLUMN `candidate_id` CHAR(36) NOT NULL COMMENT '변경 대상 주유 후보 UUID. fuel_candidates.id 참조',
    MODIFY COLUMN `operation_id` VARCHAR(100) NOT NULL COMMENT '주유 후보 내 모바일 수정 재시도 키',
    MODIFY COLUMN `actor_id` CHAR(36) NOT NULL COMMENT '작업을 수행한 Supabase 사용자 UUID',
    MODIFY COLUMN `request_payload` LONGTEXT NOT NULL COMMENT '확인·기각·보정 요청 JSON. 같은 키의 다른 요청 비교',
    MODIFY COLUMN `response_payload` LONGTEXT NOT NULL COMMENT '처음 저장한 수정 결과 JSON. 같은 요청 재시도에 반환',
    MODIFY COLUMN `previous_revision` BIGINT NOT NULL COMMENT '작업 직전 후보 버전',
    MODIFY COLUMN `resulting_revision` BIGINT NOT NULL COMMENT '작업 반영 후 후보 버전',
    MODIFY COLUMN `created_at` DATETIME(6) NOT NULL COMMENT '행 생성 시각. UTC DATETIME(6)';

ALTER TABLE `connection_operations`
    COMMENT = '사용자 해제·차량 초기화 요청과 최초 대상·등록 버전·관리자 결정 이력',
    MODIFY COLUMN `id` CHAR(36) NOT NULL COMMENT '이 행을 식별하는 UUID',
    MODIFY COLUMN `installation_id` CHAR(36) NOT NULL COMMENT '장치 앱 설치 UUID. vehicle_installations.id 참조',
    MODIFY COLUMN `operation_id` VARCHAR(100) NOT NULL COMMENT '설치 또는 대상 내에서 같은 요청 재시도에 유지하는 고유 키',
    MODIFY COLUMN `kind` VARCHAR(10) NOT NULL COMMENT 'USER=사용자 해제, VEHICLE=관리자 승인이 필요한 차량 초기화',
    MODIFY COLUMN `status` VARCHAR(30) NOT NULL COMMENT 'AWAITING_ADMIN=관리자 승인 대기, COMPLETED=완료, REJECTED=기각',
    MODIFY COLUMN `vehicle_id` CHAR(36) NULL COMMENT '요청 최초 대상 차량 UUID. 이후 연결 변경에도 이력 값 유지',
    MODIFY COLUMN `user_id` CHAR(36) NULL COMMENT '요청 최초 확인된 사용자 UUID. 확인된 사용자가 없으면 NULL',
    MODIFY COLUMN `registration_version` BIGINT NOT NULL COMMENT '설치와 차량의 연결 변경 때 증가하는 버전. 이전 연결의 요청·설정 재사용 방지',
    MODIFY COLUMN `session_version` BIGINT NOT NULL COMMENT '연결 해제 요청 시작 시점의 설치 세션 버전 스냅샷',
    MODIFY COLUMN `decision_user_id` CHAR(36) NULL COMMENT '차량 초기화를 승인·기각한 관리자 Supabase UUID. 결정 전 또는 사용자 해제는 NULL 가능',
    MODIFY COLUMN `pending_records_policy` VARCHAR(40) NULL COMMENT '승인한 기록 보존 정책. RETAIN_ORIGINAL_OWNER=기존 소유권 유지',
    MODIFY COLUMN `created_at` DATETIME(6) NOT NULL COMMENT '행 생성 시각. UTC DATETIME(6)',
    MODIFY COLUMN `completed_at` DATETIME(6) NULL COMMENT '요청 처리 완료 시각(UTC). 승인 대기 상태는 NULL';

ALTER TABLE `connection_reauth_uses`
    COMMENT = '차량 초기화에 소비한 OTP·TOTP 인증 사건. JWT 갱신을 통한 증명 재사용 방지',
    MODIFY COLUMN `proof_hash` BINARY(32) NOT NULL COMMENT '사용자 UUID·OTP/TOTP 방식·인증 시각 조합의 SHA-256. JWT 원문 해시가 아님',
    MODIFY COLUMN `operation_id` CHAR(36) NOT NULL COMMENT '재인증을 소비한 connection_operations.id. 클라이언트 operation_id 문자열과 다름',
    MODIFY COLUMN `actor_id` CHAR(36) NOT NULL COMMENT '작업을 수행한 Supabase 사용자 UUID',
    MODIFY COLUMN `authenticated_at` DATETIME(6) NOT NULL COMMENT 'Supabase amr에 기록된 OTP/TOTP 인증 시각. UTC',
    MODIFY COLUMN `used_at` DATETIME(6) NOT NULL COMMENT '서버가 해당 인증 사건을 초기화 승인에 소비한 시각. UTC';

ALTER TABLE `installation_capabilities`
    COMMENT = '설치의 현재 차량 등록 버전에 대한 연료·거리·위치 SDK 기능 보고',
    MODIFY COLUMN `installation_id` CHAR(36) NOT NULL COMMENT '장치 앱 설치 UUID. vehicle_installations.id 참조',
    MODIFY COLUMN `registration_version` BIGINT NOT NULL COMMENT '설치와 차량의 연결 변경 때 증가하는 버전. 이전 연결의 요청·설정 재사용 방지',
    MODIFY COLUMN `observed_at` BIGINT NOT NULL COMMENT '장치 SDK 기능 관측 시각. UTC epoch milliseconds',
    MODIFY COLUMN `fuel` VARCHAR(30) NOT NULL COMMENT '연료 기능 상태. AVAILABLE/UNSUPPORTED/PERMISSION_REQUIRED/TEMPORARY_FAILURE/UNKNOWN',
    MODIFY COLUMN `distance` VARCHAR(30) NOT NULL COMMENT '거리 기능 상태. AVAILABLE/UNSUPPORTED/PERMISSION_REQUIRED/TEMPORARY_FAILURE/UNKNOWN',
    MODIFY COLUMN `location` VARCHAR(30) NOT NULL COMMENT '위치 기능 상태. AVAILABLE/UNSUPPORTED/PERMISSION_REQUIRED/TEMPORARY_FAILURE/UNKNOWN';

ALTER TABLE `recommendation_registrations`
    COMMENT = '서버가 확인한 설치·차량 등록 버전별 추천 기능 등록·검증 상태',
    MODIFY COLUMN `installation_id` CHAR(36) NOT NULL COMMENT '장치 앱 설치 UUID. vehicle_installations.id 참조',
    MODIFY COLUMN `registration_version` BIGINT NOT NULL COMMENT '설치와 차량의 연결 변경 때 증가하는 버전. 이전 연결의 요청·설정 재사용 방지',
    MODIFY COLUMN `registered` BOOLEAN NOT NULL DEFAULT FALSE COMMENT '현재 설치·차량 등록 버전에 추천 기능이 서버 등록되었는지 여부',
    MODIFY COLUMN `verified` BOOLEAN NOT NULL DEFAULT FALSE COMMENT '서버가 추천 기능 사용 조건을 검증했는지 여부. 장치 보고만으로 TRUE가 되지 않음',
    MODIFY COLUMN `verified_at` DATETIME(6) NULL COMMENT '서버 검증 완료 시각. UTC',
    MODIFY COLUMN `verification_source` VARCHAR(200) NULL COMMENT '추천 기능 검증의 신뢰 가능한 서버 출처';

ALTER TABLE `recommendation_consents`
    COMMENT = '설치·차량 등록 버전·사용자별 추천 기능 동의와 활성화 선택',
    MODIFY COLUMN `installation_id` CHAR(36) NOT NULL COMMENT '장치 앱 설치 UUID. vehicle_installations.id 참조',
    MODIFY COLUMN `registration_version` BIGINT NOT NULL COMMENT '설치와 차량의 연결 변경 때 증가하는 버전. 이전 연결의 요청·설정 재사용 방지',
    MODIFY COLUMN `user_id` CHAR(36) NOT NULL COMMENT '데이터 주체의 Supabase 사용자 UUID',
    MODIFY COLUMN `consented` BOOLEAN NOT NULL COMMENT '사용자의 추천 기능 동의 여부. FALSE는 동의 철회',
    MODIFY COLUMN `enabled` BOOLEAN NOT NULL COMMENT '사용자가 선택한 추천 활성화 여부. 실제 활성에는 등록·검증·동의·현재 사용자 조건도 필요',
    MODIFY COLUMN `updated_at` DATETIME(6) NOT NULL COMMENT '행의 마지막 변경 시각. UTC DATETIME(6)';

ALTER TABLE `installation_sync_checkpoints`
    COMMENT = '서버가 검증한 설치·차량 등록 버전·사용자별 큐 완료 확인 지점',
    MODIFY COLUMN `installation_id` CHAR(36) NOT NULL COMMENT '장치 앱 설치 UUID. vehicle_installations.id 참조',
    MODIFY COLUMN `registration_version` BIGINT NOT NULL COMMENT '설치와 차량의 연결 변경 때 증가하는 버전. 이전 연결의 요청·설정 재사용 방지',
    MODIFY COLUMN `user_id` CHAR(36) NOT NULL COMMENT '데이터 주체의 Supabase 사용자 UUID',
    MODIFY COLUMN `pending_count` BIGINT NULL COMMENT '서버 큐 조정 작업이 확인한 사용자별 대기 건수. 모르면 NULL이며 장치 로컬 큐 건수가 아님',
    MODIFY COLUMN `last_acknowledged_at` BIGINT NULL COMMENT '서버가 마지막 수신 완료를 확인한 시각. UTC epoch milliseconds',
    MODIFY COLUMN `verified_at` BIGINT NULL COMMENT '서버 큐 조정 작업이 상태를 검증한 시각. UTC epoch milliseconds이며 미확인 시 NULL';
