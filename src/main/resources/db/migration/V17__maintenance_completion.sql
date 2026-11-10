ALTER TABLE maintenance_plans
    ADD COLUMN completed_at DATETIME(6) NULL COMMENT '사용자 확인으로 정비 완료 처리한 시각';
ALTER TABLE maintenance_plans
    ADD COLUMN deleted_at DATETIME(6) NULL COMMENT '완료된 계획의 논리 삭제 시각';
ALTER TABLE maintenance_plans
    ADD COLUMN completion_event_id CHAR(36) NULL COMMENT '사용자 완료 확인 정비 이력 UUID';
ALTER TABLE maintenance_plans
    ADD COLUMN next_plan_id CHAR(36) NULL COMMENT '완료 후 생성된 동일 주기의 다음 계획 UUID';
ALTER TABLE maintenance_plans
    ADD UNIQUE KEY uq_maintenance_completion_event (completion_event_id);
ALTER TABLE maintenance_plans
    ADD UNIQUE KEY uq_maintenance_next_plan (next_plan_id);
