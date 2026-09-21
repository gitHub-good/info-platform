-- T21 · AI 简报异步任务表（事实回链 + 成本 + 状态乐观锁）
-- 来源：技术方案 §4.2 ai_brief DDL（原样落）+ §4.3 流程 2（异步 Worker：INSERT status=0 → CAS 领取 →
--        LLM 调用 → 解析 → 幻觉校验 → 写 source_links → status=1/3 → 发 AiBriefDoneEvent）+ §4.1.4 AI 简报接口
--        + Spike-2 §5.1（content 即 BriefContent JSON）/ §6（幻觉校验 facts 回查）/ §8.3（cost_tokens 成本治理）。
-- 表用途：T21 AIBriefService 按 idempotency_key=subjectId+briefType+yyyyMMdd 幂等创建任务（status=0 处理中），
--          异步生成后写 content（BriefContent JSON）/source_links（事实回链 JSON 数组）/cost_tokens/model，
--          status 翻 1 完成 / 2 失败 / 3 待核实；GET /ai-briefs/{taskId} 轮询状态。
-- 约定：content/model/prompt_version NOT NULL（status=0 创建时写空串占位，生成完成时 UPDATE 回填真值）；
--        UNIQUE(idempotency_key) 是幂等最后防线（重复 POST 直接返回上次结果）；
--        version 乐观锁列守护 CAS 领取（WHERE status=0 AND version=? bump）与终态写入（updateById @Version）。
CREATE TABLE ai_brief (
  id              INTEGER PRIMARY KEY AUTOINCREMENT,
  subject_id      INTEGER,                      -- 标的内部主键（每日推荐型可空，无单一标的）
  brief_type      TINYINT NOT NULL,             -- 1个股 2事件归因 3政策解读 4每日推荐
  prompt_version  TEXT    NOT NULL,             -- 生成所用模板版本（如 v1.0），回溯"简报为何这么差"
  model           TEXT    NOT NULL,             -- 实际服务的模型（如 deepseek-flash），回溯
  content         TEXT    NOT NULL,             -- BriefContent JSON（summary/keyEvents/bias/facts[]/disclaimer）
  source_links    TEXT,                         -- 事实回链 JSON 数组（facts/keyEvents 的 sourceUrl 去重）
  cost_tokens     INTEGER,                      -- 本次 LLM 调用 total token（T30 成本报表）
  status          TINYINT NOT NULL DEFAULT 0,   -- 0处理中 1完成 2失败 3待核实
  idempotency_key TEXT    NOT NULL,             -- = subjectId+briefType+yyyyMMdd
  created_at      TEXT    NOT NULL,             -- ISO-8601 整秒（UTC）
  updated_at      TEXT    NOT NULL,
  version         INTEGER NOT NULL DEFAULT 0
);
CREATE UNIQUE INDEX uq_ai_brief_idempotency ON ai_brief(idempotency_key);
CREATE INDEX idx_aibrief_subject ON ai_brief(subject_id, brief_type);
