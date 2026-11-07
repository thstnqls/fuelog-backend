# C05 주유 후보 / M07 모바일 확인 API

차량은 설치 Bearer, `/mobile/**`은 Supabase 사용자 JWT를 사용한다. 모든 응답은
`Cache-Control: no-store`이다. 후보는 주유·결제 완료를 의미하지 않으며 감지 임계치는
Android가 관리한다. 서버는 3%p를 확정 정책으로 강제하지 않는다.

## 차량 동기화

`POST /fuel-candidates/sync`

```json
{
  "id": "후보 UUID",
  "owner": "C03에서 검증한 사용자 UUID",
  "intervalId": "서버 recording interval UUID 또는 해당 설치의 localIntervalId UUID",
  "startAt": 1793926800000,
  "endAt": 1793926860000,
  "increasePercent": 10,
  "status": "CANDIDATE",
  "revision": 0,
  "queued": true
}
```

시간은 epoch milliseconds다. 서버는 설치의 현재 차량, 최신 운행 구간, 최신 검증
동의의 사용자·구간·만료 및 차량 멤버십을 확인한다. 감지 시작이 서버 사용자 검증
이전이면 저장하지 않는다. 이전 구간/사용자의 미귀속 후보는 새 로그인으로 귀속하지
않는다. 현 구간이 종료되었어도 동의가 유효하고 감지 시각이 구간 안이면 재전송할 수
있다. 새 구간이 시작되면 이전 구간의 차량 sync는 403이다. 모바일 조회는 동의 만료와
무관하게 원래 소유자와 현재 차량 멤버십을 검사한다.

후보 UUID는 전역 충돌을 차단하며 `(installation_id,id)`도 UNIQUE다. 같은 UUID의
owner/설치/차량/구간은 변경할 수 없다. endAt이 더 큰 요청만 감지 시작/종료
시각·증가량을 갱신한다. 이전/같은 endAt은 감지 증거를 덮어쓰지 않는다.
status/revision/capacityVerified/estimatedLiters/estimatedAmount/priceAt/stationCandidates는
클라이언트 캐시로 무시한다. queued=true는 확인함을 멱등 생성하며 확정 명령이 아니다.
queued=false 재전송이 이미 생성한 확인함을 삭제하지 않는다.

응답은 id/owner/status/revision/capacityVerified/estimatedLiters/estimatedAmount/priceAt/
stationCandidates와 intervalId/startAt/endAt/increasePercent, 모바일 보정값
correctedPlace/correctedLiters/correctedAmount를 포함한다. 응답 intervalId는 서버 구간 ID다.
최초 revision은 1이며 감지 증거·서버 추정값·모바일 상태 변경 시 증가한다. 동일 요청의
반복은 revision을 증가시키지 않는다. 모바일 CONFIRMED/REJECTED 상태는 sync로 되돌리지
않는다. 클라이언트는 id/owner 확인 후 더 높은 revision만 반영한다.

## 모바일 확인과 보정

- `GET /mobile/fuel-candidates/{id}`: 원래 소유자 및 차량 멤버십 확인 후 상세 반환.
- `PATCH /mobile/fuel-candidates/{id}`: 아래 요청으로 조건부 갱신.

```json
{
  "operationId": "mobile-confirm-uuid",
  "expectedRevision": 1,
  "action": "CONFIRM",
  "place": "사용자 확인 주유소",
  "liters": 5.0,
  "amount": 8500
}
```

`action`은 CONFIRM 또는 REJECT다. 보정 세 필드는 선택이며 null/생략이면 기존 보정값을
유지한다. 감지 증거와 추정치는 별도로 보존한다. CONFIRMED는 사용자의 확인 상태이며
실제 결제량·결제 성공을 인증하지 않는다. 동일 후보에서 operationId 재시도는 최초
저장한 응답을 반환한다. 같은 키로 다른 요청을 보내거나 expectedRevision이 다르면
409다. 사용자, 요청, 전후 revision 및 결과를 같은 트랜잭션에서 감사 이력으로 저장한다.
다른 변경 이후에도 과거 operation 재시도는 그 operation의 원래 응답을 반환하므로 앱은
revision 역행 방지 규칙을 유지해야 한다.

