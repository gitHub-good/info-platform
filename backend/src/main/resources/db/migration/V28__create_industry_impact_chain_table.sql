-- T144 · M17 事件→行业影响链表（REQ-20260926-14 拍板五，单事件多行业传导结构化影响评估）。
-- 惯例对齐 V23/V26：小写下划线；created_at/updated_at 必备；NOT NULL 优先；时间戳整秒 ISO-8601（UTC）文本。
-- event_item / 既有表零 DDL 改动（影响域独立建表，与推荐域同构）。
-- V29（industry_weekly_report 表 + brief_type=9 周报模板播种）随 T143/T145 落地。

-- 影响链行（一事件一行业一行：UNIQUE(event_id, industry)——重生成由仓储整事件替换收敛）
-- direction：BULLISH / BEARISH / NEUTRAL（=事件方向经模板极性映射，polarity 全 +1——零反转发明）
-- basis：依据回溯 JSON（newsId / signalNewsIds 信号来源条目 id 集 / 事件结构化字段 / quote 原文引用）
-- template_key：命中模板键（9 事件类型键 + POLICY_MONETARY/FISCAL/INDUSTRIAL 宏观三分，≥10 类覆盖验收面）
-- gen_method：v1 恒 TEMPLATE（纯规则映射无 LLM——护栏降级态与常态同一路径，链路恒活）
-- cache_state：AUTO（HIGH 落库自动）/ ON_DEMAND（MEDIUM 首次展开按需缓存；LOW 不生成无行）
CREATE TABLE industry_impact_chain (
  id           INTEGER PRIMARY KEY AUTOINCREMENT,
  event_id     INTEGER NOT NULL,               -- event_item.id（不做物理 FK，SQLite 惯例）
  industry     TEXT    NOT NULL,               -- 申万 31 白名单枚举（实体把守，容器不进链）
  direction    TEXT    NOT NULL,               -- BULLISH / BEARISH / NEUTRAL
  logic_chain  TEXT    NOT NULL,               -- 传导逻辑链文本（模板规则渲染）
  basis        TEXT    NOT NULL,               -- 依据回溯 JSON
  template_key TEXT    NOT NULL,               -- 命中模板键
  gen_method   TEXT    NOT NULL,               -- TEMPLATE（v1 唯一）
  cache_state  TEXT    NOT NULL,               -- AUTO / ON_DEMAND
  created_at   TEXT    NOT NULL,
  updated_at   TEXT    NOT NULL,
  UNIQUE(event_id, industry)
);
CREATE INDEX idx_iic_event ON industry_impact_chain(event_id); -- 事件详情区块按事件读链
