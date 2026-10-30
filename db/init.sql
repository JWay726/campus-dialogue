-- 容器首次初始化时执行：只建库。
-- 应用账号由 docker-compose.yml 的 MYSQL_USER / MYSQL_PASSWORD 自动创建，
-- MySQL 官方镜像会默认授予该账号对 MYSQL_DATABASE（campus_dialogue）的全部权限，
-- 天然满足「最小权限：只碰本项目库」的要求。
-- 手工建库（非容器）请执行 README「快速开始 → 建库建账号」里的语句。
CREATE DATABASE IF NOT EXISTS campus_dialogue
  CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