## 통합 확인함

`GET /mobile/inbox?cursor=0`은 운행 요약과 주유 후보를 하나의 숫자 ID 순서로 반환한다.
페이지 크기는 50이고 nextCursor를 사용한다. 기존 TRIP_SUMMARY 항목에는 summary가 유지된다.
새 주유 항목 예시는 다음과 같다.

```json
{
  "inboxItemId": 3,
  "kind": "FUEL_CANDIDATE",
  "candidateId": "후보 UUID",
  "candidateRevision": 2,
  "deliveredAt": "2026-11-06T02:01:00Z",
  "readAt": null,
  "tripId": null,
  "tripRevision": 0,
  "summary": null
}
```

주유 항목은 상세 조회 링크이며 최신 candidateRevision을 제공한다. 기존 운행 전용
모바일 클라이언트는 kind를 분기해야 한다. `(recipient_id,kind,candidate_id)` UNIQUE로
중복 전달을 막는다. 현재 사용자와 차량 멤버십을 잃은 사용자의 데이터는 조회되지 않는다.
`PATCH /mobile/inbox/{itemId}` 읽음 처리도 두 종류 모두 지원한다.

## 추정 메타데이터와 주유소

V11의 `vehicle_fuel_metadata`는 신뢰할 수 있는 서버 차량/가격 검증 작업의 입력 지점이다.
차량이나 모바일 요청으로 이 테이블을 수정하는 API는 제공하지 않는다.

- capacity_liters > 0, capacity_verified_at, capacity_source가 모두 있어야 용량 검증으로 인정한다.
- L 추정은 capacity_liters × increasePercent / 100, 소수 셋째 자리 반올림이다.
- 금액은 양수 unit_price, price_source, 0 ≤ price_at ≤ 감지 endAt ≤ price_valid_until이
  모두 있을 때만 계산하고 priceAt을 함께 반환한다. 소수 둘째 자리 반올림이다.
- 용량 미검증이면 L/금액은 null이다. 단가가 없거나 유효 기간 밖이면 금액/priceAt은 null이며 L은 보존한다.
- 검증 메타데이터 변경은 다음 차량 sync에 반영하며 revision을 증가시킨다.

`POST /fuel-candidates/{id}/station-search` 요청은 locationPermission, latitude,
longitude, observedAt(epoch ms)을 받는다. 위치는 권한이 있고 좌표와 60초 이내 시각이
있을 때만 사용 가능하다. 현재 오피넷 공급자·자격 증명은 미연동이므로 항상
stationCandidates=[]를 반환한다. 권한 없음/오래된 위치/공급자 부재도 원본 감지와
추정량·확정 상태를 변경하지 않는다. 장소를 자동 확정하지 않는다.

## 오류와 배포

- 401: 잘못된 설치/모바일 인증, 만료된 현재 사용자 동의.
- 403: 멤버십 해제, 소유권/설치/차량/구간 불일치, 미검증 동의 또는 검증 이전 증거.
- 404: 없는 후보.
- 409: revision 경합, operationId 재사용.
- 400: 잘못된 시각, 증가율 범위, 필수값/수동 보정 범위.

V11 마이그레이션은 기존 확인함을 확장한다. 실제 MariaDB 적용 및 Android/모바일 통합,
실차 센서 정확도는 별도 검증 대상이다. 선택 API인 GET /vehicle/events 및 실시간 push는
추가하지 않았다. 차량은 수동 sync로 최신 revision을 조회한다. 이 저장소에는 Android
코드가 없어 Android 빌드는 수행하지 않는다.
