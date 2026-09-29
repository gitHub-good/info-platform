import { useEffect, useState } from 'react';
import { AiBrief } from '@/pages/AiBrief';
import { JobLog } from '@/pages/JobLog';
import { LlmConfig } from '@/pages/LlmConfig';
import { LlmCostReport } from '@/pages/LlmCostReport';
import { Login } from '@/pages/Login';
import { Overview } from '@/pages/Overview';
import { PromptTemplates } from '@/pages/PromptTemplates';
import { SubjectDetail } from '@/pages/SubjectDetail';
import { TaskCenter } from '@/pages/TaskCenter';
import { Subscriptions } from '@/pages/Subscriptions';
import { Watchlist } from '@/pages/Watchlist';
import { Feed } from '@/pages/Feed';
import { FeedDashboard } from '@/pages/FeedDashboard';
import { IndustryHeat } from '@/pages/IndustryHeat';
import { IndustryMainline } from '@/pages/IndustryMainline';
import { Events } from '@/pages/Events';
import { Recommendations } from '@/pages/Recommendations';
import { MarketTop } from '@/pages/MarketTop';
import { NewsLibrary } from '@/pages/NewsLibrary';
import { NewsPulse } from '@/pages/NewsPulse';
import { Sources } from '@/pages/Sources';
import { AppLayout } from '@/components/layout/AppLayout';
import { NotificationProvider } from '@/components/notifications/NotificationProvider';
import { getToken } from '@/api/http';
import { canonicalizeRoute, navigate, parseSubjectCode, queryOf } from '@/lib/navigation';

/**
 * 轻量 hash 路由（#/overview · #/watchlists · #/subjects/:code · #/job-logs?jobName= …）。
 * 不引入 react-router 以避免新依赖与离线构建风险；页面增多后可平滑替换。
 * 登录守卫（UI 方案 §2.3）：无 token 一律渲染登录页（全屏、无 AppLayout），登录成功回原目标路由；
 * 已登录空 hash / 访问 /login 落默认页 #/overview（旧 hash 继续有效直接渲染）。
 */
function useHashRoute(): string {
  const [hash, setHash] = useState(() =>
    typeof window === 'undefined' ? '' : window.location.hash,
  );
  useEffect(() => {
    const onChange = () => setHash(window.location.hash);
    window.addEventListener('hashchange', onChange);
    return () => window.removeEventListener('hashchange', onChange);
  }, []);
  return hash;
}

