CREATE TABLE user_notification_settings (
    user_id CHAR(36) NOT NULL PRIMARY KEY,
    charge_complete_enabled BOOLEAN NOT NULL DEFAULT FALSE,
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
);
