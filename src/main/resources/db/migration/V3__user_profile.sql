-- V3: 个人中心扩展（学号绑定 / 微信绑定）
-- 风格与 V1/V2 一致：时间戳用 BIGINT（毫秒），InnoDB + utf8mb4。
-- student_no 唯一索引：MySQL 唯一索引允许多个 NULL，未绑定学号的账号不受影响。

ALTER TABLE users ADD COLUMN student_no      VARCHAR(32) NULL;
ALTER TABLE users ADD COLUMN wechat_openid   VARCHAR(64) NULL;
ALTER TABLE users ADD COLUMN wechat_nickname VARCHAR(64) NULL;
ALTER TABLE users ADD COLUMN wechat_bound_at BIGINT      NULL;

CREATE UNIQUE INDEX uk_users_student_no ON users (student_no);
CREATE INDEX idx_users_wechat_openid ON users (wechat_openid);
