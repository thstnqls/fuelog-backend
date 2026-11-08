# DB 마이그레이션

첫 도메인 테이블을 추가할 때 `V1__create_<table>.sql`을 작성합니다.
이후 변경은 `V2__...sql`, `V3__...sql`처럼 새로운 파일로 추가합니다.
이미 적용한 파일은 수정하지 않습니다.

`V13__document_tables_and_columns.sql`은 V12까지의 업무 테이블 30개·컬럼 286개에
한국어 COMMENT를 추가합니다. 타입·NULL 허용·기본값·AUTO_INCREMENT와 기존 제약을
유지하며, 다음 애플리케이션 시작 시 Flyway가 연결 DB에 적용합니다.
전체 목록과 컬럼 설명은 [DB 스키마 설명](../../../../../docs/DATABASE_SCHEMA.md)에 있습니다.

local/prod 모두 Flyway가 먼저 마이그레이션하고 Hibernate가 엔티티와 스키마를
검증합니다(`ddl-auto: validate`). 도메인이 정의되지 않은 초기 상태에서는
업무 테이블을 생성하지 않습니다.

문자열은 utf8mb4를 사용하고, BaseTimeEntity의 created_at/updated_at은
UTC 시각을 저장하는 datetime(6) 컬럼으로 선언합니다.

## 테스트 목업 데이터

`db/seed/V7__demo_data.sql`은 모든 업무 테이블의 참조 관계를 맞춘 고정 예시
행을 넣습니다. 기본 local/prod 프로파일은 이 위치를 읽지 않습니다. 폐기 가능한
별도 MariaDB 데이터베이스를 `DB_URL`, `DB_USERNAME`, `DB_PASSWORD`에 설정한 뒤
`SPRING_PROFILES_ACTIVE=local,seed ./gradlew bootRun`으로 적용합니다.

V7은 한 번만 실행됩니다. 시드를 적용한 DB는 이후 실행에도 `seed` 프로파일을
포함해야 Flyway가 이미 적용된 V7 파일을 찾습니다. 기존 `.env.local`이 공유 DB를
가리킨다면 그 설정으로 `seed` 프로파일을 켜지 마세요. 샘플 설치에는 인증 비밀값을
넣지 않았고 사용자 UUID도 실제 Supabase 계정이 아닙니다. API 인증 테스트에는
별도로 발급한 설치 자격 증명과 실제 모바일 JWT가 필요합니다. 이후 정규 스키마
변경 버전은 V8부터 사용합니다.
