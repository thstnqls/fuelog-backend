# C06 정비 요약 / M11·M12 정비 관리

V10은 정비 계획·이벤트·감사 이력, 제조사 근거·차량 적용 프로필, 누적거리 샘플,
차량별 요약 revision 테이블을 추가합니다. 모든 API는 JSON이며 응답은
`Cache-Control: no-store`입니다. 배포 시 HTTPS를 사용합니다.

## 인증과 공개 범위

차량 API는 `Authorization: Bearer <설치 자격 증명>`, 모바일 API는 Supabase 사용자
access token을 사용합니다. 차량 ID는 설치의 현재 연결에서만 결정하며 연결 해제
후에는 403입니다. 모바일은 모든 요청에서 현재 차량 멤버십을 확인합니다.

공동 항목 변경은 OWNER, 개인 항목 변경은 소유자만 가능합니다. 정비
이벤트 수정·삭제는 작성자만 가능하며 공동 이벤트 변경에는 관리 권한도 필요합니다.
MEMBER는 공동 내용을 읽고 본인의 개인 계획·이벤트를 기록할 수 있습니다.

`shared`는 항목/이벤트의 공동 관리 공개 여부입니다. 비용·메모·정비소·영수증은
별도 `detailsShared`로 제어합니다. 모바일 이력의 `details`는 작성자 또는 명시적으로
공개된 경우에만 제공하고 나머지는 null입니다. 개인 이벤트는 `detailsShared=true`를
허용하지 않습니다. 차량 요약 DTO에는 개인 상세 필드 자체가 없습니다.

## 차량 조회

`GET /maintenance/summary`:

```json
{
  "revision": 8,
  "odometer": {"km": 48200.5, "observedAt": 1793940000000, "status": "available"},
  "items": [{
    "id": "11111111-1111-4111-8111-111111111111",
    "name": "엔진오일",
    "lastServiceDate": "2026-01-10",
    "lastServiceKm": 42000,
    "nextDueDate": "2027-01-10",
    "nextDueKm": 52000,
    "basis": "USER",
    "dateDue": false,
    "distanceDue": false,
    "remainingKm": 3799.5,
    "intervalKm": 10000,
    "intervalMonths": 12,
    "riskLevel": "NORMAL"
  }]
}
```

조회 범위는 설치 인증으로 결정한 `vehicle_id`이며 사용자 페어링이 없어도 해당 차량 공용 주기를 제공합니다. `intervalKm`·`intervalMonths`는 반복 주기이고, `nextDueKm`·`nextDueDate`는 마지막 확정 정비로 계산한 다음 예정입니다. 정비 이력이 없어 다음 예정이 null이어도 등록된 반복 주기는 반환합니다. USER는 주기 출처(직접 설정)를 뜻하며 차량 공용 조회를 사용자별로 나누는 기준이 아닙니다. 개인 항목과 비용·메모는 기존 공개 범위를 유지합니다.

예시는 계약 설명용입니다. `items`는 배열이며 최대 2개, 데이터가 없으면 빈 배열입니다.
누적거리가 없으면 `odometer={km:null,observedAt:null,status:"unavailable"}`입니다.
7일보다 오래된 마지막 유효값도 km와 epoch milliseconds를 유지하되 `status=stale`,
`remainingKm=null`, `distanceDue=null`로 반환합니다. ERROR/UNSUPPORTED 샘플은 마지막
유효 누적거리를 덮어쓰지 않습니다. 누적거리는 정비 이벤트에 입력한 거리에서 추론하지
않습니다. 정비 날짜는 UTC 기준의 `YYYY-MM-DD`이며 숫자 거리는 km입니다.

이미 날짜 또는 거리 조건이 도래한 항목을 우선합니다. 같은 우선순위에서는 이미
도래한 날짜, 이름, 항목 ID 순으로 정렬합니다. 미래 날짜와 남은 km의 단위를 비교해
선도달을 예측하지 않습니다. 조건이 아직 도래하지 않으면 앱은 “날짜 또는 거리 중
먼저 도달하면 정비 시기”로 안내할 수 있습니다.

## 차량 원본 누적거리 업로드

`PUT /vehicle-odometer/samples/{sampleId}`:

```json
{"km":48200.5,"observedAt":1793940000000,"unit":"km","source":"PLEOS_VEHICLE_API","quality":"VALID"}
```

