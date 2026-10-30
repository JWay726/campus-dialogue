-- V1: 知识库与对话持久化表结构（MySQL 9.x）
-- 使用 IF NOT EXISTS，兼容已存在的历史表（由早期 Hibernate ddl-auto=update 创建）；
-- 新环境（如 docker-compose 全新 MySQL）则由此脚本一次性建表。

CREATE TABLE IF NOT EXISTS sessions (
    id          BIGINT       NOT NULL AUTO_INCREMENT,
    title       VARCHAR(255),
    created_at  BIGINT,
    updated_at  BIGINT,
    PRIMARY KEY (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS messages (
    id          BIGINT       NOT NULL AUTO_INCREMENT,
    session_id  BIGINT       NOT NULL,
    role        VARCHAR(255),
    content     TEXT,
    ts          BIGINT,
    PRIMARY KEY (id),
    KEY idx_messages_session (session_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS vectors (
    id          BIGINT       NOT NULL AUTO_INCREMENT,
    doc_id      VARCHAR(255),
    chunk_text  TEXT,
    embedding   TEXT,
    PRIMARY KEY (id),
    KEY idx_vectors_doc (doc_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS documents (
    id          BIGINT       NOT NULL AUTO_INCREMENT,
    title       VARCHAR(255),
    content     TEXT,
    source      VARCHAR(255),
    created_at  BIGINT,
    updated_at  BIGINT,
    PRIMARY KEY (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
