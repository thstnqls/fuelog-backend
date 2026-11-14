# 차량 연결 사용자 이메일 조회

`GET /installation/users/{userId}`

- Authorization: Bearer <installation-secret>
- userId: `/installations/me`의 authenticated_user_id(UUID).
- 응답: `{ "user_id": "<UUID>", "email": "driver@example.com" }`, 이메일 미등록은 null.
- 성공 응답 Cache-Control: no-store. 다른 사용자 ID 조회는 차단한다.
- 서버가 설치 인증 후 현재 QR 인증 사용자·차량 멤버십을 확인하고 Supabase Auth Admin `/auth/v1/admin/users/{userId}`에서 최신 이메일을 조회한다. 외부 조회 후 연결 상태를 재검증해 조회 도중 해제된 계정 정보도 반환하지 않는다.

## 서버 설정

기존 `SUPABASE_URL`에 더해 `SUPABASE_SERVICE_ROLE_KEY`를 서버 환경에 설정하고 서버를 재시작한다. legacy service_role 관리자 키를 사용하며 앱, Git, 로그에 넣지 않는다. DB 마이그레이션은 없다. Supabase 서버 요청은 연결 제한 3초/요청 제한 5초이며 실패 원문·키는 응답하지 않는다.

| 상태 | 의미 |
| --- | --- |
| 401 | 설치 인증 실패 |
| 403 CONNECTED_USER_REQUIRED | 현재 연결 사용자 ID 불일치·연결 해제·멤버십 철회 |
| 404 USER_NOT_FOUND | Supabase 사용자 삭제/없음 |
| 503 USER_LOOKUP_NOT_CONFIGURED | 서버 URL/관리 키 미설정 |
| 502 USER_LOOKUP_UNAVAILABLE | Supabase 실패·응답 ID 불일치·잘못된 응답 |

차량 앱은 기존 `/installations/me`로 연결을 확인한 후 이 API를 호출한다. 화면 표시만 이메일로 바꾸며 기록 소유권은 UUID를 유지한다. 기존 authenticated_user_name 응답은 하위 호환을 위해 유지한다. 모바일 `/auth/me`는 기존처럼 JWT 클레임 정보를 반환한다.

검증: InstallationUserTests(미인증/다른 사용자/해제/조회 중 해제), SupabaseUserServiceTests(ID별 이메일/응답 ID 불일치/관리 키 없음/오류 비밀값 비노출), Android InstallationFlowAndroidTest(이메일 조회·오래된 응답 무시·기존 QR 복원)를 실행한다. 로컬 환경에는 관리 키가 없으므로 실제 Supabase 이메일 조회는 별도 확인이 필요하다.

참고: https://supabase.com/docs/reference/javascript/auth-admin-getuserbyid
