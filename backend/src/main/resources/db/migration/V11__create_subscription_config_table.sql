-- T26 · 订阅配置表（精细订阅 + CRUD + 退订降噪）
-- 来源：技术方案 §4.2 subscription_config DDL（原样落）+ §4.1.6 订阅接口 + §4.4 幂等键 userId+subType+subKey
--       + PRD 故事 5（主题/标的/事件类型订阅 + 退订后续不推送不入流）。
-- 表用途：T26 SubscriptionService CRUD（subscribe 幂等 / unsubscribe 软退订 status=0 / list 游标分页）；
--          T26 后 PushService 的 SubscriptionResolver 切 SubscriptionConfigSubscriptionResolver
--          按 sub_type=2(标的) AND sub_key=subjectId AND status=1 解析异动推送目标（替代 M1 watchlist 隐含订阅）；
--          T27 信息流命中引擎按全部 active 订阅（findAllActive）匹配内容。
-- 行级权限：所有面向用户的查询带 WHERE user_id=?（端口层约束，T26 防越权，与 watchlist 同模式）；
--          仅 existsById 不带 owner（返回布尔存在性，供应用层区分 404/403）。
-- 幂等：§4.4「幂等业务语义键」= userId + subType + subKey——subscribe 已订阅直返 / 已退订重新激活（UPDATE status，
--       复用同一天然键行，不新增行）；DB UNIQUE(user_id, sub_type, sub_key) 为最后防线（同 watchlist_item UNIQUE 模式，
--       极小概率竞态下触发 UncategorizedSQLException→500，应用层预检正常路径返回幂等结果）。
-- 退订：status 1→0 软退订（保留行不物理删除，便于重新订阅复用同键 + 退订后不推送不入流由解析方 status=1 过滤）。
-- 类型：sub_type TINYINT（1主题 2标的 3事件类型 4政策主题）；channel TINYINT（1应用内 2邮件预留，DEFAULT 1）；
--       sub_key TEXT（主题词/subjectId 字符串/事件类型枚举值/政策主题词）——标的订阅时存 String.valueOf(subjectId)，
--       SubscriptionConfigSubscriptionResolver 按 sub_key 文本匹配 subjectId。
CREATE TABLE subscription_config (
  id          INTEGER PRIMARY KEY AUTOINCREMENT,
  user_id     INTEGER NOT NULL,
  sub_type    TINYINT NOT NULL,               -- 1主题 2标的 3事件类型 4政策主题
  sub_key     TEXT    NOT NULL,               -- 主题词/subjectId/事件类型枚举/政策主题词
  channel     TINYINT NOT NULL DEFAULT 1,      -- 1应用内 2邮件(预留)
  status      TINYINT NOT NULL DEFAULT 1,      -- 1订阅中 0已退订
  created_at  TEXT    NOT NULL,
  updated_at  TEXT    NOT NULL,
  version     INTEGER NOT NULL DEFAULT 0,
  UNIQUE(user_id, sub_type, sub_key)
);
CREATE INDEX idx_sub_user_type ON subscription_config(user_id, sub_type);
