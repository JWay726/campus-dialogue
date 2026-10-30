-- V2: 用户账号表（注册 / 管理员增删用户 / 角色）
-- 风格与 V1 保持一致：时间戳用 BIGINT（毫秒），InnoDB + utf8mb4。
-- 兼容已存在环境：IF NOT EXISTS + 命名唯一约束。

CREATE TABLE IF NOT EXISTS users (
    id            BIGINT       NOT NULL AUTO_INCREMENT,
    username      VARCHAR(64)  NOT NULL,
    password_hash VARCHAR(255) NOT NULL,
    role          VARCHAR(20)  NOT NULL,
    created_at    BIGINT,
    updated_at    BIGINT,
    PRIMARY KEY (id),
    CONSTRAINT uk_users_username UNIQUE (username)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
