-- T131 · M16 推荐三表（卡片 / 反馈流水 / 降噪组合），来源：技术方案-V2.0-M16 §4.1 DDL + ADR-0051。
-- 惯例对齐 V23：小写下划线；三必备 created_at/updated_at；NOT NULL+默认值优先；时间戳整秒 ISO-8601（UTC）文本。
-- event_item / 通知 / 订阅 / 管道既有表零 DDL 改动（推荐域独立建表）。
-- V27（brief_type=8 模板播种）随 T132 落地。

-- 推荐卡片（一用户一事件一卡：UNIQUE 幂等最后防线；事件字段冗余为卡片流筛选/渲染免 join）
-- level：P1 标的直接 / P2 行业 / P3 订阅（主关联层级，多级命中取最高）
-- push_status：PENDING（已生成未过闸门）/ PUSHED（已 SSE 推送）/ SKIPPED_QUOTA（超日上限静默）
--              / SKIPPED_MUTED（降频/静默组合拦截）
-- gen_method：LLM（白名单校验通过）/ TEMPLATE（模板拼接或 LLM 失败/被拒/降级态兜底）
CREATE TABLE recommendation_card (
  id             INTEGER PRIMARY KEY AUTOINCREMENT,
  user_id        INTEGER NOT NULL,               -- 归属用户（行级权限键）
  event_id       INTEGER NOT NULL,               -- event_item.id（不做物理 FK，SQLite 惯例）
  news_id        INTEGER NOT NULL,               -- 冗余 event_item.news_id（原文跳转免 join）
  event_type     TEXT    NOT NULL,               -- 冗余（9 枚举白名单同 event_item）
  importance     TEXT    NOT NULL,               -- HIGH / MEDIUM（触发门槛保证，无 LOW）
  direction      TEXT    NOT NULL,               -- BULLISH / BEARISH / NEUTRAL
  level          TEXT    NOT NULL,               -- P1 / P2 / P3
  industries     TEXT    NOT NULL DEFAULT '[]',  -- JSON 命中行业数组（P2 命中集；P1/P3 为关联行业上下文）
  subjects       TEXT,                           -- JSON [{"code","name","industry","inWatchlist"}] 标的区（≤5，可空）
  logic_chain    TEXT    NOT NULL,               -- 逻辑链文案（白名单校验后的最终文本）
  logic_inputs   TEXT,                           -- JSON 生成输入快照（结构化事实 + 允许集，抽检对账与复现用）
  gen_method     TEXT    NOT NULL,               -- LLM / TEMPLATE
  prompt_version TEXT,
  recscore       REAL    NOT NULL DEFAULT 0,     -- recscore-v1 综合分（排序用）
  basis          TEXT    NOT NULL,               -- "recscore-v1:..." 口径串
  combo_key      TEXT    NOT NULL,               -- 降噪组合键 "{eventType}|{industry}"（industry 空用 "-"）
  push_status    TEXT    NOT NULL DEFAULT 'PENDING',
  pushed_at      TEXT,
  read           TINYINT NOT NULL DEFAULT 0,     -- 已读（隐式采纳信号）
  adopted        TINYINT NOT NULL DEFAULT 0,     -- 采纳（条件 UPDATE 首次置 1，去重计 1）
  created_at     TEXT    NOT NULL,
  updated_at     TEXT    NOT NULL,
  UNIQUE(user_id, event_id)
);
CREATE INDEX idx_rc_user_created ON recommendation_card(user_id, created_at); -- 推荐中心卡片流 + 游标分页
CREATE INDEX idx_rc_user_push    ON recommendation_card(user_id, push_status); -- 当日配额计数 / 遗留 PENDING 重推

-- 反馈流水（append-only：同卡可先 DISLIKE 后 UNDO_MUTE 再 DISLIKE，状态由 mute 表承载）
-- action：USEFUL 有用 / DISLIKE 不感兴趣 / ADD_WATCHLIST 加自选 / UNDO_MUTE 撤销降频
CREATE TABLE recommendation_feedback (
  id         INTEGER PRIMARY KEY AUTOINCREMENT,
  user_id    INTEGER NOT NULL,
  card_id    INTEGER NOT NULL,
  action     TEXT    NOT NULL,
  combo_key  TEXT,                                -- DISLIKE/UNDO_MUTE 必填（升级计数滚动窗查询键）
  created_at TEXT    NOT NULL,
  updated_at TEXT    NOT NULL
);
CREATE INDEX idx_rf_user_created ON recommendation_feedback(user_id, created_at); -- 滚动 30 天升级计数
CREATE INDEX idx_rf_card         ON recommendation_feedback(card_id);            -- 卡片操作条已点动作回显

-- 降噪组合（常驻状态表：行量有界 ~几十行，不入 retention 清理）
-- status：ACTIVE 降频中 / LIFTED 已撤销（保留行，再次 DISLIKE 沿 Subscription reactivate 模式翻回）
CREATE TABLE recommendation_mute (
  id              INTEGER PRIMARY KEY AUTOINCREMENT,
  user_id         INTEGER NOT NULL,
  combo_key       TEXT    NOT NULL,               -- "{eventType}|{industry}"
  mute_days       INTEGER NOT NULL DEFAULT 7,     -- 7 降频 / 30 升级静默
  muted_until     TEXT    NOT NULL,               -- 到期时刻（查询语义：now < muted_until 且 ACTIVE 即拦截）
  trigger_count   INTEGER NOT NULL DEFAULT 1,     -- 留痕展示用；升级判定以 feedback 流水滚动窗为准
  last_disliked_at TEXT   NOT NULL,
  status          TEXT    NOT NULL DEFAULT 'ACTIVE',
  created_at      TEXT    NOT NULL,
  updated_at      TEXT    NOT NULL,
  UNIQUE(user_id, combo_key)
);
