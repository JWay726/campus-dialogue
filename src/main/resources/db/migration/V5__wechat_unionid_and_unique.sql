-- V5: 微信身份加固
--   1) 新增 unionid：同一开放平台账号下唯一，跨应用不变，登录时优先用它定位账号
--   2) openid / unionid 各建唯一索引：从数据库层面保证「一个微信只能绑一个账号」
--      （服务层已有占用校验，唯一索引用于兜住并发扫码的竞态）
-- 说明：MySQL 唯一索引允许多个 NULL，因此未绑定微信的账号不受影响。

ALTER TABLE users ADD COLUMN wechat_unionid VARCHAR(64) NULL;

-- 原来的单列普通索引被下面的唯一索引取代，避免重复索引
DROP INDEX idx_users_wechat_openid ON users;

CREATE UNIQUE INDEX uk_users_wechat_openid  ON users (wechat_openid);
CREATE UNIQUE INDEX uk_users_wechat_unionid ON users (wechat_unionid);