/** 按路由渲染页面内容（各页保留自带 main+max-w，AppLayout 只提供导航骨架）。 */function renderPage(route: string) {
  if (route.startsWith('/watchlists')) {
    return <Watchlist />;
  }
  if (route.startsWith('/ai-brief')) {
    // AiBrief 自带 main+max-w-4xl（T44 去掉此处外层嵌套 main：同页双 main 属无效结构）
    return <AiBrief />;
  }
  // 行业热度与日报（M15 T126）：第 16 页，「分析」组——热度榜/日报双 Tab（自带 main+max-w-4xl）
  if (route.startsWith('/industry-heat')) {
    return <IndustryHeat />;
  }
  // 行业主线（M27 T245）：第 18 页，「分析」组——热力图 + 主线榜单 + 龙头（自带 main+max-w-5xl；
  // 热力图与榜单两区块独立三态互不拖垮，重算/配置入口在页头）
  if (route.startsWith('/industry-mainline')) {
    return <IndustryMainline />;
  }
  // 事件流（M15 T127）：第 17 页，「分析」组——L2 结构化事件卡片流（自带 main+max-w-4xl）
  if (route.startsWith('/events')) {
    return <Events />;
  }
  // 推荐中心（M16 T135）：第 18 页，「分析」组——动态推荐卡片流 + 反馈闭环（自带 main+max-w-4xl；
  // ?focus={cardId} 为 SSE 铃铛跳转落地的定位参数，页面内消费）
  if (route.startsWith('/recommendations')) {
    return <Recommendations />;
  }
  // 全市场推荐（M21 T184）：第 20 页，「分析」组——四层漏斗 Top10 榜单（自带 main+max-w-4xl；
  // /market-top/methodology 子路由由页内 hash 切换（导航仍 1 项，ADR-0059 裁决 7））
  if (route.startsWith('/market-top')) {
    return <MarketTop />;
  }
  // 资讯库（M19 T161）：「数据」组——news_item 原始库全量列表（自带 main+max-w-4xl）；
  // V2.4 T213 增 URL 预填：effectiveRoute 随归一层透传（#/policies 重定向 L1=监管·政策 即达）
  if (route.startsWith('/news-pulse')) {
    return <NewsPulse />;
  }
  if (route.startsWith('/news-library')) {
    return <NewsLibrary route={route} />;
  }
  if (route.startsWith('/job-logs')) {
    // URL 参数初始化预过滤（#/job-logs?jobName=xxx，T41 任务中心「历史」跳转用）；
    // key 随参数变化强制重挂载，切换过滤即重新拉首页
    const jobName = queryOf(route).get('jobName') ?? '';
    return <JobLog key={jobName} initialJobName={jobName} />;
  }
  if (route.startsWith('/cost-report')) {
    return <LlmCostReport />;
  }
  if (route.startsWith('/subjects')) {
    // 路由参数化（T38）：#/subjects/:code 或 ?code=xxx，无参回退默认标的；
    // key 随标的代码变化强制重挂载（M12 UI 设计 D8，JobLog key 先例）：
    // 各分区页码/新闻探页计数/停止标志随组件树重建归零，无逐项枚举重置的遗漏面
    const subjectCode = parseSubjectCode(route);
    return (
      <main className="mx-auto w-full max-w-6xl p-4 sm:p-6">
        <SubjectDetail key={subjectCode} subjectId={subjectCode} />
      </main>
    );
  }
  // 新增 5 页均已实现：#/overview（T42）、#/llm-config（T39）、#/sources（T40 归一）、#/task-center（T41）、#/feed（T43）
  if (route.startsWith('/overview')) {
    return <Overview />;
  }
  if (route.startsWith('/llm-config')) {
    return <LlmConfig />;
  }
  // 源管理（V2.4 T212 单列表分组）：资讯源六分组在前 + 业务数据源段收尾（?section=biz 段定位）；
  // 旧 #/datasource-config / #/info-sources / ?tab= 由 canonicalizeRoute 归一层重定向归一
  if (route.startsWith('/sources')) {
    return <Sources route={route} />;
  }
  if (route.startsWith('/task-center')) {
    return <TaskCenter />;
  }
  // 抓取大盘（M14 T116）：运维只读监控页，与源管理页「发现异常 → 处置」单向闭环（拍板二）
  if (route.startsWith('/feed-dashboard')) {
    return <FeedDashboard />;
  }
  if (route.startsWith('/feed')) {
    return <Feed />;
  }
  // 订阅管理（体检 P1-3）：信息流的数据源头，个性化闭环起点
  if (route.startsWith('/subscriptions')) {
    return <Subscriptions />;
  }
  // 提示词模板（M5 T47）：单路由两视图（列表 ⇄ 编辑器 state 切换，hash 不变，UI 方案 D4）
  if (route.startsWith('/prompt-templates')) {
    return <PromptTemplates />;
  }
  return null;
}

export default function App() {
  const hash = useHashRoute();
  // 仅驱动登录成功后的原地重渲染（登录守卫在目标 hash 渲染登录页时不发生 hashchange）
  const [, setAuthEpoch] = useState(0);
  const route = hash.replace(/^#/, '');
  const token = getToken();

  // 已登录的空 hash / 显式 /login → 默认落地 #/overview（旧 hash 继续有效）
  useEffect(() => {
    if (token && (route === '' || route === '/login')) {
      navigate('/overview');
    }
  }, [route, token]);

  const fallbackRoute = route === '' || route === '/login' ? '/overview' : route;
  // 旧源页路由归一（T204）：渲染按规范路由，地址栏 replaceState 静默改写
  // （不触发 hashchange、不新增历史项——回退键不被重定向劫持，无死循环）。
  // 注意：此 effect 必须位于登录守卫早返回之前（hooks 无条件调用）
  const effectiveRoute = canonicalizeRoute(fallbackRoute);
  useEffect(() => {
    if (token && effectiveRoute !== fallbackRoute) {
      window.history.replaceState(null, '', `#${effectiveRoute}`);
    }
  }, [fallbackRoute, effectiveRoute, token]);

  // 登录守卫：无 token 一律渲染登录页（全屏、无 AppLayout），登录成功回原目标路由
  if (!token) {
    const target = route !== '' && route !== '/login' ? route : '/overview';
    return <Login redirectTo={target} onAuthenticated={() => setAuthEpoch((e) => e + 1)} />;
  }

  // 登录态挂载通知中心（P1-1）：SSE 长连接随 Provider 建立，登出（token 清除）即卸载断开
  return (
    <NotificationProvider>
      <AppLayout currentRoute={effectiveRoute}>{renderPage(effectiveRoute)}</AppLayout>
    </NotificationProvider>
  );
}
