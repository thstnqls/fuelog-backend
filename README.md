# Fuelog Backend

데이터 저장용 Spring Boot API 백엔드 초기 구성입니다.

## 기술 구성

Java 21 / Gradle 9.4.1 / Spring Boot 4.1.2 / Spring MVC / Validation /
Spring Data JPA / MariaDB 11.4 / Flyway / Actuator를 사용합니다.
Spring Boot 관련 의존성은 BOM으로 관리하며 springdoc-openapi 버전은 별도로 지정합니다.
[Spring Boot 버전 호환 기준](https://docs.spring.io/spring-boot/system-requirements.html)을 참고합니다.
빌드에는 JDK 21이 필요하며 IntelliJ Project SDK도 21로 지정합니다.

## 로컬 실행과 .env 위치

로컬 설정은 프로젝트 루트의 `.env.local`에 둡니다. Spring Boot의 local 프로파일이
이 파일을 직접 읽습니다. Git에는 예제인 `.env.example`만 포함됩니다.

```bash
# 새로 clone한 경우 한 번만 복사
cp .env.example .env.local
# .env.local의 DB_URL, DB_USERNAME, DB_PASSWORD, SUPABASE_URL을 수정
./gradlew bootRun
```

이 저장소에는 Compose 파일이 없습니다. MariaDB 서버와 사용할 데이터베이스를
별도로 준비해야 합니다. `DB_URL`에는 반드시 데이터베이스 이름까지 넣습니다.
예: `jdbc:mariadb://localhost:3306/fuelog`. IntelliJ에서는 Working directory를
프로젝트 루트로 설정하거나 환경변수를 직접 등록합니다.

예제의 DB 접속은 `localhost:3306/fuelog`, 사용자/비밀번호는 `fuelog` / `fuelog_local`입니다.
DB_PORT를 바꾸면 DB_URL의 포트도 함께 변경합니다.
MariaDB 계정과 데이터베이스는 사용하는 DB 서버에 별도로 만들어야 합니다.

```bash
curl http://localhost:8080/actuator/health
# 정상 응답: {"status":"UP"}
```

DB 데이터 보존은 사용 중인 MariaDB 운영 방식에 따릅니다.

## 환경별 설정

| 프로파일 | DB | 스키마 | 로그 |
| --- | --- | --- | --- |
| local (기본) | MariaDB, 개발 기본값 또는 환경변수 | Flyway + JPA validate | 앱/SQL DEBUG |
| prod | 접속 환경변수 필수 | Flyway + JPA validate | INFO, SQL 로그 억제 |
| test | 인메모리 H2 | create-drop, Flyway 비활성 | INFO |

공통 설정은 `src/main/resources/application.yml`, 환경별 설정은
`application-local.yml`, `application-prod.yml`입니다.
테스트 설정은 `src/test/resources/application-test.yml`에만 존재합니다.
OSIV가 꺼져 있으므로 데이터 조회와 DTO 변환은 서비스 트랜잭션 내에서 처리합니다.

## 운영 실행

```bash
./gradlew clean build
```

운영 DB와 사용자를 먼저 준비하고 배포 환경에 아래 환경변수를 주입합니다.
운영 비밀값은 저장소에 보관하지 않습니다.

| 변수 | 설명 | 기본값 |
| --- | --- | --- |
| SPRING_PROFILES_ACTIVE | 운영은 반드시 prod | local |
| DB_URL | jdbc:mariadb://호스트:3306/DB이름 | prod 필수 |
| DB_USERNAME | DB 사용자 | prod 필수 |
| DB_PASSWORD | DB 비밀번호 | prod 필수 |
| SUPABASE_URL | Supabase 프로젝트 URL. JWT issuer/JWKS 주소의 기준 | 필수 |
| FUELOG_JWT_ISSUER | 기본 Supabase issuer를 재정의할 때만 사용 | `${SUPABASE_URL}/auth/v1` |
| FUELOG_JWK_SET_URI | 기본 Supabase JWKS를 재정의할 때만 사용 | `${SUPABASE_URL}/auth/v1/.well-known/jwks.json` |
| SERVER_PORT | HTTP 포트 | 8080 |
| DB_POOL_MAX_SIZE | 운영 최대 연결 수 | 10 |
| DB_POOL_MIN_IDLE | 운영 최소 유휴 연결 수 | 2 |

```bash
java -jar build/libs/fuelog-backend-0.0.1-SNAPSHOT.jar --spring.profiles.active=prod
```

Flyway가 시작 시 실행되므로 DB 사용자에게 마이그레이션에 필요한 DDL 권한이 있어야 합니다.
종료 시 진행 중인 요청을 최대 30초간 처리합니다.
관리 엔드포인트는 `/actuator/health`만 노출하며 DB 상세 정보는 숨깁니다.
모바일 API는 Supabase Auth의 비대칭 서명 JWT를 검증합니다. JWT의 `iss`, `aud`,
서명, 만료를 확인하며 `aud` 기본값은 `authenticated`입니다. Supabase 프로젝트가
기존 HS256 서명을 쓴다면 JWKS에 공개 키가 없으므로 비대칭 서명 키로 전환해야 합니다.
익명 사용자는 모바일 페어링 승인을 할 수 없습니다. 차량 앱은 Supabase 계정이
필요 없습니다. 차량 앱의 주행 제한 판정은 클라이언트에서 CarUxRestrictions
현재값과 변경 콜백으로 처리합니다.

`GET /auth/me`는 Supabase access token을 검증한 뒤 JWT의 사용자 ID(`sub`),
이메일, 휴대폰, 역할, 익명 여부, 세션 ID, 발급·만료 시각을 반환합니다.
`Authorization: Bearer <access_token>` 헤더로 호출합니다. 토큰의 발급 시점
정보만 읽으며 Supabase Auth 사용자 레코드를 새로 조회하지 않습니다.

### Android C01 / 모바일 M02 연결 계약

새 Android 클라이언트는 설치마다 32바이트 이상 난수를 만들어 백업 제외 저장소에
보관하고 `Authorization: Bearer <base64url 자격 증명>`으로 HTTPS JSON POST를
호출합니다. 서버는 최초 `POST /connection/requests`에서 자격 증명 해시로 설치를
등록하고, 이후 같은 해시로 소유권을 검사합니다. `operationId`는 최대 100자의
고유 문자열이며 앱이 재시도와 프로세스 복구 때 유지합니다. 최초 `requestId`는
빈 문자열 또는 `null`입니다.
Android의 `API_BASE_URL`은 배포된 HTTPS 서버 주소로 설정해야 합니다.

| 경로 | 인증 | 입력 | 성공 결과 |
| --- | --- | --- | --- |
| `POST /connection/requests` | 설치 Bearer | `operationId`, `requestId` | `requestId`, `status`, WAITING 때 `remainingSeconds`, `nonce` |
| `POST /connection/requests/status` | 설치 Bearer | `operationId`, `requestId` | 같은 상태 형식 |
| `POST /mobile/connection/inspect` | Supabase JWT | QR의 `type`, `version`, `requestId`, `nonce` | `requestId`, `remainingSeconds` |
| `POST /mobile/connection/approve` | Supabase JWT | `requestId`, `nonce`, 기존 `vehicleId`, `idempotencyKey` | `requestId`, `vehicleId`, `COMPLETED` |
| `POST /connection/reset` | 설치 Bearer + `X-User-Authorization: Bearer <Supabase JWT>` | `vehicleId` | 설치 ID와 `RESET` |

QR JSON은 `{ "type":"fuelog.vehicle-registration", "version":1,
"requestId":"...", "nonce":"..." }`입니다. URL이나 차량 정보, 설치 자격
증명을 QR에 넣지 않습니다. 같은 `operationId`로 재시도하면 같은 요청 ID와 nonce를
돌려줍니다. nonce는 설치 자격 증명과 요청 ID에 대한 HMAC-SHA256으로 계산하고,
DB에는 SHA-256 해시만 저장합니다. 새 `operationId`는 이전 활성 QR을 폐기합니다.
모든 연결 응답은 `Cache-Control: no-store`입니다. WAITING의 남은 시간은 서버
만료 시각 기준이며 최대 120초입니다.
HTTP 429 제한은 운영 게이트웨이에서 적용해야 하며 이 저장소에는 아직 요청
속도 제한기가 없습니다.

모바일 승인은 기존 차량 OWNER/MANAGER 권한을 검사하고 nonce 소비, 설치 연결,
완료 상태를 한 트랜잭션으로 처리합니다. 같은 승인 키·사용자·차량 재시도는
COMPLETED를 돌려줍니다. 새 차량 생성은 이 경로에서 지원하지 않습니다.
초기화에는 최근 5분 이내 `otp` 또는 `totp` 인증 시각이 `amr`에 기록된 사용자
JWT가 필요합니다. 초기화는 설치 연결을 해제하고 해당 설치 요청을 REVOKED로
바꿉니다. 운행·주유·정비 이력과 차량은 삭제하지 않습니다.

설치 자격 증명의 해시만으로는 실제 Pleos 기기임을 증명할 수 없습니다. 기기
증명은 별도 프로비저닝 계약이 필요합니다. 또한 현재 서버는 신뢰할 수 있는
주행 상태 승인 허가를 받지 않으므로 주행 제한 중 모바일 승인을 차단하지
않습니다. 출시 전에 이 계약과 차량·모바일 통합 검증이 필요합니다.

아래의 `/installations`와 `/pairing-requests` 경로는 기존 차량 화면 최종 확인
흐름을 사용하는 클라이언트를 위한 별도 계약입니다.

### C02 차량 홈과 수동 운행 기록

C02 차량 API는 C01과 같은 설치 Bearer 자격 증명을 사용합니다. 설치가 차량에
연결되어 있어야 합니다. 개인 홈에는 모바일 QR 승인으로 받은 짧은 운행 사용자
세션을 `X-Trip-User-Session: Bearer <scopedToken>`으로 추가합니다. 이 헤더가
없으면 개인 거리·비용·알림은 `null`/`needs_user_proof`이며, 무효·폐기된 토큰은
401/403입니다. 앱은 이때 개인 캐시를 즉시 지워야 합니다.

| API | 호출 주체 | 설명 |
| --- | --- | --- |
| `GET /vehicle/home` | 차량 앱 | 차량 별칭, 세션 버전, 수동 기록·거리·연료·비용·정비·동기화의 독립 상태 |
| `POST /trip-user/challenges` | 차량 앱 | `installationId`, `operationId`로 2분 QR 확인 요청 발급 |
| `POST /mobile/trip-user/approve` | Supabase 로그인 모바일 | `challengeId`, `proof`, `vehicleId`로 차량 멤버십 확인 |
| `POST /trip-user/session` | 차량 앱 | 승인 상태 조회. ACTIVE일 때 표시명·subjectId·버전·만료·scopedToken 반환 |
| `DELETE /trip-user/session` | 차량 앱 | 현재 세션 폐기 및 버전 증가 |
| `POST /recording-intervals` | 차량 앱 | `localIntervalId`, `startedAt`, `source=manual_app`로 구간 시작 |
| `POST /recording-intervals/{id}/samples` | 차량 앱 | `batchId`와 `samples` 배열의 오프라인 재전송 |
| `POST /recording-intervals/{id}/finish` | 차량 앱 | `operationId`, `endedAt`, `lastSequence`로 종료 |
| `GET /sync/status` | 차량 앱 | 서버가 수락한 최신 구간의 sequence와 수신 시각 |

운행 사용자 QR은 `{ "type":"fuelog.trip-user", "version":1,
"challengeId":"...", "proof":"..." }` JSON입니다. proof는 설치 자격
증명에서 요청별 HMAC으로 계산하고 서버에는 해시만 저장합니다. 휴대폰은 앱 내부
스캐너로 읽어 HTTPS 요청 본문에 보냅니다. 모바일 사용자의 차량 `group_members`
멤버십을 확인하며 이름은 인증 수단으로 사용하지 않습니다. 승인된 사용자 세션은
설치·차량·사용자에 묶이고 30분 뒤 만료됩니다. 새 확인 요청·승인·로그아웃·연결
초기화는 `sessionVersion`을 증가시키고 이전 세션을 폐기합니다.

샘플 요청 예시는 아래와 같습니다. `distanceKm`는 해당 샘플에서 늘어난 km이고,
누락 샘플은 거리 대신 `missingReason`을 보냅니다. 연료 값이 잘못되어도 거리
샘플은 수락하고 연료 상태만 `error`로 저장합니다.

```json
{
  "batchId": "batch-001",
  "samples": [
    {"sequence": 1, "observedAt": "2026-11-04T10:00:03Z", "distanceKm": 0.125},
    {"sequence": 2, "observedAt": "2026-11-04T10:00:06Z", "missingReason": "odometer_reset"}
  ]
}
```

같은 `localIntervalId`와 같은 배치 내용의 재전송은 중복 저장하지 않습니다. 종료
후 새 샘플은 거부하되 이미 수락한 배치 재시도는 같은 결과를 반환합니다. 시작
시점에 운행 사용자 세션이 없던 구간은 `user_id=null`로 고정되어 나중 로그인한
사용자의 거리 합계에 포함하지 않습니다. 구간과 누락 sequence는 보존합니다.

홈 응답의 각 상태에는 `state`와 `observedAt`이 있으며 값은 `available`,
`stale`, `unsupported`, `error`, `needs_user_proof` 중 하나입니다. 연료·비용·
정비·알림 실제 산출 API는 아직 없어 확인된 사용자에게도 미지원 값은 `null`
입니다. 비용이 생기면 금액과 통화를 함께 반환하는 `Money` 형식을 사용합니다.
`/sync/status`의 `pendingCount`는 기기 로컬 업로드 큐를 서버가 알 수 없어
`null`입니다. 모든 응답은 `Cache-Control: no-store`입니다.
이 서버는 시동·자동 시작·백그라운드 수집을 추론하거나 시작하지 않습니다.
Android의 현재 로컬 구간 저장과 이 업로드 API는 아직 통합되지 않았으며,
실차 거리 권한·지원 여부와 오프라인 재전송은 Android/모바일과 함께 검증해야
합니다.

### C03 이번 운행 사용자 확인

현재 기록 중인 구간에 대해 차량 앱이 `POST /trip-user/challenges`에
`{"intervalId":"<구간 UUID>"}`를 보내면 2분짜리 QR 페이로드를 받습니다.
모바일은 Supabase JWT로 QR의 `challengeId`와 `proof`, 명시적으로 동의할
`vehicleId`, `installationId`, `intervalId`, 고정된 `operationId`를
`POST /mobile/trip-user/consent` 본문에 보냅니다. proof는 URL에 넣지 않으며
서버에는 해시만 보관합니다. 이 경로는 기존 C02 개인 홈 세션 승인이 아니므로
`/mobile/trip-user/approve`로 사용할 수 없습니다.

| API | 인증 | 역할 |
| --- | --- | --- |
| `POST /trip-user/candidates` | 설치 Bearer | `{ "intervalId": "<UUID>" }`로 같은 설치·차량·구간의 동의한 후보 조회 |
| `POST /trip-user/verify` | 설치 Bearer | `intervalId`, `candidateId`, `operationId`로 차량 화면의 명시적 선택·확인. 응답에 `subjectId`, `expiresAtEpochMillis` 제공 |
| `POST /mobile/trip-user/consent` | 모바일 Supabase JWT | 현재 구간·차량 멤버십 확인 후 5분 후보 생성. 응답에 `consentId`·`candidateId` 제공 |
| `POST /trip-user/revoke` | 설치 Bearer 또는 동의한 모바일 사용자 JWT | `consentId`의 후보와 향후 사용 권한 폐기 |
| `GET /trip-user/events?cursor=0` | 설치 Bearer | 후보 변경의 `revision`과 종류만 조회. 이름·사용자 ID는 포함하지 않음 |

후보가 한 명이어도 차량 화면의 `verify`가 필요합니다. 다른 설치·차량·구간은
403, 만료·폐기는 410, 일회용 후보를 다른 `operationId`로 재사용하거나 같은
키에 다른 본문을 보내면 409입니다. 검증 결과는 기존 구간·샘플의 소유자를
변경하지 않습니다. 이후 새 샘플을 해당 사용자에게 기록하려면
`POST /recording-intervals/{id}/samples`에
`X-Trip-User-Candidate: <검증된 candidateId>` 헤더를 보냅니다. 서버는 매 배치마다
구간·설치·차량·후보 유효기간·멤버십을 재검사하고, 헤더가 없던 이전 익명 샘플은
익명으로 유지합니다. 구간을 종료하고 새 구간을 시작하면 새 모바일 동의와 차량
확인이 필요합니다. 주행 제한 시 화면 전환과 검증 결과 적용을 보류하는 판단은
차량 앱에서 수행해야 하며 실차·모바일 통합 검증은 아직 필요합니다.

### 차량 앱 최초 등록

C04 운행 요약·오프라인 업로드와 M06 확인함 계약은
[C04 API 문서](docs/C04_API.md)를 참고하세요. V8에서 요약과 확인함 테이블을 추가합니다.

차량 앱은 최초 실행 때 인증 없이 `POST /installations`를 호출합니다. 서버가
설치 ID(UUID)와 암호학적 난수로 만든 `installation_secret`을 한 번 반환하고
비밀값의 SHA-256 해시만 DB에 저장합니다. 차량 앱은 설치 ID와 비밀값을 앱
전용 저장소에 보관하고, 비밀값은 Android Keystore 기반으로 보호합니다. 이후
차량 API 호출에 `X-Installation-Id`, `X-Installation-Secret` 헤더를 함께 보냅니다.
비밀값은 QR, URL, 로그, 모바일 응답에 넣지 않습니다. 설치 앱을 삭제해 비밀값을
잃으면 기존 설치 ID를 복원할 수 없으므로 별도 관리자 재등록 절차가 필요합니다.
공개 등록 API의 대량 호출은 운영 환경의 API 게이트웨이에서 제한해야 합니다.

페어링 흐름: 차량 앱이 설치 헤더로 `POST /pairing-requests`를 호출해 QR을
발급하고 휴대폰은
`POST /pairing-requests/preview` 본문에 QR의 `request_id`, `proof`를 보냅니다.
휴대폰의 `POST /pairing-requests/{id}/intent`도 `proof`를 본문에 담으며,
`vehicle_id` 또는 `new_vehicle_name` 중 하나만 보냅니다. intent 접수 때 서버가
새 `confirmation_code`를 만들어 모바일 응답과 차량 상태 조회에 표시합니다.
차량 앱이 동일한 코드를 확인하고 설치 헤더와 코드가 담긴 본문으로
`POST /pairing-requests/{id}/confirm`을 호출합니다. 서버는 차량 앱의 비밀값과
코드를 확인하며, 차량 앱은 사용자의 수동 확인 전에는 confirm을 호출하지 않아야
합니다. 차량 상태 응답의 `approved_user_id`와 `selected_vehicle_name`도 표시해
어느 승인 시도인지 확인할 수 있게 합니다. QR 사진과 모바일 로그인만으로는
연결을 완료할 수 없습니다.
발급과 intent에는 `Idempotency-Key` 헤더가 필요합니다. 발급 응답의 proof는
해시만 저장하므로 같은 키로 발급을 재요청하면 409가 반환됩니다. 앱은 최초
응답을 메모리에 보관하고, 잃어버렸다면 새 키로 재발급해야 합니다. `current`는
상태만 복원하며 proof를 돌려주지 않습니다.

QR은 발급 응답의 `qr` 객체를 JSON으로 직렬화해 그립니다. URL, 차량 이름, VIN,
차량 ID, 로그인 토큰, 설치 비밀값을 QR에 넣지 않습니다. 모바일은 QR을 직접
파싱하고 proof를 HTTPS 요청 본문으로만 보냅니다. 요청 ID는 화면에 앞 8자처럼
짧게 표시해 문의에 사용하고, intent 이후의 `confirmation_code`는 두 화면에서
비교합니다. 코드만 같다는 이유로 자동 승인해서는 안 됩니다.

C01 차량 앱은 발급에 실패하거나 오프라인이면 QR을 숨기고 네트워크 안내를
표시해야 합니다. 카운트다운은 응답의 `expires_at`을 기준으로 하며, 재발급하면
이전 QR은 즉시 `revoked`가 됩니다. 화면 회전·재시작 뒤에는 `/installations/me`와
`/pairing-requests/current`로 상태를 복원합니다. 연결 상태면 C07로 이동합니다.
CarUxRestrictions의 현재값과 변경 콜백에서 주행 제한을 확인하고, 제한 중에는
QR 표시와 최종 확인을 중단해야 합니다. 복귀 시 제한 상태와 서버 요청 상태를
다시 조회합니다. 이 저장소는 백엔드이므로 차량의 실제 정차 여부를 직접 읽지
않으며, 차량 앱의 제한 적용이 필요합니다.

요청 만료나 취소는 QR 검증에서 HTTP 410, 이미 진행 중이거나 완료된 요청은
409, proof 불일치는 403입니다. 설치 비밀값이 틀리면 401입니다. 연결 취소는
요청 상태와 설치 연결만 바꾸며 운행·주유 기록 삭제 API가 없습니다. 새 C01
계약의 C07 초기화는 위 `/connection/reset`을 사용합니다.

## Swagger / OpenAPI

[springdoc-openapi 3.1.1](https://springdoc.org/getting-started.html)을 사용해
컨트롤러의 API 문서를 자동 생성합니다.

- Swagger UI: http://localhost:8080/swagger-ui.html
- OpenAPI JSON: http://localhost:8080/v3/api-docs
- OpenAPI YAML: http://localhost:8080/v3/api-docs.yaml

local에서는 기본 활성화, prod에서는 기본 비활성화입니다.
환경변수 `SWAGGER_ENABLED=true` 또는 `false`로 UI와 문서 노출을 함께 변경합니다.
`.env.local`에 추가했다면 앱을 재시작합니다.

문서 제목, 설명, 버전은 `common/config/OpenApiConfig.java`에서 관리합니다.
Swagger의 Supabase Bearer 인증은 모바일 API용입니다. 새 차량 C01 API는
설치 자격 증명을 Authorization Bearer 헤더에 사용하며, 기존 차량 API는 설치
ID와 비밀값 헤더를 사용합니다. Actuator는 API 문서에서 제외됩니다.

## 패키지 구성

```text
app.fuelog
├── FuelogApplication
├── common
│   ├── config          # JPA Auditing 등 공통 설정
│   └── entity          # 생성/수정 시각을 갖는 BaseTimeEntity
└── domain              # 기능별 패키지를 추가할 위치
```

새 기능은 `domain.<기능명>` 아래 다음 계층으로 구성합니다.

```text
domain.<기능명>
├── controller          # HTTP 요청/응답과 @Valid 검증
├── dto                 # 요청/응답 객체 (record 권장)
├── service             # 비즈니스 로직과 @Transactional 경계
├── repository          # 저장소 계층 (필요 시)
└── entity              # JPA 엔티티
```

현재 페어링 코드는 `app.fuelog`의 controller/dto/service/repository/entity에
구현되어 있으며, 트랜잭션 서비스가 저장소의 DB 잠금과 조건부 갱신을 묶습니다. 다른 도메인은
필요에 따라 JPA 엔티티와 저장소를 사용할 수 있습니다.
시각은 Instant로 표현하고 JDBC 및 로컬 DB 세션의 기준 시간대는 UTC입니다.
기본 MVC 오류는 Problem Details 형식을 사용합니다.

## DB 마이그레이션

전체 업무 테이블 30개와 컬럼 286개의 용도·타입·NULL·기본값은
[DB 스키마 설명](docs/DATABASE_SCHEMA.md)에 정리되어 있습니다.
V13은 기존 컬럼 속성과 제약을 유지하면서 모든 업무 테이블·컬럼에 한국어 COMMENT를 추가합니다.

`src/main/resources/db/migration`에 `V1__create_<table>.sql`부터 추가합니다.
이미 적용한 파일은 수정하지 않고 후속 버전 파일을 추가합니다.
local/prod 모두 Flyway 적용 후 JPA가 스키마를 검증합니다.
V1 마이그레이션이 vehicles, group_members, vehicle_installations,
pairing_requests 테이블과 유일성 제약을 생성합니다.
V2는 설치 비밀값의 해시 컬럼을, V3는 intent 접수 때만 확인 코드를 발급할 수
있도록 nullable 코드 컬럼을 추가합니다. V4는 설치 자격 증명 해시의 유일성
제약을 추가합니다. V5는 운행 사용자 확인 요청·세션, 수동 기록 구간·배치·샘플과
설치별 세션 버전을 추가합니다. V6는 C03 후보·이벤트와 샘플별 검증 사용자
컬럼을 추가합니다. 폐기 가능한 DB에서만 `local,seed` 프로파일을 사용하면
V7의 테이블별 목업 `INSERT`가 적용됩니다. 적용 방법과 주의점은
[`db/migration/README.md`](src/main/resources/db/migration/README.md)에 있습니다.
local 프로파일은 기존 DB에 Flyway 이력 없이 다른 테이블이 있을 때 version 0으로
baseline을 만든 뒤 V1을 적용합니다. 운영 DB는 Flyway 이력과 기존 스키마를
별도로 검토한 뒤 마이그레이션해야 합니다.

## 검증

```bash
./gradlew test bootJar
```

테스트는 외부 DB 없이 애플리케이션/JPA 컨텍스트 시작, health 응답,
관리 엔드포인트 노출 범위, OpenAPI 문서, 차량 연결·운행 사용자 세션·수동 기록
멱등 처리 및 Swagger UI 제공을 검증합니다.
H2 테스트는 MariaDB 고유 SQL과 Flyway 검증을 대체하지 않으므로
실제 SQL 추가 시 MariaDB 통합 테스트도 추가합니다.
