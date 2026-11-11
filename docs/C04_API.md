# C04 운행 요약 / M06 확인함

차량 API는 `Authorization: Bearer <설치 자격 증명>`, 모바일 API는 Supabase
로그인 JWT를 사용합니다. 모든 응답은 JSON이고 `Cache-Control: no-store`입니다.
시각은 UTC ISO-8601, 거리는 km, 연료는 L입니다. 실제 배포 시 HTTPS를 사용합니다.

## 업로드 및 조회

`PUT /recording-intervals/{tripId}`는 Android가 고정한 UUID와 본문의 `tripId`가
일치해야 합니다. 설치별 유일 키이며 C04 조회 시에는 생성하지 않습니다.
새 오프라인 익명 운행은 기존 C02 구간 없이도 업로드할 수 있습니다.

입력 필드와 JSON 예시는 Swagger의 **C04 운행 요약**을 참고하세요. 수집하지 않은
거리·연료·단가는 null, 대응하는 Valid 필드는 false로 보냅니다. 사용자 ID는 받지
않습니다. `userConsentId`는 같은 설치·차량·구간에 대해 C03 `verify`가 반환한
`candidateId`입니다. 미확정이면 null입니다.

서버는 기존 C02 구간을 `{tripId}`와 `recording_intervals.id` 또는
`local_interval_id`로 대조합니다. 따라서 Android가 클라이언트 UUID를 사용하는
경우 C02 구간 시작 때도 그 UUID를 `localIntervalId`로 보내야 합니다.

개인 운행을 업로드하려면 먼저 C02 구간을 준비하고 해당 구간의 모바일 동의와
C03 검증을 완료해야 합니다. 실제 기록 시작은 검증 이후여야 하며 시작 시각이
동의 유효기간 안에 있어야 합니다. C02 준비 구간의 시작보다 늦은 실제 시작은
허용하지만, 업로드 범위 밖의 기존 샘플이 있으면 거부합니다. 익명/다른 사용자의
샘플이 이미 들어간 구간 전체를 새 사용자에게 귀속할 수 없습니다.

업로드 시 멤버십·검증 상태·구간 경계를 다시 검사합니다. 동의 만료 후 오프라인
전송은 원래 검증된 구간이 유효기간 안에 시작된 경우에만 허용합니다. 폐기된 동의와
해제된 멤버십은 거부합니다. 기존 C02 기록 구간이 진행 중이면 같은 트랜잭션에서
종료하여 이후 신규 샘플을 막습니다. 기존 익명 구간·샘플의 소유자는 변경하지 않습니다.

성공 응답:

```json
{"tripId":"77777777-7777-4777-8777-777777777777","revision":1,"syncedAt":"2026-11-06T02:00:01Z"}
```

같은 revision의 동일 내용은 기존 응답을 돌려줍니다. 숫자의 소수점 표현 차이는
정규화합니다. 같은 revision의 다른 내용과 낮은 revision은 409입니다. 높은
revision만 갱신하며 소유자·동의·시작/종료 시각은 변경할 수 없습니다. 응답을 받은
후에만 Android의 동기화 완료 상태를 바꿔야 합니다.

`GET /recording-intervals/{tripId}/summary`는 같은 설치에서 조회합니다. 개인 운행은
`X-Trip-User-Candidate: <userConsentId>` 헤더가 추가로 필요합니다.

요약에는 `tripId`, `revision`, `syncedAt`, `startedAt`, `endedAt`과 다음 값이 있습니다.

| 필드 | 구조 / 의미 |
| --- | --- |
| distanceKm | `{value,status,reason}`. 유효한 수신 거리, 누락 시 `partial_recording` |
| fuelConsumedLiters | `{value,status,reason}`. 시작 연료 − 종료 연료 |
| fuelEfficiencyKmPerLiter | `{value,status,reason}`. 거리 / 소모량, 소수 3자리 반올림 |
| estimatedCost | `{value,currency,status,reason}`. 소모량 × 단가, 소수 2자리 반올림 |
| priceSource / priceObservedAt | 입력 단가의 출처와 관측 시각 |
| quality | missing, endEstimated, refueled, ownerChanged, measurementSource |

