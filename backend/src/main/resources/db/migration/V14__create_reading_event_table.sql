-- T29 · 阅读行为留痕表（推荐相关性优化的个性化数据源）+ 每日推荐提示词 v1.1（个性化画像注入）
-- 来源：任务表 T29「推荐相关性优化（命中率 ≥70%）」+ PRD 场景 2「推荐内容与用户订阅主题/已读标的相关性命中率 ≥70%（抽样人工标注）」。
-- 写入方：ReadingEventController（POST /api/v1/reading-events，详情页/政策详情/简报页埋点，静默失败不影响主流程），
--         经 ReadingEventService 落库（同 user+type+ref 1 小时窗口内去重，幂等友好，防前端重复上报/StrictMode 双触发）。
-- 查询方：RecommendationPersonalizer（近 30 天阅读画像 → DailyRecommendationContextBuilder 上下文装配
--         + RecommendationRelevanceScorer 已读热度评分）。
-- 不设 version 列：追加型流水，写操作仅 INSERT（无 UPDATE 竞争），对齐 llm_call_log/job_execution_log 模式。
-- subject_id 可空：政策/无标的关联的阅读（POLICY/AI_BRIEF 每日推荐型）记 NULL，评分侧按 subject_id 非空聚合。
-- 时间戳存整秒 ISO-8601 文本（Instant.toString），字典序即时间序。
CREATE TABLE reading_event (
  id           INTEGER PRIMARY KEY AUTOINCREMENT,
  user_id      INTEGER NOT NULL,                -- 归属用户（行级权限键，与 subscription_config.user_id 同口径）
  subject_id   INTEGER,                         -- 关联标的（埋点侧经 subjectCode/subjectId 解析；解析不到记 NULL）
  content_type TEXT    NOT NULL,                -- SUBJECT_DETAIL / POLICY / AI_BRIEF（枚举名文本）
  content_ref  TEXT    NOT NULL,                -- 内容引用（标的代码 / 政策 id / 简报 taskId）
  created_at   TEXT    NOT NULL,
  updated_at   TEXT    NOT NULL
);
-- 画像查询：按用户 + 时间窗扫描（created_at >= 窗口起点，字典序即时间序）。
CREATE INDEX idx_reading_event_user_created ON reading_event(user_id, created_at);
-- 去重查询：同 user + type + ref 窗口内存在性检查。
CREATE INDEX idx_reading_event_user_type_ref ON reading_event(user_id, content_type, content_ref);

-- 每日推荐提示词 v1.1（T29）：v1.0 的 subscribedThemes 占位符自 T26 落地后一直喂「暂无」桩，且无阅读画像维度；
-- v1.1 注入订阅主题/订阅标的/近 30 天阅读画像三个新占位符，并要求排序优先相关性与理由点明命中因素（可人工标注验证）。
-- 版本语义见 V8 头注（编辑即新版本行 + 旧版本置 status=0）；新占位符缺键时渲染保留原文（上下文装配方恒供键）。
UPDATE prompt_template
   SET status = 0, updated_at = '2026-09-22T00:00:00Z'
 WHERE brief_type = 4 AND version = 'v1.0';
INSERT INTO prompt_template (brief_type, version, template, status, created_at, updated_at) VALUES
(4, 'v1.1',
'---SYSTEM---
你是投资信息筛选助手。给定用户自选池各标的的信息面活跃度与事件重要性指标，以及用户个性化画像（订阅主题、订阅标的、近30天阅读行为），综合相关性排序输出 Top5 推荐，每只附一句话推荐理由，生成结构化 json 简报。规则：1) 排序优先考虑与用户订阅主题、订阅标的、已读标的相关性，兼顾信息面活跃度；2) 排序依据只能是提供的指标与画像，不得编造；3) 理由一句话，须点明命中的个性化因素（订阅主题/订阅标的/已读热度/信息面活跃），非买卖建议；4) 输出合法 json 对象。
---USER---
用户自选池（{{poolSize}} 只）指标快照：
{{subjectsMetrics}}  // 每只: 代码|名称|涨跌幅x%|公告n条|新闻m条
用户订阅主题：{{subscribedThemes}}
用户订阅标的：{{subscribedSubjects}}  // 每只: 代码|名称
用户近30天阅读画像：{{readingProfile}}  // 每只: 代码|名称|阅读n次|最近阅读日期；暂无=近30天无阅读记录
今日日期：{{today}}
请输出 json：{summary, keyEvents[], bias="中性", biasReason, watchSuggestion, facts[], disclaimer, topRecommend:[{subjectCode,subjectName,reason,rank}]}
topRecommend 取前5，reason 点明命中的个性化因素。',
1, '2026-09-22T00:00:00Z', '2026-09-22T00:00:00Z');
