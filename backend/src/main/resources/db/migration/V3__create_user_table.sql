-- T17 · 用户表（本地用户表 + JWT 无状态认证基线，对齐 backend.md「认证」段与技术方案 §5 安全）
-- 来源：技术方案 §5 鉴权「JWT 短期 1h + Refresh」、§4.1.2 watchlist 行级权限需 user_id 身份
-- 命名：表/字段小写下划线；三必备字段 created_at/updated_at/version（SQLite 日期存 ISO-8601 文本）
CREATE TABLE user (
  id            INTEGER PRIMARY KEY AUTOINCREMENT,
  username      TEXT    NOT NULL,
  password_hash TEXT    NOT NULL,             -- BCrypt 哈希，永不存明文
  created_at    TEXT    NOT NULL,
  updated_at    TEXT    NOT NULL,
  version       INTEGER NOT NULL DEFAULT 0,
  UNIQUE(username)
);

-- 播种默认管理员：用户名 admin，初始密码 admin123（BCrypt cost=10 预生成哈希）
-- 安全说明：此为首次启动引导账号，初始密码仅用于本地/演示；生产部署后请立即通过改密接口或直接 UPDATE 改密，
--           并确保 JWT_SECRET 等敏感配置走环境变量注入（不写入文件）。
-- 哈希由 spring-security-crypto BCryptPasswordEncoder 生成（$2a$ 前缀），matches("admin123", 此哈希)=true
INSERT INTO user (username, password_hash, created_at, updated_at, version)
VALUES ('admin', '$2a$10$tAs2zalIu.jzVe9iXO3k3uwQh04h/Tm6PnOOS/lvTv00nRBLa7wRe',
        '2026-09-21T00:00:00Z', '2026-09-21T00:00:00Z', 0);
