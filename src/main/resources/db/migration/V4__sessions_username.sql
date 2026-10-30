-- V4: 会话归属用户隔离（每个登录用户只能看到/操作自己的会话）
-- 新增 username 列并建索引；历史会话该列为 NULL，视为无主，任何用户都不可见，避免跨用户串数据。
-- 与 V1/V2/V3 风格一致：用 ALTER/CREATE INDEX，兼容已存在环境。

ALTER TABLE sessions ADD COLUMN username VARCHAR(64) NULL;
CREATE INDEX idx_sessions_username ON sessions (username);
