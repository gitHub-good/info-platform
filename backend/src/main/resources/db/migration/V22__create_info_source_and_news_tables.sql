-- T100/T104 · M13 源接入框架四表（资讯源注册表 / 轮询状态 / 资讯统一库 / 日统计 rollup）
-- 来源：技术方案-V2.0-M13 §4.1 DDL + ADR-0038（源注册表 DB 化与六源边界）/ ADR-0039（资讯统一库独立建表与跨源指纹去重）。
-- 惯例对齐：小写下划线；三必备 created_at/updated_at；NOT NULL+默认值优先；时间戳整秒 ISO-8601（UTC）文本，字典序即时间序。

-- 资讯源注册表（ADR-0038：预置源+通用源统一模型；config 存 JSON 文档，结构与校验见方案 §4.3）。
-- 种子：InfoSourceSeeder 启动 seed-if-absent（按 source_code，DB 为权威——ADR-0032 同系列），预置目录见 InfoSourceCatalog。
-- 软删语义：deleted=1 → 调度摘除 + 配置归档（页面可恢复=改回 0）+ 历史条目保留（news_item 不动）。
-- 与既有 runtime_config datasource.{CODE} 键空间并存、边界冻结（ADR-0038：资讯源配置不入 runtime_config）。
CREATE TABLE info_source (
  id               INTEGER PRIMARY KEY AUTOINCREMENT,
  source_code      TEXT    NOT NULL,               -- 稳定代码（预置源固定；通用源按名称拼音/用户输入，唯一）
  name             TEXT    NOT NULL,               -- 展示名（如「金十数据·快讯」）
  category         TEXT    NOT NULL,               -- 分组标签：快讯/媒体/政策/宏观/国际/自建
  adapter_type     TEXT    NOT NULL,               -- rss / json_api / preset（html_template 预留不开放）
  adapter_ref      TEXT,                           -- preset 时的 Spring bean 名；rss/json_api 为 NULL
  endpoint         TEXT    NOT NULL,               -- 拉取端点（查询参数含在 URL；翻页参数由引擎按需追加）
  config           TEXT    NOT NULL DEFAULT '{}',  -- JSON：映射/包装剥离/headers/条数上限/游标声明（§4.3）
  interval_minutes INTEGER NOT NULL DEFAULT 15,    -- 轮询间隔（分钟），1~60，兼作源频控下限
  enabled          TINYINT NOT NULL DEFAULT 1,     -- 启停：0 即调度摘除（下一 tick 生效）
  is_preset        TINYINT NOT NULL DEFAULT 0,     -- 预置源=1：无删除入口（蓝图裁决 1）
  deleted          TINYINT NOT NULL DEFAULT 0,     -- 软删（仅通用源）：调度摘除 + 配置归档可恢复
  created_at       TEXT    NOT NULL,
  updated_at       TEXT    NOT NULL,
  UNIQUE(source_code)
);

-- 源轮询状态（1:1 info_source；配置行与高频运行态分离：调度器每轮写、页面读摘要）。
-- 事务边界（方案 §4.4）：单轮「条目批量落库 + 游标推进 + 日统计自增」同一事务；游标只随条目成功推进（不漏），
-- 崩溃重拉由唯一索引幂等吸收（不重）。
CREATE TABLE source_poll_state (
  source_id            INTEGER PRIMARY KEY,        -- = info_source.id（1:1，无自增）
  last_attempt_at      TEXT,                       -- 最近尝试时刻
  last_success_at      TEXT,                       -- 最近成功时刻（断流 gap 基准）
  next_due_at          TEXT    NOT NULL,           -- 下次应抓时刻（含错峰偏移；到期判定 max(此值, backoff_until)）
  cursor_value         TEXT,                       -- 增量游标值（语义由源声明：ID 数值串 / ISO 时间 / NULL=NONE）
  cursor_updated_at    TEXT,
  consecutive_failures INTEGER NOT NULL DEFAULT 0, -- 连续失败数（成功清零；驱动指数退避）
  backoff_until        TEXT,                       -- 退避截止（NULL=不在静默期）
  last_duration_millis INTEGER,                    -- 最近一轮耗时（大盘延迟面）
  last_round_detail    TEXT,                       -- 最近轮明细（ADR-0036 段式惯例）：new=3; dup=1; pages=1; backfill=none
  last_error           TEXT,                       -- 最近失败摘要（页面/大盘诊断）
  created_at           TEXT    NOT NULL,
  updated_at           TEXT    NOT NULL
);

