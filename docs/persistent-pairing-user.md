# 페어링 사용자 유지와 연결 없는 차량 기본정보

2026-11-08 변경. 신규 차량 앱은 구간 QR·후보 선택을 사용하지 않는다.

- 최초 차량 QR 승인과 차량 화면 최종 확인으로 사용자를 인증한다.
- `GET /installations/me`는 `vehicle_id`/`vehicle_name`(차량 식별)과 `paired`/`authenticated_user_id`/`authenticated_user_name`(사용자 연결)을 별도로 반환한다.
- QR의 2분 만료는 연결 요청에만 적용한다. 완료된 사용자 연결은 재시작·새 운행에서도 유지하며 현재 차량 멤버십을 계속 확인한다.
- 신규 운행 시작 본문은 `subjectId`에 조회한 인증 사용자 UUID를 보낸다. 서버가 현재 사용자와 일치하는지 검사하고 구간 소유자를 고정한다. 같은 localIntervalId의 재시도는 소유자를 바꾸지 않는다.
- 구간 샘플·종료·요약은 고정 소유자에게 누적한다. 새로운 요약의 `userConsentId`는 null이며, 과거 candidateId 형식은 기존 큐 호환을 위해 유지한다.
- `POST /connection/disconnect`의 USER는 현재 사용자를 차단하고 완료된 페어링을 폐기하지만 물리 차량 연결과 정비·운행 이력은 보존한다. 같은 차량을 선택하여 새 사용자로 다시 페어링한다. 다른 차량으로 바꾸는 것은 VEHICLE 초기화 절차를 따른다.
- 새 사용자는 이전 사용자의 개인 요약을 조회할 수 없다. 기존 대기 기록은 원래 소유자를 유지하고 새 사용자에게 재귀속하지 않는다.
- `GET /maintenance/summary`는 사용자 연결 없이 설치 인증과 기존 차량 식별만으로 공동 정비·누적거리를 반환한다. 개인 비용·메모·영수증은 포함하지 않는다. 정비 수정과 누적거리 업로드 권한은 완화하지 않는다.
- 차량 등록조차 없는 신규 설치는 최초 등록 전 서버 정비 이력을 조회할 수 없다. 정비를 익명 인터넷 API로 공개하는 변경은 아니다.

기존 trip-user/challenges·session·candidates·verify API는 이전 클라이언트 호환용이다. 신규 앱에서는 호출하지 않는다.
추가 DB 마이그레이션 없이 기존 완료된 pairing_requests와 QR 승인 이력을 사용한다.

## 이미 연결된 사용자 QR 재스캔

같은 Supabase 계정이 현재 같은 설치·차량에 연결되어 있으면 기존 QR 재스캔을 오류로 처리하지 않는다.
물리적인 휴대폰 식별이 아니라 검증된 JWT의 사용자 UUID와 서버 연결 상태로 판정한다.

| 모바일 QR 확인 API | 연결 유지 시 응답 | 최초 연결 시 응답 |
|---|---|---|
| `POST /mobile/connection/inspect` | `status=COMPLETED`, `vehicleId`, `remainingSeconds=0` | `status=WAITING`, `vehicleId=null` |
| `POST /pairing-requests/preview` | `status=completed`, `vehicle_id` | `status=pending`, `vehicle_id=null` |

모바일은 완료 응답을 받으면 차량 선택·승인 단계를 생략하고 로그인 완료 화면으로 이동한다.
최초 연결·승인 대기는 기존 절차와 QR 비밀값 검증을 유지한다.
완료된 요청의 QR 만료는 기존 로그인을 만료시키지 않는다. 완료 응답은 현재 계정의 연결 상태 조회이며
새 로그인 권한을 발급하거나 세션·QR을 복구하지 않는다. 따라서 완료 요청의 비밀값은 다시 소비하지 않는다.
로그아웃·연결 해제·사용자 변경·멤버십 철회 후에는 이전 QR로 로그인 권한을 복구할 수 없다.
그 경우 새 QR과 기존 승인 절차가 필요하다. 장치 재설치로 새 installation_id를 발급받은 경우도 동일 설치로 취급하지 않는다.

검증: `./gradlew test bootJar`. 재시작·만료된 QR·구간 시작 소유자 검사·요약/확인함 귀속·연결 해제 후 공동 정비 조회·재페어링·멤버십 철회를 포함한다.
배포는 별도이며, 앱과 함께 새 응답 필드를 지원하는 서버 버전을 사용해야 한다.
