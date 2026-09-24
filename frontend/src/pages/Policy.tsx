import { useCallback, useEffect, useRef, useState } from 'react';
import { Button } from '@/components/ui/button';
import { Skeleton } from '@/components/ui/skeleton';
import { IndustryFilter } from '@/components/policy/IndustryFilter';
import { PolicyDetail } from '@/components/policy/PolicyDetail';
import { PolicyList } from '@/components/policy/PolicyList';
import { ApiError } from '@/api/http';
import { trackReadingOnce } from '@/api/readingEvent';
import { DEFAULT_POLICY_DAYS, getPolicy, listPolicies } from '@/api/policy';
import type { PolicyDetailView, PolicyView } from '@/types/policy';

/** 非 ApiError 兜底文案。 */
function messageOf(err: unknown, fallback: string): string {
  return err instanceof ApiError ? err.msg : fallback;
}

/** 详情错误映射：30040 政策条目不存在 → 友好提示。 */
function detailErrorMessage(err: unknown): string {
  if (err instanceof ApiError && err.code === 30040) return '政策条目不存在或已下线';
  return messageOf(err, '详情加载失败');
}

/**
 * 政策时事页（技术方案 §4.1.5 + PRD 故事 4 + T25）。
 * - 列表：GET /policies?days=7&industry=&cursor=（游标分页，每页 20）。
 * - 行业过滤：下拉选行业 → 重置列表带 industry 参数重新拉首页。
 * - 分页：nextCursor 存在时「加载更多」追加下一页；翻页失败保留既有条目，按钮变重试入口。
 * - 详情：点条目 → GET /policies/{id} → 详情 + 关联自选标的 + aiTendency 倾向徽章。
 * 三态：加载骨架 / 空数据引导 / 错误重试；受保护接口 401 由 http 层统一跳 /login。
 */