설치별 sampleId UUID로 멱등 처리하며 같은 본문은 최초 `{sampleId,receivedAt}`을
반환합니다. 다른 본문은 409입니다. quality는 VALID/ERROR/UNSUPPORTED이며 VALID는
km 필수, 나머지는 km=null입니다. 원본 누적거리만 받으며 음수·다른 단위·다른 source·
서버보다 60초 이상 미래 시각을 거부합니다. 과거 샘플도 앞뒤 유효값과 누적거리 순서가
일치해야 하며 역행은 422입니다. 계기판 초기화는 새 정상값으로 자동 해석하지 않습니다.

설치 자격과 source 필드로 실제 Pleos 하드웨어를 증명할 수는 없습니다. 신뢰할 수 있는
기기/차량 원본 출처의 증명이 필요하면 별도 프로비저닝·attestation 계약이 필요합니다.
현재 Android C06에는 이 API를 호출하는 업로더가 없으므로 후속 연동이 필요합니다.

## 모바일 계획과 이벤트

경로 앞에는 `/mobile/vehicles/{vehicleId}`를 붙입니다.

| API | 입력 / 응답 |
| --- | --- |
| GET maintenance-plans | 공동 계획 + 본인 개인 계획을 `{items:[...]}`로 반환. 비활성 계획도 포함하되 완료 후 논리 삭제된 계획은 제외 |
| PUT maintenance-plans/{itemId} | 클라이언트가 생성한 UUID, 아래 계획 JSON. 성공 시 계획과 ETag |
| DELETE maintenance-plans/{itemId} | If-Match 필수. 비활성화하며 이벤트는 유지 |
| GET maintenance-events | cursor UUID로 최대 50건. `{items:[...],nextCursor}`; 최초 cursor 생략 |
| POST maintenance-events | Idempotency-Key 필수. 아래 이벤트 JSON. 성공 시 이벤트와 ETag |
| PATCH maintenance-events/{eventId} | If-Match 필수. 부분 JSON 수정. 생략은 유지, null은 선택 필드 제거 |
| DELETE maintenance-events/{eventId} | If-Match 필수. 논리 삭제·감사 이력 보존 |

계획 예시:

```json
{"name":"엔진오일","shared":true,"basis":"USER","evidenceId":null,"intervalKm":10000,"intervalMonths":12}
```

거리 또는 달력 개월 주기 중 하나가 있어야 합니다. 최초 PUT은 If-Match 생략 또는 0,
기존 변경·재활성화는 현재 계획 revision이 필요합니다. 계획 revision은 설정 변경
버전이며 이벤트가 바뀌어 파생 예정이 갱신되는 것은 차량 summary revision으로 확인합니다.

이벤트 예시:

```json
{
  "itemId":"11111111-1111-4111-8111-111111111111",
  "serviceDate":"2026-01-10",
  "odometerKm":42000,
  "status":"CONFIRMED",
  "shared":true,
  "detailsShared":false,
  "cost":85000,
  "currency":"KRW",
  "memo":"사용자가 확인한 기록",
  "shop":null,
  "receiptUrl":null
}
```

활성 계획의 itemId가 필요하며 날짜·누적거리 중 하나는 있어야 합니다. 미래 정비일은
확정 이력으로 받지 않습니다. `VISIT_ESTIMATE`는 방문 추정 상태이며 예정 계산에 쓰지
않습니다. 금액이 있으면 ISO 통화 코드가 필요합니다. 영수증 URL은 HTTPS만 허용합니다.

POST는 사용자·차량·Idempotency-Key별 한 건입니다. 동일 내용 재시도는 최초 응답을
반환하고 다른 내용은 409입니다. 기록 수정·삭제 후 원래 POST를 재시도해도 복원하거나
중복 생성하지 않습니다. `If-Match: "1"`처럼 현재 revision을 보내며 누락은 428,
불일치는 412입니다. 같은 revision의 동시 수정 중 한 건만 반영됩니다.

예정 재계산은 이벤트 생성·수정·논리 삭제 또는 계획 변경과 한 트랜잭션에서 수행합니다.
항목 변경은 이전·새 항목 모두 재계산합니다. 공동 계획은 공동 CONFIRMED 이력만,
개인 계획은 공동 또는 본인 CONFIRMED 이력만 사용합니다. 마지막 유효 기록 한 건에서
날짜·누적거리 축을 함께 읽고, 없는 축을 이전 기록에서 섞어 채우지 않습니다.
정비일이 있으면 정비일, 없으면 생성 UTC 날짜를 정렬 기준으로 삼고 동률은 생성 시각과
ID로 정합니다. 예정일은 해당 날짜+개월, 예정 거리는 해당 누적거리+km 주기입니다.
변경 시 차량 summary revision도 증가합니다. 삭제 감사 이력은 별도 관리 테이블에
저장하며 일반 모바일·차량 API로 제공하지 않습니다.

