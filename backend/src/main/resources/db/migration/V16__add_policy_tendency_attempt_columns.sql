-- P0-3 · 政策倾向判断尝试留痕（系统体检 20260924 止血：失败无限重试烧 LLM 预算）
-- 来源：体检报告 P0-3 —— PolicyTendencyService.judgeAndPersist 失败返回 UNJUDGED 不留尝试痕迹，
--        PolicyTendencyJob 每 30min 重扫 7 天窗口 ai_tendency=0 条目，每条失败政策 ≈ 168 次真实
--        LLM 调用/周（系统任务 userId=0 绕过预算护栏，实测预算已烧 18181/20000）。
-- 表用途：findRecentUnjudged 扫描前过滤——tendency_attempts < 上限（Job 配 policy.tendency.max-attempts，
--         默认 3）且距上次尝试 ≥ 退避窗（policy.tendency.retry-backoff-hours，默认 2h）；
--         每轮判断未成功由 recordTendencyAttempt 原子自增 attempts 并刷新 last_attempt_at。
-- 约定：tendency_attempts NOT NULL DEFAULT 0——存量 UNJUDGED 行迁移后为 0，按止血语义再试满上限后停扫
--        （不回填历史值，7 天窗口外的老条目本就出窗；ai_tendency 仍 0，对外 API 语义不变）；
--        tendency_last_attempt_at 可空（NULL=从未尝试），ISO-8601 整秒文本，字典序即时间序，对齐既有表惯例。
ALTER TABLE policy_item ADD COLUMN tendency_attempts INTEGER NOT NULL DEFAULT 0;
ALTER TABLE policy_item ADD COLUMN tendency_last_attempt_at TEXT;
