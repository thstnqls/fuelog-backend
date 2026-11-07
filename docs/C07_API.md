# C07 연결·설정 / M14 초기화 승인

모든 시각은 epoch milliseconds이고 응답은 `Cache-Control: no-store`다.
`/connection/**`, `/installation/**`은 설치 Bearer, `/mobile/**`은 Supabase 사용자 JWT를
사용한다. 설치 비밀값은 사용자 해제 및 차량 초기화 후에도 유지한다.

## 설정과 기능 보고

`POST /connection/settings`은 `{ "kind": "", "operationId": "고정 키" }` 조회 envelope를
받으며 상태를 변경하지 않는다. deviceAlias는 서버에 등록된 기기 별칭이며 없으면 null이다.
features의 fuel/distance/location은 AVAILABLE / UNSUPPORTED / PERMISSION_REQUIRED /
TEMPORARY_FAILURE / UNKNOWN 중 하나다.

`PUT /installation/capabilities`:

```json
{
  "observedAt": 1793926800000,
  "features": {
    "fuel": "AVAILABLE",
    "distance": "PERMISSION_REQUIRED",
    "location": "TEMPORARY_FAILURE"
  }
}
```

설치 및 현재 등록 버전별로 최신 보고만 반영한다. 같은/이전 observedAt은 덮어쓰지 않는다.
미보고·다른 등록 버전·60초 초과 또는 미래 보고는 조회 시 UNKNOWN이다. 허용 시계 오차는
보고 수락 기준 1초다. 실제 가용성 확인에는 현재 시각 이전 보고만 사용한다. 기능 보고는
클라이언트 SDK의 진단 상태이며 추천 기능의 서버 검증 증명이 아니다.

## 사용자 해제 / 차량 초기화 요청

`POST /connection/disconnect`:

```json
{"kind":"USER","operationId":"기기에 먼저 영속화한 UUID"}
```

kind는 USER 또는 VEHICLE이다. 결과는 `{ "operationId": "동일 키", "status": "..." }`다.
서버는 `(installationId,operationId)` UNIQUE로 작업을 저장하고 차량, 사용자, 등록 버전,
세션 버전을 최초 요청에 고정한다. 같은 키로 다른 kind를 보내면 409다. 완료된 요청을
재시도해도 새 현재 사용자의 세션이나 재등록된 차량을 해제하지 않는다.

USER는 다음을 한 트랜잭션으로 수행하고 COMPLETED를 반환한다.

- 현재 설치의 C02 scoped 세션, C03 사용자 후보/동의와 대기 증명을 폐기한다.
- 개인 접근을 잠그고 신규 감지/운행 샘플/개인 요약·주유·정비 요청을 차단한다.
- 진행 중 서버 운행 구간을 해제 시점 또는 마지막 수신 샘플 경계로 종료한다.
- 기존 사용자·차량·운행 샘플·요약·주유·정비 기록과 설치 자격 증명을 유지한다.

VEHICLE은 동일한 즉시 개인 접근 잠금을 수행하지만 등록은 유지하고 AWAITING_ADMIN을
반환한다. 관리자 결정 전에는 새 C03 증명과 수집을 허용하지 않는다. 관리자 기각 후에도
이전 권한을 복구하지 않는다. USER 완료 또는 기각 이후 새 C03 증명 성공 시에만 서버의
개인 접근 잠금을 해제한다. 새 증명은 잠금 이후 생성한 challenge여야 한다. 기존 기록의
소유자는 새 로그인으로 변경하지 않는다. Android의 로컬 잠금은 클라이언트가 별도로 유지한다.

이 저장소에서 차량 인증은 설치 자격 및 서버 발급 scoped 세션을 사용한다. 모바일
Supabase JWT는 휴대폰의 승인 인증이며 차량에 연결된 별도 Supabase 로그인 세션으로
저장하지 않는다. 따라서 USER 해제 시 휴대폰 전체 Supabase 로그아웃을 수행하지 않는다.
향후 차량에서 별도 Supabase 로그인을 도입하면 설치별 세션 등록과 공급자 원격 폐기를
연동해야 한다. 현재 구현의 COMPLETED는 실제 사용 중인 서버 scoped 세션/동의 폐기를 뜻한다.

## 관리자 승인과 기각

`GET /mobile/connection-operations`은 로그인 사용자가 OWNER/MANAGER인 현재 등록의
차량 초기화 대기 요청만 최대 100건 반환한다. 일반 회원은 빈 목록을 받는다.
항목에는 operationId, installationId, vehicleId, registrationVersion, createdAt이 있다.
operationId는 설치별 키이므로 승인/기각 본문에 installationId를 반드시 함께 보낸다.

`POST /mobile/connection-operations/{operationId}/approve`:

