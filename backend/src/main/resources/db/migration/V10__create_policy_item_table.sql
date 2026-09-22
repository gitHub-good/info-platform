-- T24 · 政策条目表（关联行业标签 + ai_tendency 倾向占位）
-- 来源：技术方案 §4.2 policy_item DDL + §4.1.5 政策时事流接口（GET /api/v1/policies 列表 + GET /{id}
--        详情含关联自选标的 + ai_tendency）+ PRD 故事 4（政策流：标题/来源/时间/摘要/关联行业标签；详情含关联自选标的）。
-- 表用途：T24 PolicyFetchJob @Scheduled 调 T07 GovPolicyClient 抓 gov.cn/zhengce 最近政策 → existsBySourceUrl
--          去重 → PolicyIndustryClassifier 按标题关键词标注 related_industries → saveAll 落库；
--          PolicyService 游标分页读（id DESC，newest-first）+ 详情按 related_industries 匹配当前用户 watchlist 关联自选标的。
-- 约定：published_at 存 ISO-8601 日期文本（yyyy-MM-dd，gov.cn 列表页仅给日期粒度，字典序即时间序，命中索引范围扫描）；
--        related_industries 存 JSON 数组文本（["白酒","银行"]），MyBatis-Plus JacksonTypeHandler 序列化；
--        ai_tendency NOT NULL DEFAULT 0 未判（T28 AI 倾向判断填 1利好/2利空/3中性，T24 全置 0）；
--        source_url 作去重键（existsBySourceUrl 防同一条政策重复入库）；summary 可空（列表页无摘要，待详情页/T28 填）；
--        无 version 列（追加型政策流，无并发 UPDATE 竞争，同 anomaly_event）。
CREATE TABLE policy_item (
  id                  INTEGER PRIMARY KEY AUTOINCREMENT,
  title               TEXT    NOT NULL,
  source              TEXT    NOT NULL,             -- 来源标签（gov.cn/zhengce = "国务院政策"）
  published_at        TEXT    NOT NULL,             -- ISO-8601 日期 yyyy-MM-dd
  summary             TEXT,                          -- 摘要（列表页无，待详情页/T28 填，可空）
  related_industries  TEXT,                          -- JSON 行业标签数组 ["白酒","银行"]
  ai_tendency         TINYINT NOT NULL DEFAULT 0,   -- 0未判 1利好 2利空 3中性（T24 默认 0，T28 填）
  source_url          TEXT,                          -- 详情 URL（去重键）
  created_at          TEXT    NOT NULL,
  updated_at          TEXT    NOT NULL
);
CREATE INDEX idx_policy_published ON policy_item(published_at);
