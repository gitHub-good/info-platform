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

/** 清单项排序键：addedAt（加入顺序，缺省）/ price（最新价）/ changePct（涨跌幅）。 */
export type WatchlistItemsSortKey = 'addedAt' | 'price' | 'changePct';

/** 清单项分页行（GET /watchlists/{id}/items 元素）：清单项 + 标的摘要 + 行情两列内联。 */
export interface WatchlistItemPagedRow {
  id: number;
  subjectId: number;
  /** 异动阈值（%），默认 3.00 */
  anomalyThreshold: number;
  /** 标的摘要随行情同源返回；行情整体缺失时为 null（前端回退数字主键） */
  subjectCode: string | null;
  name: string | null;
  market: string | null;
  industry: string | null;
  /** 最新价（元）；行情缺失 null 显示「—」且排序沉底 */
  price: number | null;
  /** 日涨跌幅（%）；行情缺失 null */
  changePct: number | null;
}

/** 清单项分页视图（M9 页码契约 {total, items, page, size} + sort/dir 回显）。 */
export interface WatchlistItemsPagedView {
  total: number;
  items: WatchlistItemPagedRow[];
  page: number;
  size: number;
  sort: WatchlistItemsSortKey;
  dir: 'asc' | 'desc';
}