## 제조사 기준

MANUFACTURER를 사용하려면 서버의 `maintenance_manufacturer_evidence`와 검증된
`vehicle_maintenance_profiles`가 필요합니다. 문서 URL·제목·차종·연식·적용 범위·
항목명과 주기가 일치해야 합니다. 모바일은 evidenceId를 참조할 수 있지만 검증 근거를
발급하거나 verified 상태를 지정할 수 없습니다. 미검증·적용 범위 불일치·주기 불일치는
422입니다. 근거가 폐기되면 차량 응답에서 해당 제조사 계획을 제외하고 모바일 계획에는
evidence.verified=false로 표시합니다. 근거를 관리하는 별도 운영 API는 이번 범위에 없습니다.

주요 실패는 400(입력), 401(설치/JWT 인증), 403(연결·멤버십·작성자·공개 범위),
404(계획/이벤트 없음), 409(멱등 충돌), 410(삭제 기록 수정), 412(동시 수정),
422(검증 근거·거리 품질), 428(If-Match 누락)이며 `{code}` 형식입니다.

Android 요청 취소·주행 제한·오프라인 표시, 실제 Pleos 누적거리 업로드와 M11/M12
화면의 CRUD·ETag 처리는 통합 검증이 필요합니다. 차량 화면의 완료 확인은 아래 완료 API로 확정 이력을 생성합니다.

## 차량 오너 편집과 D-day

차량 홈·기본정보의 공용 교체주기 조회는 사용자 페어링과 관계없이 설치에 등록된 vehicle_id 기준으로 유지한다. summary에 `canEdit`, `editorSubjectId`를 추가한다. 서버가 확인한 현재 페어링 사용자와 해당 차량 OWNER 역할이 일치할 때만 편집 가능하며, 앱은 이 사용자 ID가 현재 확인된 사용자와 같은지도 검사한다. 이 플래그는 UI 표시용이고 서버가 쓰기 요청마다 권한을 다시 검사한다.

- GET `/maintenance/plans`: 현재 차량 오너에게 활성 공용 주기 전체를 반환한다.
- PUT `/maintenance/plans/{itemId}`: 설치 Bearer + If-Match(신규 0, 수정 현재 revision), 본문 `{plan:{name,shared:true,basis:USER,intervalKm?,intervalMonths?},lastServiceDate?,lastServiceKm?,operationId}`. 서버가 사용자·차량을 인증에서 결정한다. 주기와 새 확정 교체 기록을 하나의 트랜잭션으로 저장하고 예정일·누적거리를 재계산한다. 오류면 모두 롤백한다. 응답 ETag는 새 revision이다.
- DELETE `/maintenance/plans/{itemId}`: 같은 오너 검사와 If-Match로 주기를 비활성화하며 과거 이력을 보존한다.

오너 아닌 MEMBER·MANAGER·미확인 사용자의 수정은 403이다. 모바일의 공용 정비 주기·이력 쓰기도 OWNER만 허용한다. 개인 비용·메모·개인 항목의 기존 공개 범위는 변경하지 않는다. 차량 편집은 공용 주기에만 허용하며 제조사 주기를 임의 수정해 검증된 제조사 기준으로 유지하지 않고 USER(차량 설정)로 저장한다.

마지막 교체일을 새로 입력하면 확정 교체 기록을 추가한다. 기존 최신 교체일보다 과거로 보정하려는 경우는 모바일 이력 수정으로 연결하며 차량 폼에서는 거부한다. 과거 이력을 조용히 덮어쓰지 않는다. 재시도 시 같은 항목 UUID를 사용하고 revision 충돌을 거부하여 중복 주기 생성을 막는다.

D-day는 마지막 교체일 + 주기 개월 수로 계산한 nextDueDate를 기준으로 한다. 날짜의 서버 업무 기준은 Asia/Seoul이다. D-30(30일 남음), D-DAY(오늘), D+3(3일 지남)을 표시한다. 거리 전용 주기 또는 마지막 교체일이 없는 경우 날짜를 추정하지 않고 확인 필요와 유효한 남은 km를 표시한다. 편집 다이얼로그는 정차 UX 제한 확인 상태에서만 열고, 화면 이탈·제한 진입·사용자/차량 변경 시 닫고 요청을 취소한다.


## 사용자 정비 완료 확인과 다음 회차

