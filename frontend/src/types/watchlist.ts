// 自选清单视图契约（对齐技术方案 §4.1.2 + 后端 WatchlistView/WatchlistItemView）。
// 列表 GET /watchlists 与单查 GET /watchlists/{id} 均返回 WatchlistView（含 items）。

export interface WatchlistItemView {
  id: number;
  subjectId: number;
  /** 异动阈值（%），默认 3.00 */
  anomalyThreshold: number;
  /** 1 启用 / 0 删除 */
  status: number;
}

export interface WatchlistView {
  id: number;
  name: string;
  remark: string | null;
  status: number;
  items: WatchlistItemView[];
}

/** 加标的请求体。 */
export interface AddItemRequest {
  subjectId: number;
  anomalyThreshold?: number;
}

/** 改异动阈值请求体。 */
export interface UpdateThresholdRequest {
  anomalyThreshold: number;
}