-- 资讯统一库（ADR-0039：独立于 policy_item；跨源指纹全局唯一 + 源内 external_id 唯一双保险）。
-- published_at：源发布时间（分钟级感知延迟口径基准）；源仅给日期 → 归一 00:00:00；
-- status：1=正常 2=源归档（软删源的历史条目标记）3+=M15 垃圾/低质预留。
-- 大字段策略：正文不入库（标题+摘要上限约 1KB，M13 非目标），无 TEXT 拆表诉求。
CREATE TABLE news_item (
  id           INTEGER PRIMARY KEY AUTOINCREMENT,
  source_id    INTEGER NOT NULL,                   -- info_source.id（不做物理 FK，SQLite 惯例 + 软删源条目保留）
  external_id  TEXT,                               -- 源侧条目 id/guid（RSS 无 guid 取 link；可空靠指纹兜底）
  title        TEXT    NOT NULL,
  summary      TEXT,                               -- 摘要/首段（richtext 已转纯文本）
  url          TEXT,
  author       TEXT,                               -- 媒体名/记者（可空）
  published_at TEXT    NOT NULL,                   -- ISO-8601 整秒（源侧发布时间）
  fetched_at   TEXT    NOT NULL,                   -- 抓取入库时刻（与 created_at 同刻，感知延迟口径直读列）
  fingerprint  TEXT    NOT NULL,                   -- sha256（归一化规则 ADR-0039，§4.2）
  status       TINYINT NOT NULL DEFAULT 1,
  created_at   TEXT    NOT NULL,
  updated_at   TEXT    NOT NULL
);
CREATE UNIQUE INDEX idx_news_fp        ON news_item(fingerprint);             -- 跨源同文去重（全局首个胜）
CREATE UNIQUE INDEX idx_news_src_ext   ON news_item(source_id, external_id);  -- 源内精确去重（NULL 豁免）
CREATE INDEX idx_news_published        ON news_item(published_at);            -- 全局资讯流 newest-first
CREATE INDEX idx_news_source_published ON news_item(source_id, published_at); -- 按源下钻/游标判定已见

-- 抓取统计日 rollup（方案 §3.5：大盘数据面；每轮原子自增，UNIQUE 幂等锚点）。
-- stat_date：Asia/Shanghai yyyy-MM-dd（对齐「今日」运营心智；与 UTC created_at 差 8h，统计语义优先本地日）。
CREATE TABLE source_daily_stats (
  id          INTEGER PRIMARY KEY AUTOINCREMENT,
  source_id   INTEGER NOT NULL,
  stat_date   TEXT    NOT NULL,                    -- yyyy-MM-dd（Asia/Shanghai）
  poll_count  INTEGER NOT NULL DEFAULT 0,          -- 当轮 +1（成功失败都计）
  fail_count  INTEGER NOT NULL DEFAULT 0,          -- 失败轮 +1
  new_count   INTEGER NOT NULL DEFAULT 0,          -- 新入库条数（应插−实插=dup 不计）
  dup_count   INTEGER NOT NULL DEFAULT 0,          -- 被唯一索引拦截条数（同源重复+跨源同文）
  created_at  TEXT    NOT NULL,
  updated_at  TEXT    NOT NULL,
  UNIQUE(source_id, stat_date)
);
