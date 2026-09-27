import { useCallback, useEffect, useRef, useState } from 'react';
import { ExternalLink } from 'lucide-react';
import { ApiError } from '@/api/http';
import { listNewsLibraryPaged } from '@/api/newsItem';
import { Badge } from '@/components/ui/badge';
import { Button } from '@/components/ui/button';
import { Dialog } from '@/components/ui/dialog';
import { Pagination } from '@/components/ui/pagination';
import { Skeleton } from '@/components/ui/skeleton';
import { formatDateTime } from '@/lib/format';
import type { NewsL0Filter, NewsLibraryItem } from '@/types/newsItem';

// 大盘可点数字弹框（V2.4 T214，REQ-20260928-20 拍板三）：数字 → 分页条目列表直查。
// 复用资讯库数据端点（news-items 页码模式）+ Pagination 组件；预填筛选只读呈现于标题
// （口径明示）；弹框内不提供二次改筛（保持「数字 → 列表」直查心智，深查去资讯库）；
// 独立请求不随大盘 30s 轮询重发；Esc/遮罩/关闭钮任一可关，重开状态复位。

/** 弹框缺省页大小（对齐资讯库 DEFAULT_NEWS_LIBRARY_PAGE_SIZE）。 */
const DIALOG_PAGE_SIZE = 20;

/**
 * 弹框查询规格（由被点数字预填，只读呈现）。
 *
 * @param title 标题口径（如「今日入库 · 全部源」「累计条目 · {源名}」）
 * @param sourceId 源过滤；null = 全部源
 * @param l0 L0 状态（对账口径必须 ALL——大盘计数含全部 L0 态）
 * @param fetchedFrom/fetchedTo 入库时间窗（yyyy-MM-dd 上海日界；今日入库弹框 = 今日，累计弹框 = null）
 * @param deepLink 「去资讯库深查」带参跳转（sourceId/l0 预填，资讯库消费）
 */
export interface NewsItemsDialogSpec {
  title: string;
  sourceId?: number | null;
  l0: NewsL0Filter;
  fetchedFrom?: string | null;
  fetchedTo?: string | null;
  deepLink: string;
}

interface NewsItemsDialogProps {
  /** 受控显隐（spec 非空即开——重开由外层换新 spec 实现状态复位）。 */
  spec: NewsItemsDialogSpec | null;
  onClose: () => void;
}

/** L0 徽章（沿资讯库同语义）。 */
const L0_BADGES: Record<string, { label: string; className: string }> = {
  PASS: { label: '通过', className: 'bg-emerald-500/15 text-emerald-400' },
  NOISE: { label: '噪音', className: 'bg-muted text-muted-foreground' },
  NEAR_DUP: { label: '近重复', className: 'bg-amber-500/15 text-amber-400' },
};

function messageOf(err: unknown, fallback: string): string {
  return err instanceof ApiError ? err.msg : fallback;
}

/** 弹框条目行（紧凑形态：标题外链 / 源徽章 / 抓取时间 / L0 徽章）。 */
function DialogRow({ item }: { item: NewsLibraryItem }) {
  const l0 = L0_BADGES[item.l0Result] ?? L0_BADGES.PASS;
  return (
    <div
      className="flex flex-col gap-1 border-b border-border py-2 last:border-b-0"
      data-testid={`dashboard-items-item-${item.id}`}
    >
      {item.url ? (
        <a
          href={item.url}
          target="_blank"
          rel="noreferrer"
          className="inline-flex items-start gap-1 text-sm font-medium underline-offset-4 hover:underline"
          data-testid={`dashboard-items-title-${item.id}`}
        >
          {item.title}
          <ExternalLink className="mt-0.5 size-3 shrink-0 text-muted-foreground" aria-hidden="true" />
        </a>
      ) : (
        <span className="text-sm font-medium" data-testid={`dashboard-items-title-${item.id}`}>
          {item.title}
        </span>
      )}
      <div className="flex flex-wrap items-center gap-x-3 gap-y-1 text-xs text-muted-foreground">
        <Badge variant="outline">{item.sourceName ?? `源 ${item.sourceId}`}</Badge>
        <span>抓取 {formatDateTime(item.fetchedAt)}</span>
        <Badge className={l0.className}>{l0.label}</Badge>
        {item.l1Main ? <Badge variant="secondary">{item.l1Main}</Badge> : null}
      </div>
    </div>
  );
}

