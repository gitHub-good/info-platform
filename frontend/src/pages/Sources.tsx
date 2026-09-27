import { useEffect, useMemo, useRef, useState } from 'react';
import { Search } from 'lucide-react';
import { getDatasourceConfigs } from '@/api/datasourceConfig';
import { getInfoSources } from '@/api/infoSource';
import { BizSourcesPanel } from '@/components/sources/BizSourcesPanel';
import { InfoSourcesPanel, type InfoSourcesPanelHandle } from '@/components/sources/InfoSourcesPanel';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { currentRoute, queryOf } from '@/lib/navigation';
import type { DataSourceConfigView } from '@/types/datasourceConfig';
import type { InfoSourcesView } from '@/types/infoSource';

// 源管理页（V2.3-M23 T204 双 Tab → V2.4 T212 去 Tab 单列表分组，REQ-20260928-20 拍板一）：
// 一屏之内分段分组——资讯源（服务端 category 六分组：快讯/媒体/政策/宏观/国际/自建）在前 +
// 「业务数据源」一段收尾（低频配置域置底）；两面板组件（卡片及交互）原样复用不动，仅外层容器
// 从 Tab 切换改为纵向分段渲染（V2.3 拍板五「卡片范式硬统一重构」否定继续成立）。
// 新增源入口页头全局唯一（沿资讯源 SourceFormDialog；业务源为代码注册域不可自增）；
// 统一搜索跨全部分段；概览条口径不变（两列表端点前端聚合，失败段静默降级为 —）。
// 路由兼容：?tab=biz → ?section=biz（段定位参数，归一层映射）；?tab=info 静默归一；
// 旧 #/datasource-config → /sources?section=biz、#/info-sources → /sources（query 透传）；
// ?source= 定位高亮语义保留（大盘失败跳转零断链）。

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

interface SourcesPageProps {
  /**
   * 当前规范路由（App 归一层后的 /sources?...，段定位参数解析源）。
   * 可缺省：直挂场景（单测/未来嵌入）以 window hash 为准并自听 hashchange。
   */
  route?: string;
}

/** hash 路由直读 + hashchange 自订阅（route prop 缺省时的驱动源）。 */
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
 * 源管理页（V2.4 T212 单列表分段分组形态）。
 * - 分段：资讯源六分组在前 + 业务数据源段收尾（?section=biz 进入时滚动定位业务段，一次性消费）。
 * - 统一搜索：单输入框同步过滤全部分段卡片（不区分大小写，按源名/代码包含；Tab 消亡，跨 Tab 提示机制退役）。
 * - 新增源：页头「＋新增源」全局唯一（经面板命令句柄打开既有 SourceFormDialog）。
 * - 概览条：两列表端点前端聚合，任一失败静默降级（面板自身三态不受影响）。
 */
export function Sources({ route: routeProp }: SourcesPageProps) {
  const liveRoute = useLiveRoute();
  const route = routeProp ?? liveRoute;
  const [search, setSearch] = useState('');
  const [infoView, setInfoView] = useState<InfoSourcesView | null>(null);
  const [bizView, setBizView] = useState<DataSourceConfigView | null>(null);
  const infoPanelRef = useRef<InfoSourcesPanelHandle>(null);
  const bizSectionRef = useRef<HTMLElement | null>(null);
  // ?section=biz 段定位（T212：?tab=biz 归一后的落点）：挂载一次性消费（沿 ?source= 定位先例）
  const focusSectionRef = useRef<string | null>(queryOf(route).get('section'));

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

  // 业务段定位：首帧渲染后滚动可见（jsdom 无 scrollIntoView 时跳过，不干扰渲染）
  useEffect(() => {
    if (focusSectionRef.current !== 'biz') return;
    focusSectionRef.current = null;
    const timer = window.setTimeout(() => {
      bizSectionRef.current?.scrollIntoView?.({ behavior: 'smooth', block: 'start' });
    }, 0);
    return () => window.clearTimeout(timer);
  }, []);

  const infoStat = useMemo(() => (infoView ? infoStatOf(infoView) : null), [infoView]);
  const bizStat = useMemo(() => (bizView ? bizStatOf(bizView) : null), [bizView]);

  return (
    <main className="mx-auto w-full max-w-6xl p-4 sm:p-6" data-testid="sources-page">
      <header className="mb-4 flex flex-col gap-3">
        <div className="flex flex-wrap items-center gap-3">
          <h1 className="text-xl font-medium">源管理</h1>
          <div className="flex w-full items-center gap-2 sm:w-auto">
            <Input
              value={search}
              onChange={(e) => setSearch(e.target.value)}
              placeholder="搜索源名称 / 源代码（过滤全部分段）"
              aria-label="搜索源名称或源代码"
              className="w-full sm:w-64"
              data-testid="sources-search"
            />
            <Search aria-hidden="true" className="size-4 shrink-0 text-muted-foreground" />
          </div>
          {/* V2.4 T212：新增源入口页头全局唯一——弹出既有资讯源 SourceFormDialog（业务源代码注册域不可自增） */}
          <Button
            size="sm"
            className="ml-auto"
            onClick={() => infoPanelRef.current?.openAdd()}
            data-testid="sources-add"
          >
            ＋新增源
          </Button>
        </div>
        <p className="text-sm text-muted-foreground">
          全站源体系一页管理：资讯源按分组（快讯 / 媒体 / 政策 / 宏观 / 国际 / 自建）7×24
          分钟级轮询采集在前 · 业务数据源按需拉取一段收尾——启停 / 参数 / 健康 / 归档统一入口
        </p>
        <div className="flex flex-wrap items-center gap-3">
          <div className="min-w-0 flex-1">
            <OverviewBar info={infoStat} biz={bizStat} />
          </div>
        </div>
      </header>

      <div className="flex flex-col gap-8">
        <InfoSourcesPanel ref={infoPanelRef} filter={search} />
        <section ref={bizSectionRef} data-testid="sources-biz-section" aria-label="业务数据源分段">
          <BizSourcesPanel filter={search} />
        </section>
      </div>
    </main>
  );
}

export default Sources;
