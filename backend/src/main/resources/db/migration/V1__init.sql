-- T01 · 标的主数据表（统一内部主键，外部代码映射）
-- 来源：技术方案 §4.2 subject_master DDL，原样落
CREATE TABLE subject_master (
  id            INTEGER PRIMARY KEY AUTOINCREMENT,
  subject_code  TEXT    NOT NULL,            -- 内部统一代码 如 SH600519
  market        TEXT    NOT NULL,             -- A_SHARE / HK / INDEX / SECTOR
  subject_type  TINYINT NOT NULL,             -- 1股票 2指数 3板块 4基金(预留) 5债券(预留)
  name          TEXT    NOT NULL,
  external_codes TEXT,                        -- JSON: {"tushare":"600519.SH","akshare":"sh600519"}
  industry      TEXT,                         -- 行业/概念
  status        TINYINT NOT NULL DEFAULT 1,   -- 1启用 0停用
  created_at    TEXT    NOT NULL,
  updated_at    TEXT    NOT NULL,
  version       INTEGER NOT NULL DEFAULT 0,
  UNIQUE(subject_code)
);
CREATE INDEX idx_subject_type   ON subject_master(subject_type);
CREATE INDEX idx_subject_market ON subject_master(market);