각 status는 `available` / `unavailable`입니다. 계산 불가 값은 null이며 0원으로
대체하지 않습니다. 60km / 4L / 1700 KRW이면 15km/L, 6800 KRW입니다.
소모량이 0 이하, 연료 끝점 누락, 누락·추정 종료·주유·사용자 변경 경계가 있으면
연비와 비용을 계산하지 않습니다. 단가 오류는 연료 계산에 영향을 주지 않고,
연료 오류는 유효한 거리를 지우지 않습니다. 단가 관측 시각은 운행 종료 이후일 수
없습니다. 저장된 샘플에 누락이 있는데 `missing=false`로 보고하면 409입니다.

현재 연료 끝점의 관측 시각·센서 출처·단위·품질을 검증할 원시 수집 모델이 없으므로
`quality.measurementSource=client_reported`입니다. 계산과 구간·권한 검증은 서버에서
수행하지만 실차 센서 연속성 검증은 후속 연동 대상입니다.

## 확인함 전달과 읽음

업로드 성공 후 `PUT /mobile-inbox/trips/{tripId}`를 호출합니다. 설치 Bearer와
`X-Trip-User-Candidate`가 필요합니다. 수신자는 서버가 운행 소유자로 결정합니다.

```json
{"operationId":"mobile-inbox:77777777-7777-4777-8777-777777777777","tripRevision":1}
```

응답은 `{inboxItemId,tripId,deliveredAt}`입니다. 사용자+tripId별 한 항목이며 동일
revision 재시도는 같은 결과입니다. 입력 revision은 현재 업로드 revision과 같아야
합니다. 높은 revision을 전달하면 같은 항목의 요약 스냅샷을 교체하고 읽음 상태를
초기화합니다. 첫 전달 시각은 유지합니다. 익명 운행은 확인함에 넣을 수 없습니다.

`GET /mobile/inbox?cursor=0`은 로그인한 사용자 소유 항목 중 현재 차량 멤버십이
유효한 것만 최대 50건씩 ID 오름차순으로 반환합니다. 응답은
`{items:[{inboxItemId,tripId,tripRevision,deliveredAt,readAt,summary}],nextCursor}`입니다.
다음 페이지는 nextCursor를 전달하고 null이면 마지막 페이지입니다. 변경된 기존
항목을 새로고침할 때는 cursor=0에서 다시 조회합니다. summary는 전달된 revision의
스냅샷이므로 운행 업로드만으로 모바일에 전달 완료됐다고 표시하면 안 됩니다.

`PATCH /mobile/inbox/{itemId}`는 로그인 사용자의 항목에 `{"readAt":"UTC 시각"}`을
보냅니다. 서버가 소유권·멤버십을 검사하고 첫 읽음 시각을 보존합니다. 타인의 항목은
403입니다. 전달 전 시각이나 서버보다 60초 이상 미래인 시각은 400입니다.

주요 실패는 400(입력), 401(인증), 403(권한·동의·과거 기록 귀속), 404(미업로드),
409(revision·경계·익명 확인함 충돌), 410(시작 당시 동의 만료)이며 `{code}`로
반환합니다. 서버 오류·응답 유실은 동일 키/revision으로 재시도합니다.

V8 마이그레이션은 `trip_summaries`, `mobile_trip_inbox`와 유일성·참조 제약을
추가합니다. Android HTTP 작업자·M06 화면·실차 강제 종료 복구는 별도 통합 검증이
필요합니다. 확인함 서버 저장 성공은 휴대폰 푸시 수신이나 읽음 확인을 뜻하지 않습니다.


## EV 운행 전력비 최초 저장 (2026-11-11)

