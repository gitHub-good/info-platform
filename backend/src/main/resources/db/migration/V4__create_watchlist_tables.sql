-- T11 · 自选清单 + 清单项（行级权限按 user_id，幂等靠业务语义键）
-- 来源：技术方案 §4.2 watchlist / watchlist_item DDL，原样落
-- 行级权限：所有查询 WHERE user_id=?（端口层约束，T11 防越权）
-- 幂等：§4.4「幂等业务语义键」——创建清单=userId+name（服务层查重）、加标的=userId+watchlistId+subjectId
--        （watchlist_item 的 UNIQUE(watchlist_id,subject_id) 为 DB 层最后防线，重复添加→30011/409）
-- 注意：watchlist 表无 idempotency_key 列（与 push_record/ai_brief 不同），按 §4.2 DDL 走自然键去重
CREATE TABLE watchlist (
  id         INTEGER PRIMARY KEY AUTOINCREMENT,
  user_id    INTEGER NOT NULL,
  name       TEXT    NOT NULL DEFAULT '默认清单',
  remark     TEXT,
  status     TINYINT NOT NULL DEFAULT 1,      -- 1启用 0删除
  created_at TEXT NOT NULL,
  updated_at TEXT NOT NULL,
  version    INTEGER NOT NULL DEFAULT 0
);
CREATE INDEX idx_watchlist_user ON watchlist(user_id);

CREATE TABLE watchlist_item (
  id                INTEGER PRIMARY KEY AUTOINCREMENT,
  watchlist_id      INTEGER NOT NULL,
  subject_id        INTEGER NOT NULL,
  anomaly_threshold DECIMAL(5,2) DEFAULT 3.00, -- 异动阈值%
  status            TINYINT NOT NULL DEFAULT 1, -- 1启用 0删除
  created_at        TEXT NOT NULL,
  updated_at        TEXT NOT NULL,
  version           INTEGER NOT NULL DEFAULT 0,
  UNIQUE(watchlist_id, subject_id),
  FOREIGN KEY(watchlist_id) REFERENCES watchlist(id)
);
CREATE INDEX idx_wlitem_subject ON watchlist_item(subject_id);
