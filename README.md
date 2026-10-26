# Fuelog Backend

데이터 저장용 Spring Boot API 백엔드 초기 구성입니다.

## 기술 구성

Java 21 / Gradle 9.4.1 / Spring Boot 4.1.2 / Spring MVC / Validation /
Spring Data JPA / MariaDB 11.4 / Flyway / Actuator를 사용합니다.
Spring Boot 관련 의존성은 BOM으로 관리하며 springdoc-openapi 버전은 별도로 지정합니다.
[Spring Boot 버전 호환 기준](https://docs.spring.io/spring-boot/system-requirements.html)을 참고합니다.
빌드에는 JDK 21이 필요하며 IntelliJ Project SDK도 21로 지정합니다.

## 로컬 실행과 .env 위치

`.env`는 프로젝트 루트의 `build.gradle`, `compose.yml`과 같은 위치에 둡니다.
Git에는 예제인 `.env.example`만 포함됩니다.

```bash
# 새로 clone한 경우 한 번만 복사
cp .env.example .env
# .env 내용을 수정한 뒤 환경변수로 전달
set -a
source .env
set +a
docker compose up -d --wait
./gradlew bootRun
```

Docker Compose는 `.env`를 자동으로 읽지만 Spring Boot는 자동으로 읽지 않습니다.
위의 `source` 명령을 사용하거나 IntelliJ 실행 설정의 Environment variables에 값을 등록합니다.
기본 설정 그대로라면 `.env` 없이도 local 프로파일로 실행할 수 있습니다.

DB 기본 접속은 `localhost:3306/fuelog`, 사용자/비밀번호는 `fuelog` / `fuelog_local`입니다.
DB_PORT를 바꾸면 DB_URL의 포트도 함께 변경합니다.
Compose는 로컬 개발용이며 DB 포트를 루프백 주소에만 공개합니다.
MariaDB 계정 환경변수는 새 데이터 볼륨을 초기화할 때만 적용됩니다.
기존 볼륨의 비밀번호는 DB에서 직접 변경해야 합니다.

```bash
curl http://localhost:8080/actuator/health
# 정상 응답: {"status":"UP"}
docker compose stop
```

중지 후에도 DB 데이터는 Docker 볼륨에 보존됩니다.

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
| SERVER_PORT | HTTP 포트 | 8080 |
| DB_POOL_MAX_SIZE | 운영 최대 연결 수 | 10 |
| DB_POOL_MIN_IDLE | 운영 최소 유휴 연결 수 | 2 |

```bash
java -jar build/libs/fuelog-backend-0.0.1-SNAPSHOT.jar --spring.profiles.active=prod
```

Flyway가 시작 시 실행되므로 DB 사용자에게 마이그레이션에 필요한 DDL 권한이 있어야 합니다.
종료 시 진행 중인 요청을 최대 30초간 처리합니다.
관리 엔드포인트는 `/actuator/health`만 노출하며 DB 상세 정보는 숨깁니다.
인증/인가와 CORS는 서비스 요구사항에 맞춰 이후 추가합니다.

## Swagger / OpenAPI

[springdoc-openapi 3.1.1](https://springdoc.org/getting-started.html)을 사용해
컨트롤러의 API 문서를 자동 생성합니다.

- Swagger UI: http://localhost:8080/swagger-ui.html
- OpenAPI JSON: http://localhost:8080/v3/api-docs
- OpenAPI YAML: http://localhost:8080/v3/api-docs.yaml

local에서는 기본 활성화, prod에서는 기본 비활성화입니다.
환경변수 `SWAGGER_ENABLED=true` 또는 `false`로 UI와 문서 노출을 함께 변경합니다.
`.env`에 추가했다면 기존 실행 방법처럼 환경변수로 내보낸 뒤 앱을 재시작합니다.

문서 제목, 설명, 버전은 `common/config/OpenApiConfig.java`에서 관리합니다.
새 컨트롤러에는 `@Tag`, `@Operation`, DTO에는 `@Schema`를 사용해 설명을 추가할 수 있습니다.
아직 업무 컨트롤러가 없으므로 초기 문서에는 업무 API가 없으며 Actuator는 문서에서 제외됩니다.

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
├── repository          # Spring Data JPA 저장소
└── entity              # JPA 엔티티
```

업무 도메인이 아직 정의되지 않아 실제 CRUD와 업무 테이블은 추가하지 않았습니다.
엔티티는 BaseTimeEntity를 상속해 생성/수정 시각을 자동 기록할 수 있습니다.
시각은 Instant로 표현하고 JDBC 및 로컬 DB 세션의 기준 시간대는 UTC입니다.
기본 MVC 오류는 Problem Details 형식을 사용합니다.

## DB 마이그레이션

`src/main/resources/db/migration`에 `V1__create_<table>.sql`부터 추가합니다.
이미 적용한 파일은 수정하지 않고 후속 버전 파일을 추가합니다.
local/prod 모두 Flyway 적용 후 JPA가 스키마를 검증합니다.
현재는 업무 SQL이 없어 Flyway 이력 테이블만 생성됩니다.

## 검증

```bash
./gradlew test bootJar
docker compose config --quiet
```

테스트는 외부 DB 없이 애플리케이션/JPA 컨텍스트 시작, health 응답,
관리 엔드포인트 노출 범위, OpenAPI 문서 및 Swagger UI 제공을 검증합니다.
H2 테스트는 MariaDB 고유 SQL과 Flyway 검증을 대체하지 않으므로
실제 SQL 추가 시 MariaDB 통합 테스트도 추가합니다.