export function Policy() {
  const [items, setItems] = useState<PolicyView[]>([]);
  const [nextCursor, setNextCursor] = useState<number | null>(null);
  const [listLoading, setListLoading] = useState(true);
  const [listError, setListError] = useState<string | null>(null);
  const [industry, setIndustry] = useState('');

  const [selectedId, setSelectedId] = useState<number | null>(null);
  const [detail, setDetail] = useState<PolicyDetailView | null>(null);
  const [detailLoading, setDetailLoading] = useState(false);
  const [detailError, setDetailError] = useState<string | null>(null);
  const [loadingMore, setLoadingMore] = useState(false);
  // 翻页失败独立提示：保留既有列表，「加载更多」按钮即重试入口（对齐 Feed 基线）
  const [loadMoreError, setLoadMoreError] = useState<string | null>(null);

  // 切换行业过滤时取消在途列表请求，避免旧响应覆盖新结果
  const listAbort = useRef<AbortController | null>(null);

  const loadFirst = useCallback(async (ind: string) => {
    listAbort.current?.abort();
    const ctrl = new AbortController();
    listAbort.current = ctrl;
    setListLoading(true);
    setListError(null);
    setLoadMoreError(null);
    try {
      const data = await listPolicies(DEFAULT_POLICY_DAYS, ind || null, null, ctrl.signal);
      if (ctrl.signal.aborted) return;
      setItems(data.policies);
      setNextCursor(data.nextCursor);
    } catch (err) {
      if (ctrl.signal.aborted) return;
      setListError(messageOf(err, '政策列表加载失败'));
    } finally {
      if (!ctrl.signal.aborted) setListLoading(false);
    }
  }, []);

  // 首次加载首页
  useEffect(() => {
    void loadFirst('');
    return () => listAbort.current?.abort();
  }, [loadFirst]);

  const handleIndustryChange = (ind: string) => {
    setIndustry(ind);
    setSelectedId(null);
    setDetail(null);
    setDetailError(null);
    void loadFirst(ind);
  };

  // 翻页：追加不清已有条目；失败保留条目并露出错误与重试入口
  const handleLoadMore = async () => {
    if (nextCursor == null || loadingMore) return;
    setLoadingMore(true);
    setLoadMoreError(null);
    try {
      const data = await listPolicies(DEFAULT_POLICY_DAYS, industry || null, nextCursor);
      setItems((prev) => [...prev, ...data.policies]);
      setNextCursor(data.nextCursor);
    } catch (err) {
      setLoadMoreError(messageOf(err, '加载更多失败'));
    } finally {
      setLoadingMore(false);
    }
  };

  const handleSelect = useCallback(async (id: number) => {
    setSelectedId(id);
    setDetailLoading(true);
    setDetailError(null);
    setDetail(null);
    try {
      const view = await getPolicy(id);
      setDetail(view);
      // 阅读埋点（T29）：详情加载成功后上报一次（会话级去重、静默失败）
      trackReadingOnce(`policy:${id}`, {
        contentType: 'POLICY',
        contentRef: String(id),
      });
    } catch (err) {
      setDetailError(detailErrorMessage(err));
    } finally {
      setDetailLoading(false);
    }
  }, []);

  const handleRetryList = () => void loadFirst(industry);
  const handleRetryDetail = () => {
    if (selectedId != null) void handleSelect(selectedId);
  };

  // 行业选项：从已加载政策 relatedIndustries 去重排序
  const industries = Array.from(
    new Set(items.flatMap((p) => p.relatedIndustries)),
  ).sort();

  const hasMore = nextCursor != null;

  return (
    <main className="mx-auto w-full max-w-6xl p-4 sm:p-6" data-testid="policy-page">
      <header className="mb-4">
        <h1 className="text-xl font-medium">政策时事</h1>
      </header>

      <div className="mb-4">
        <IndustryFilter
          industries={industries}
          value={industry}
          onChange={handleIndustryChange}
          disabled={listLoading}
        />
      </div>

      <div className="grid grid-cols-1 gap-4 lg:grid-cols-2">
        <section data-testid="policy-list-section">
          {listLoading ? (
            <div className="flex flex-col gap-2" data-testid="policy-list-loading">
              {Array.from({ length: 4 }, (_, i) => (
                <Skeleton key={i} className="h-24 w-full" />
              ))}
            </div>
          ) : listError ? (
            <div
              className="flex flex-col items-start gap-2"
              data-testid="policy-list-error"
            >
              <p className="text-sm text-destructive" role="alert">
                {listError}
              </p>
              <Button
                variant="outline"
                size="sm"
                onClick={handleRetryList}
                data-testid="policy-list-retry"
              >
                重试
              </Button>
            </div>
          ) : items.length === 0 ? (
            <div
              className="py-10 text-center text-sm text-muted-foreground"
              data-testid="policy-list-empty"
            >
              最近 {DEFAULT_POLICY_DAYS} 天暂无政策条目
            </div>
          ) : (
            <>
              <PolicyList
                items={items}
                selectedId={selectedId}
                onSelect={handleSelect}
              />
              {hasMore ? (
                <div className="mt-3 flex flex-col items-center gap-2">
                  {loadMoreError ? (
                    <p
                      className="text-sm text-destructive"
                      role="alert"
                      data-testid="policy-more-error"
                    >
                      {loadMoreError}
                    </p>
                  ) : null}
                  <Button
                    variant="outline"
                    size="sm"
                    className="w-full"
                    onClick={handleLoadMore}
                    disabled={loadingMore}
                    data-testid="policy-load-more"
                  >
                    {loadingMore ? '加载中…' : '加载更多'}
                  </Button>
                </div>
              ) : null}
            </>
          )}
        </section>

        <section data-testid="policy-detail-section" className="self-start">
          <PolicyDetail
            detail={detail}
            loading={detailLoading}
            error={detailError}
            onRetry={handleRetryDetail}
          />
        </section>
      </div>
    </main>
  );
}

export default Policy;
