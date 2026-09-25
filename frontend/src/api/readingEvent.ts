// 阅读行为埋点数据适配层（T29 推荐相关性优化，对齐后端 ReadingEventController）。
// POST /api/v1/reading-events（Bearer）：详情页/政策详情/简报页/信息流「点原文」（M11/REQ-20260925-08 增 FEED）
// 阅读事件上报，供每日推荐的个性化相关性排序（已读标的热度 + 时间衰减）消费。
//
// 设计约定（对齐任务要求「不打扰交互，静默失败不影响主流程」）：
// - fire-and-forget：trackReading 内部捕获全部异常（仅 console.debug 留痕），调用方不 await 不 try；
// - 幂等友好：后端同 user+type+ref 1 小时窗口去重（recorded=false 非 2001）；
//   前端再叠加会话级 once 守卫（防 React StrictMode 双触发与短时重挂载重复上报）。

import { request } from './http';

/** 阅读内容类型（对齐后端 ReadingEventType 枚举名；FEED=信息流「点原文」阅读）。 */
export type ReadingContentType = 'SUBJECT_DETAIL' | 'POLICY' | 'AI_BRIEF' | 'FEED';

/** 阅读留痕请求体：contentType/contentRef 必填；subjectCode/subjectId 二选一可空（政策等无标的阅读）。 */
export interface ReadingEventPayload {
  contentType: ReadingContentType;
  /** 内容引用：标的代码 / 政策 id / 简报 taskId / 信息流条目稳定 contentId */
  contentRef: string;
  /** 标的代码（详情页埋点传） */
  subjectCode?: string;
  /** 标的 id（简报页埋点传，后端优先于 subjectCode） */
  subjectId?: number;
}

/** 会话级已上报守卫键集合（模块级，跨组件挂载存活）。 */
const sentKeys = new Set<string>();

/**
 * 上报一次阅读事件（静默失败：任何异常仅 console.debug，不外抛、不打扰主流程）。
 */
export async function trackReading(payload: ReadingEventPayload): Promise<void> {
  try {
    await request('/reading-events', { method: 'POST', body: payload });
  } catch (err) {
    // 埋点是旁路增强：网络/鉴权/校验失败不影响页面主流程，仅调试留痕
    console.debug('[reading-event] 上报失败（忽略）', payload.contentType, payload.contentRef, err);
  }
}

/**
 * 会话内同键只上报一次（推荐入口）。
 *
 * @param key 会话级去重键（如 `subject:SH600519` / `policy:42` / `brief:7`）
 * @param payload 上报载荷
 */
export function trackReadingOnce(key: string, payload: ReadingEventPayload): void {
  if (sentKeys.has(key)) return;
  sentKeys.add(key);
  void trackReading(payload);
}

/** 清空会话守卫（测试用）。 */
export function resetReadingTrackerForTest(): void {
  sentKeys.clear();
}
