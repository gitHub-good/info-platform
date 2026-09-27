import { useEffect, useMemo, useState } from 'react';
import { Search } from 'lucide-react';
import { getDatasourceConfigs } from '@/api/datasourceConfig';
import { getInfoSources } from '@/api/infoSource';
import { BizSourcesPanel } from '@/components/sources/BizSourcesPanel';
import { InfoSourcesPanel } from '@/components/sources/InfoSourcesPanel';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { currentRoute, navigate, queryOf, sourcesTabOf, type SourcesTab } from '@/lib/navigation';
import type { DataSourceConfigView } from '@/types/datasourceConfig';
import type { InfoSourcesView } from '@/types/infoSource';

// 源管理页（V2.3-M23 T204，方案 §3.4 / §4.5）：#/sources 双 Tab 单页——
// 资讯源（轮询采集型，默认高频运维面）与业务数据源（按需拉取型）两面板组件复用（components/sources/*），
// 页壳只提供：页头 + 统一搜索 + 概览条 + Tab 容器（?tab=info|biz，URL 态随刷新/分享保持）。
// 概览条零新端点：GET /info-sources 与既有 datasource-configs 两列表端点前端聚合（失败段静默降级为 —）。
// 旧路由 #/datasource-config / #/info-sources 由 App.tsx 归一层 replace 重定向（query 透传）。

const TAB_META: Record<SourcesTab, { label: string; testId: string }> = {
  info: { label: '资讯源', testId: 'sources-tab-info' },
  biz: { label: '业务数据源', testId: 'sources-tab-biz' },
};

interface InfoOverviewStat {
  enabled: number;
  total: number;
  todayNew: number;
}

interface BizOverviewStat {
  healthy: number;
  total: number;
}

/** 资讯源概览聚合：活跃（未归档）源启用 N/M + 今日入库（today.newCount 求和）。 */
function infoStatOf(view: InfoSourcesView): InfoOverviewStat {
  const active = view.groups.flatMap((group) => group.sources);
  return {
    enabled: active.filter((source) => source.enabled).length,
    total: active.length,
    todayNew: active.reduce((sum, source) => sum + source.today.newCount, 0),
  };
}

/** 业务源概览聚合：健康 N/M（最近一次抓取事件 OK 记为健康，含事件源）。 */
function bizStatOf(view: DataSourceConfigView): BizOverviewStat {
  return {
    healthy: view.sources.filter((source) => source.health.lastEventType === 'OK').length,
    total: view.sources.length,
  };
}

/** 关键词对源卡（名称/代码）的包含匹配（统一搜索口径，不区分大小写；空串全命中）。 */
function sourceMatches(keyword: string, name: string, code: string): boolean {
  const kw = keyword.trim().toLowerCase();
  return (
    !kw || name.toLowerCase().includes(kw) || code.toLowerCase().includes(kw)
  );
}

interface OverviewBarProps {
  info: InfoOverviewStat | null;
  biz: BizOverviewStat | null;
}

/** 概览条：两段式轻聚合（任一段加载失败/在途显示 —，不阻塞面板主路径）。 */
function OverviewBar({ info, biz }: OverviewBarProps) {
  return (
    <div
      data-testid="sources-overview"
      className="flex flex-wrap items-center gap-x-6 gap-y-1 rounded-lg border px-4 py-2 text-sm text-muted-foreground"
    >
      <span data-testid="sources-overview-info">
        {info ? `资讯源 启用 ${info.enabled}/${info.total} · 今日入库 ${info.todayNew}` : '资讯源 —'}
      </span>
      <span data-testid="sources-overview-biz">
        {biz ? `业务源 健康 ${biz.healthy}/${biz.total}` : '业务源 —'}
      </span>
    </div>
  );
}

interface SourcesTabsProps {
  tab: SourcesTab;
  onSwitch: (tab: SourcesTab) => void;
}

/** 双 Tab 切换（URL ?tab= 态驱动；默认 info）。 */
function SourcesTabs({ tab, onSwitch }: SourcesTabsProps) {
  return (
    <div role="tablist" aria-label="源管理分区" data-testid="sources-tabs" className="flex items-center gap-1">
      {(Object.keys(TAB_META) as SourcesTab[]).map((key) => (
        <Button
          key={key}
          role="tab"
          size="sm"
          variant={key === tab ? 'default' : 'outline'}
          aria-selected={key === tab}
          onClick={() => onSwitch(key)}
          data-testid={TAB_META[key].testId}
        >
          {TAB_META[key].label}
        </Button>
      ))}
    </div>
  );
}

interface SourcesPageProps {
  /**
   * 当前规范路由（App 归一层后的 /sources?...，Tab 态解析源）。
   * 可缺省：直挂场景（单测/未来嵌入）以 window hash 为准并自听 hashchange。
   */
  route?: string;
}

