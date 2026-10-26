# DB 마이그레이션

첫 도메인 테이블을 추가할 때 `V1__create_<table>.sql`을 작성합니다.
이후 변경은 `V2__...sql`, `V3__...sql`처럼 새로운 파일로 추가합니다.
이미 적용한 파일은 수정하지 않습니다.

local/prod 모두 Flyway가 먼저 마이그레이션하고 Hibernate가 엔티티와 스키마를
검증합니다(`ddl-auto: validate`). 도메인이 정의되지 않은 초기 상태에서는
업무 테이블을 생성하지 않습니다.

문자열은 utf8mb4를 사용하고, BaseTimeEntity의 created_at/updated_at은
UTC 시각을 저장하는 datetime(6) 컬럼으로 선언합니다.
