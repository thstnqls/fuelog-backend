# fuelog-backend

fuelog(풀록) 차량·모바일 앱의 사용자 연결, 운행·주유·충전 기록과 정비 관리를 제공하는 Spring Boot 기반 API 서버입니다.

## 주요 기능

- QR 기반 차량 연결 및 사용자 인증·접근 권한 관리
- 운행 요약과 주유·충전 후보 저장 및 동기화
- 모바일 확인함과 개인·공동 정비 이력 관리

## 실행 방법

앱 시연에는 배포된 서버를 사용하므로 별도 서버 실행이 필요하지 않습니다.
로컬 개발 시에는 JDK 21과 MariaDB 데이터베이스·계정을 준비한 뒤 실행합니다.

```bash
cp .env.example .env.local
# .env.local의 DB_URL, DB_USERNAME, DB_PASSWORD, SUPABASE_URL 설정
./gradlew bootRun
```

- 상태 확인: `http://localhost:8080/actuator/health`
- API 문서: `http://localhost:8080/swagger-ui.html` (로컬 기본 활성화)

## 개발 및 테스트 환경

- Java 21 / Spring Boot 4.1.2 / MariaDB / Flyway
- Supabase Auth JWT 기반 모바일 사용자 인증
- 자동 테스트는 `test` 프로파일과 H2 인메모리 DB 사용

```bash
./gradlew test bootJar
```

빌드 결과: `build/libs/fuelog-backend-0.0.1-SNAPSHOT.jar`

차량·모바일 앱의 설치 방법, 에뮬레이터 규격 및 APK 위치는 각 앱 프로젝트의 README를 참고하세요.