`GET /recording-intervals/{tripId}/ev-cost`와 `POST .../ev-cost`는 설치 Bearer와
현재 페어링 사용자·운행 소유자·차량 멤버십을 검사한다. 클라이언트 운행 UUID와
서버 구간 UUID 모두 해당 설치 안에서 해석한다. GET은 위치 입력 없이 저장된 금액만
읽는다. POST는 다음 새 차량 위치 증거를 HTTPS 본문으로 받는다.

```json
{
  "latitude":37.5,"longitude":127.0,
  "measuredAt":"2026-11-11T01:00:00Z","receivedAt":"2026-11-11T01:00:01Z",
  "source":"PLEOS_CURRENT_UPDATE","locationBasis":"TRIP_END"
}
```

P단 종료 자동 계산은 `TRIP_END`, 이후 사용자가 금액을 확인하면 `CONFIRMATION`이다.
TRIP_END 위치는 운행 종료 시각부터 10초 안에 측정되어야 한다. 두 경우 모두 새 위치
업데이트의 측정·수신 시각과 범위를 검사하고 30초가 지난 증거를 거부한다.
나중에 확인할 때는 현재 위치를 사용하며 과거 종료 위치로 표시하지 않는다.
원시 좌표는 저장하지 않는다. 좌표계 검증 설정은 `PLEOS_COORDINATE_SYSTEM=WGS84`가 필요하다.

유효한 소모 kWh × **서로 다른 1~3개 사업자의 완속(<30kW) 일반 회원 단가 평균**으로
원 단위 HALF_UP 계산한다. 반경 1km에서 부족하면 3km까지 한 번 확장하고 사업자당
가장 가까운 한 시설만 쓴다. 단가는 저장된 운행 endedAt에 `verified_at <= endedAt < valid_until`인
버전 중 가장 최근 것을 선택한다. 이후에 확인된 현재 요금으로 과거 요금을 대체하지 않는다.
최근 24시간 이내 카탈로그와 해당 날짜에 유효한 요금이 한 곳도 없으면 금액을 저장하지 않는다.
최초 보관 요금 이전 날짜와 요금 유효기간의 공백도 계산 불가다.

```json
{
  "tripId":"77777777-7777-4777-8777-777777777777",
  "owner":"11111111-1111-4111-8111-111111111111","status":"SAVED",
  "cost":{
    "amount":1320,"currency":"KRW","averageWonPerKwh":330,"consumedKwh":4,
    "tariffDate":"2026-11-09","calculatedAt":"2026-11-11T01:00:02Z",
    "basis":"SLOW_THREE_OPERATOR_MEAN","sourceCount":3,"locationBasis":"CONFIRMATION"
  }
}
```

`tariffDate`는 운행 종료일(KST), `calculatedAt`은 실제 최초 저장 시각이다.
시설별 목록은 이 응답에 포함하지 않는다. 계산 불가 응답은 `cost=null`이며 status는
LOCATION_REQUIRED / NO_VALID_CONSUMPTION / COORDINATE_SYSTEM_UNVERIFIED /
INSUFFICIENT_HISTORICAL_SLOW_RATES로 구분한다. 실패해도 기존 운행은 유지된다.

V18 `trip_ev_costs`의 summary_id 기본키와 설치 행 잠금으로 최초 한 건만 저장한다.
중복·동시 요청은 기존 금액·시각을 반환하며 수정·삭제 API는 없다. 저장 후 소모 kWh를
변경하는 요약 업로드는 409 SAVED_EV_COST_IMMUTABLE이다. 새 클라이언트가 unitPrice를
보내도 이 비용 API를 우회할 수 없다. V18 이전 서버에 이미 저장된 EV 비용은
LEGACY_SAVED_ESTIMATE로 보존한다.

요약의 `estimatedCost`와 `savedEvCost` 및 기존 모바일 확인함 항목은 같은 고정 금액을
반환한다. 확인함 전달 후 금액이 나중에 저장되어도 새 운행·확인함·revision을 만들지 않는다.
서버 재시작 시 V18을 적용해야 새 API가 동작한다.