```json
{
  "installationId": "설치 UUID",
  "registrationVersion": 1,
  "pendingRecordsPolicy": "RETAIN_ORIGINAL_OWNER",
  "reauthenticationProof": "같은 관리자의 최근 OTP/TOTP 인증을 포함한 Supabase JWT"
}
```

현재 JWT와 proof의 사용자 일치, proof 서명/발급자/대상/만료, 최근 5분 OTP/TOTP amr,
현재 OWNER/MANAGER 권한, 최초 작업의 등록 버전과 현재 등록 일치를 재검사한다.
OTP/TOTP 인증 사건(사용자·방법·인증 시각)은 DB에서 일회용 소비한다. JWT를 refresh해도
같은 인증 사건으로 다른 초기화를 승인할 수 없다. 같은 완료 작업의 재시도는 새 증명을
소비하지 않고 기존 COMPLETED를 반환한다. 관리자 권한은 재시도에도 검사한다.

승인은 권한 폐기, 기존 소유자 기록 보존, 기존 C01 QR/연결 요청 폐기, 설치-차량 연결 해제,
등록 버전 증가, 감사 작업 완료를 한 트랜잭션으로 처리한다. vehicles와 group_members는
삭제하지 않는다. 차량은 같은 설치 Bearer와 새로운 C01 operationId로 재등록한다.
기존 `/connection/reset` 즉시 초기화 경로는 정책 우회를 막기 위해 사용 중단했으며
최근 OTP가 있어도 409 USE_CONNECTION_OPERATIONS를 반환한다.

`POST /mobile/connection-operations/{operationId}/reject`:

```json
{"installationId":"설치 UUID","registrationVersion":1}
```

관리 권한과 버전을 확인하고 REJECTED를 저장한다. 같은 기각 재시도는 같은 결과다.
기각은 차량 등록을 유지하며 기존 토큰이나 개인 화면을 자동 복구하지 않는다.
반대 결정으로 덮어쓰려 하면 409다.

## 추천 동의

`PUT /mobile/vehicles/{vehicleId}/recommendation`:

```json
{"consented":true,"enabled":true}
```

consented는 필수이고 enabled는 생략 시 false다. true 동의/활성화에는 현재 차량 멤버십,
같은 C03 확인 사용자, 서버의 추천 등록 및 기능 검증이 필요하다. 등록·검증 정보는
`recommendation_registrations`에 신뢰된 서버 프로비저닝만 기록하며 차량의 capabilities
보고로 승격하지 않는다. verified_at과 verification_source도 있어야 검증으로 인정한다.

`consented=false, enabled=false`로 즉시 철회할 수 있다. 현재 설정 조회에 반영하고
동의·등록·검증·사용자 확인 중 하나라도 없으면 recommendationEnabled=false다.
동의는 설치·등록 버전·사용자별로 격리되며 추천 설정은 운행 소유자를 변경하지 않는다.

## 동기화 상태

`GET /installation/sync-status`은 status, lastAcknowledgedAt, owners를 반환한다.
현재 확인된 사용자 한 명의 원래 소유자 상태만 반환하고 다른 사용자의 ID·건수·금액은
노출하지 않는다. 사용자가 잠금/만료/미확정이면 owners=[]와 UNKNOWN을 반환한다.

서버는 기기 로컬 큐를 직접 볼 수 없다. `installation_sync_checkpoints`는 신뢰된 서버 큐
조정 작업의 기록 지점이며 클라이언트의 완료 선언을 받는 API는 제공하지 않는다.
현재 큐 조정 작업/Android 큐 manifest 업로드는 미연동이므로 기본 UNKNOWN이다.
서버가 pending_count>0을 확인한 경우 PENDING, pending_count=0과 승인 시각 및 최근
60초 이내 서버 검증 시각을 모두 기록한 경우에만 SYNCED다. 오래된 완료 확인은 UNKNOWN이다.
단순 업로드 성공이나 최근 SDK 보고를 미전송 큐 완료로 해석하지 않는다.

## 오류 및 검증 범위

401: 설치/사용자/재인증 토큰 오류 또는 만료.
403: 관리자/멤버십/현재 사용자 검증 부재, 해제 후 개인 접근 또는 초기화 대기 중 증명 시도.
409: 다른 kind 키 재사용, 등록 버전/대상 변경, 반대 결정, 재사용한 재인증 사건.
400: 잘못된 정책·필수 필드·기능 상태·미래 관측 시각.

차량은 실패 시 로컬 잠금을 유지하고 같은 operationId로 수동 재시도한다.
서버 DB 작업은 V12 마이그레이션이다. H2 기반 통합 테스트에서 소유권 보존·동시 재시도·
새 사용자 세션 보호·관리자 재인증·추천 철회·기능 및 큐 상태 격리를 검증한다.
실제 MariaDB 적용, 실제 Supabase OTP, Android 재시작·늦은 응답 폐기·모바일 왕복 검증은
배포/기기 통합 환경에서 수행해야 한다. 이 저장소에는 Android 코드가 없다.