단순 방문 추정이나 기한 경과만으로 완료 또는 새 회차를 생성하지 않는다. 미완료 정비는 기존 `nextDueDate`/`nextDueKm`를 유지한다.
기존 `POST maintenance-events` 및 계획 폼의 마지막 교체 기록은 과거 이력 입력·보정용이다. 현재 회차의 완료 버튼은 반드시 아래 API를 호출한다.

- 모바일: `POST /mobile/vehicles/{vehicleId}/maintenance-plans/{itemId}/complete` (사용자 JWT)
- 차량: `POST /maintenance/plans/{itemId}/complete` (설치 Bearer, 현재 확인된 차량 OWNER)

현재 계획 revision을 `If-Match`로, 재전송 시 유지할 키를 `Idempotency-Key`로 보낸다. 공동 계획은 OWNER, 개인 계획은 소유자만 완료할 수 있다.

```json
{"confirmed":true,"serviceDate":"2026-11-10","odometerKm":52000}
```

`confirmed=true`는 사용자가 실제 정비 이행을 확인했다는 뜻이다. 날짜는 필수이며 미래 날짜를 거부한다. 거리 주기가 있으면 실제 교체 당시 누적거리도 필수다.
기존 마지막 정비일·누적거리보다 이전 값은 거부한다. 차량 누적거리 샘플에서 정비 완료 여부나 교체 거리를 추측하지 않는다.

한 트랜잭션에서 확정 이력을 생성하고 기존 계획을 `status=COMPLETE`, `active=false`로 바꾼다. DB `maintenance_plans.completed_at`과 `deleted_at`에 서버 시각을 기록한다.
기존 회차의 예정 값은 보존한다. 같은 이름·주기·소유자·공개 범위·제조사 근거를 가진 새 UUID 계획을 생성하고 `next_plan_id`로 연결한다.
새 계획의 마지막 정비 값은 확인한 실제 날짜·누적거리이고, 다음 예정은 여기에 기존 주기를 더한다. 늦게 완료해도 실제 교체 시점부터 계산한다.
다음 회차에서 주기를 수정해도 이전 완료 이력을 기준으로 재계산한다.

응답은 `{event,completedPlan,nextPlan}`이며 새 계획 ID는 `nextPlan.id`, 새 revision은 `nextPlan.revision`이다.
동일 키·본문 재요청은 같은 완료 이력과 다음 계획 ID를 반환한다(계획 내용은 조회 시점의 최신 값). 다른 키로 이미 완료된 계획을 재완료하거나 같은 키에 다른 본문을 보내면 409다.
완료된 계획의 재활성화와 완료 확인 이력의 수정·삭제는 회차 연결 보호를 위해 409로 거부한다. 일반 과거 이력의 수정·삭제는 유지한다.
완료 후 논리 삭제된 계획은 일반 계획 목록과 차량 요약에서 제외되며, 이벤트와 DB 행은 보존한다. 모바일의 일반 비활성 계획은 기존처럼 목록에 포함한다.

## 정비 위험도

차량 요약 항목 및 모바일·차량 계획 응답에 `riskLevel`을 반환한다. DB에 위험도를 고정 저장하지 않고 조회 시 Asia/Seoul 날짜와 유효한 최근 누적거리로 계산한다.

| 값 | 기준 |
| --- | --- |
| UNKNOWN | 예정 기준이나 판단 가능한 측정값 없음, 또는 비활성·완료 계획 |
| NORMAL | 판단 가능한 기준에서 아직 예정 시점에 도달하지 않음 |
| OVERDUE | 예정일 또는 예정 누적거리에 도달했지만 주기의 50% 추가 초과 미만 |
| DANGER | 예정 시점에서 원래 주기의 50% 이상 추가 경과 (전체 주기의 150% 이상) |

거리 예: 42,000km에서 교체하고 주기가 10,000km라면 예정 52,000km, 위험 57,000km부터다.
날짜는 달력 개월을 더해 예정일을 구하고, 마지막 교체일~예정일의 실제 일수 절반(홀수는 올림)을 추가한 날짜부터 위험으로 본다.
날짜·거리 중 높은 위험도를 반환한다. 거리 데이터가 없거나 7일보다 오래되면 거리 판단을 생략하며 날짜 판단은 유지한다.
차량 요약 최대 2개에는 DANGER 항목을 먼저 노출한다. 이 값은 설정한 교체 주기의 초과 표시이며 차량 고장 진단 결과가 아니다.

스키마 변경은 `V17__maintenance_completion.sql`로 적용한다. 기존 계획·이력은 자동 완료하거나 삭제하지 않는다.