/** hash 路由直读 + hashchange 自订阅（route prop 缺省时的 Tab 态驱动源）。 */
function useLiveRoute(): string {
  const [route, setRoute] = useState(() => currentRoute());
  useEffect(() => {
    const onChange = () => setRoute(currentRoute());
    window.addEventListener('hashchange', onChange);
    return () => window.removeEventListener('hashchange', onChange);
  }, []);
  return route;
}

/**
 * 源管理页（V2.3-M23 T204，全站第 19 页形态：20→19 归一）。
 * - Tab：`?tab=info|biz` URL 态（默认 info）；切换走 navigate 改 hash（其余 query 原样保留——
 *   `?source=` 大盘定位参数跨 Tab 切换不丢失，回 info Tab 面板重挂载即重新定位）。
 * - 统一搜索：单输入框过滤当前 Tab 卡片（不区分大小写，按源名/代码包含）；跨 Tab 命中以轻提示呈现
 *   （REQ 场景 2 形态裁量：概览数据就地计数，零额外请求）。
 * - 概览条：两列表端点前端聚合，任一失败静默降级（面板自身三态不受影响）。
 */
export function Sources({ route: routeProp }: SourcesPageProps) {
  const liveRoute = useLiveRoute();
  const route = routeProp ?? liveRoute;
  const tab = sourcesTabOf(route);
  const [search, setSearch] = useState('');
  const [infoView, setInfoView] = useState<InfoSourcesView | null>(null);
  const [bizView, setBizView] = useState<DataSourceConfigView | null>(null);

  // 概览条数据（一次性拉取；失败段静默降级为 —，不进错误态——面板有各自的加载/重试）
  useEffect(() => {
    const ctrl = new AbortController();
    getInfoSources(ctrl.signal).then(setInfoView).catch(() => undefined);
    return () => ctrl.abort();
  }, []);

  useEffect(() => {
    const ctrl = new AbortController();
    getDatasourceConfigs(ctrl.signal).then(setBizView).catch(() => undefined);
    return () => ctrl.abort();
  }, []);

  const switchTab = (next: SourcesTab) => {
    if (next === tab) return;
    // 仅改 tab 参数，其余 query（?source= 定位等）原样保留
    const params = queryOf(route);
    params.set('tab', next);
    navigate(`/sources?${params.toString()}`);
  };

  const infoStat = useMemo(() => (infoView ? infoStatOf(infoView) : null), [infoView]);
  const bizStat = useMemo(() => (bizView ? bizStatOf(bizView) : null), [bizView]);

  // 跨 Tab 命中提示：搜索非空时，用概览数据就地数另一 Tab 的命中数（零额外请求）
  const keyword = search.trim();
  const crossTabHint = useMemo(() => {
    if (!keyword) return null;
    if (tab === 'info') {
      if (!bizView) return null;
      const hits = bizView.sources.filter((source) =>
        sourceMatches(keyword, source.label, source.sourceCode),
      ).length;
      return hits > 0 ? `「${TAB_META.biz.label}」命中 ${hits} 个源` : null;
    }
    if (!infoView) return null;
    const hits = infoView.groups
      .flatMap((group) => group.sources)
      .filter((source) => sourceMatches(keyword, source.name, source.sourceCode)).length;
    return hits > 0 ? `「${TAB_META.info.label}」命中 ${hits} 个源` : null;
  }, [keyword, tab, bizView, infoView]);

  return (
    <main className="mx-auto w-full max-w-6xl p-4 sm:p-6" data-testid="sources-page">
      <header className="mb-4 flex flex-col gap-3">
        <div className="flex flex-wrap items-center gap-3">
          <h1 className="text-xl font-medium">源管理</h1>
          <div className="flex w-full items-center gap-2 sm:w-auto">
            <Input
              value={search}
              onChange={(e) => setSearch(e.target.value)}
              placeholder="搜索源名称 / 源代码（过滤当前分区）"
              aria-label="搜索源名称或源代码"
              className="w-full sm:w-64"
              data-testid="sources-search"
            />
            <Search aria-hidden="true" className="size-4 shrink-0 text-muted-foreground" />
          </div>
        </div>
        <p className="text-sm text-muted-foreground">
          全站源体系一页管理：资讯源 7×24 分钟级轮询采集 · 业务数据源按需拉取——启停 / 参数 / 健康 / 归档统一入口
        </p>
        <div className="flex flex-wrap items-center gap-3">
          <SourcesTabs tab={tab} onSwitch={switchTab} />
          <div className="min-w-0 flex-1">
            <OverviewBar info={infoStat} biz={bizStat} />
          </div>
        </div>
        {crossTabHint ? (
          <p className="text-xs text-muted-foreground" data-testid="sources-cross-tab-hint">
            {crossTabHint}
          </p>
        ) : null}
      </header>

      {tab === 'info' ? (
        <InfoSourcesPanel filter={search} />
      ) : (
        <BizSourcesPanel filter={search} />
      )}
    </main>
  );
}

export default Sources;