/**
 * 大盘数字下钻弹框：分页条目列表 + 标题 total 对账呈现。
 * 挂载态由 spec 变化驱动（外层重开换新 spec 引用即复位 page=1）。
 */
export function NewsItemsDialog({ spec, onClose }: NewsItemsDialogProps) {
  const [items, setItems] = useState<NewsLibraryItem[]>([]);
  const [total, setTotal] = useState(0);
  const [page, setPage] = useState(1);
  const [pageSize, setPageSize] = useState(DIALOG_PAGE_SIZE);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const abortRef = useRef<AbortController | null>(null);

  // spec 变化（含重开换新引用）→ 状态复位（page=1、页大小回缺省）
  useEffect(() => {
    if (spec) {
      setPage(1);
      setPageSize(DIALOG_PAGE_SIZE);
    }
  }, [spec]);

  const load = useCallback(
    async (target: number, size: number) => {
      if (!spec) return;
      abortRef.current?.abort();
      const ctrl = new AbortController();
      abortRef.current = ctrl;
      setLoading(true);
      setError(null);
      try {
        const view = await listNewsLibraryPaged(
          {
            sourceId: spec.sourceId ?? null,
            l0: spec.l0,
            l1: null,
            fetchedFrom: spec.fetchedFrom ?? null,
            fetchedTo: spec.fetchedTo ?? null,
            page: target,
            size,
          },
          ctrl.signal,
        );
        if (ctrl.signal.aborted) return;
        setItems(view.items);
        setTotal(view.total);
        setPage(target);
      } catch (err) {
        if (ctrl.signal.aborted) return;
        setError(messageOf(err, '条目列表加载失败'));
      } finally {
        if (!ctrl.signal.aborted) setLoading(false);
      }
    },
    [spec],
  );

  useEffect(() => {
    if (spec) void load(1, DIALOG_PAGE_SIZE);
    return () => abortRef.current?.abort();
  }, [spec, load]);

  if (!spec) return null;

  return (
    <Dialog
      open
      title={`${spec.title} · 共 ${total} 条`}
      onClose={onClose}
      footer={
        <>
          <a
            href={`#${spec.deepLink}`}
            className="mr-auto text-sm text-primary underline-offset-4 hover:underline"
            data-testid="dashboard-items-deep-link"
          >
            去资讯库深查 →
          </a>
          <Button variant="outline" size="sm" onClick={onClose}>
            关闭
          </Button>
        </>
      }
    >
      <div
        className="max-h-[26rem] min-h-[8rem] overflow-y-auto pr-1"
        data-testid="dashboard-items-list"
      >
        {loading ? (
          <div className="flex flex-col gap-2" data-testid="dashboard-items-loading">
            {Array.from({ length: 3 }, (_, i) => (
              <Skeleton key={i} className="h-14 w-full" />
            ))}
          </div>
        ) : error ? (
          <div
            className="flex flex-col items-start gap-2 py-4"
            data-testid="dashboard-items-error"
            role="alert"
          >
            <p className="text-sm text-destructive">{error}</p>
            <Button
              variant="outline"
              size="sm"
              onClick={() => void load(page, pageSize)}
              data-testid="dashboard-items-retry"
            >
              重试
            </Button>
          </div>
        ) : items.length === 0 ? (
          <p
            className="py-6 text-center text-sm text-muted-foreground"
            data-testid="dashboard-items-empty"
          >
            该口径下暂无条目
          </p>
        ) : (
          items.map((item) => <DialogRow key={item.id} item={item} />)
        )}
      </div>
      <p className="text-xs text-muted-foreground" data-testid="dashboard-items-total">
        口径：全部状态（l0=ALL）
        {spec.fetchedFrom ? ` · 入库日 ${spec.fetchedFrom}` : ' · 不限入库时间'}
        {spec.sourceId ? ' · 指定源' : ' · 全部源'}——弹框共 {total} 条，与被点数字对账
      </p>
      <Pagination
        page={page}
        pageSize={pageSize}
        total={total}
        disabled={loading}
        onPageChange={(target) => void load(target, pageSize)}
        onPageSizeChange={(size) => {
          setPageSize(size);
          void load(1, size);
        }}
        label="大盘条目分页"
        testIdPrefix="dashboard-items-pagination"
      />
    </Dialog>
  );
}

export default NewsItemsDialog;
