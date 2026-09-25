import { useEffect, useState } from 'react';
import { AiBrief } from '@/pages/AiBrief';
import { JobLog } from '@/pages/JobLog';
import { LlmConfig } from '@/pages/LlmConfig';
import { LlmCostReport } from '@/pages/LlmCostReport';
import { Login } from '@/pages/Login';
import { Overview } from '@/pages/Overview';
import { Policy } from '@/pages/Policy';
import { PromptTemplates } from '@/pages/PromptTemplates';
import { SubjectDetail } from '@/pages/SubjectDetail';
import { TaskCenter } from '@/pages/TaskCenter';
import { Subscriptions } from '@/pages/Subscriptions';
import { Watchlist } from '@/pages/Watchlist';
import { DatasourceConfig } from '@/pages/DatasourceConfig';
import { Feed } from '@/pages/Feed';
import { InfoSources } from '@/pages/InfoSources';
import { AppLayout } from '@/components/layout/AppLayout';
import { NotificationProvider } from '@/components/notifications/NotificationProvider';
import { getToken } from '@/api/http';
import { navigate, parseSubjectCode, queryOf } from '@/lib/navigation';

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

/** 按路由渲染页面内容（各页保留自带 main+max-w，AppLayout 只提供导航骨架）。 */
function renderPage(route: string) {
  if (route.startsWith('/watchlists')) {
    return <Watchlist />;
  }
  if (route.startsWith('/ai-brief')) {
    // AiBrief 自带 main+max-w-4xl（T44 去掉此处外层嵌套 main：同页双 main 属无效结构）
    return <AiBrief />;
  }
  if (route.startsWith('/policies')) {
    return <Policy />;
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
  // 新增 5 页均已实现：#/overview（T42）、#/llm-config（T39）、#/datasource-config（T40）、#/task-center（T41）、#/feed（T43）
  if (route.startsWith('/overview')) {
    return <Overview />;
  }
  if (route.startsWith('/llm-config')) {
    return <LlmConfig />;
  }
  if (route.startsWith('/datasource-config')) {
    return <DatasourceConfig />;
  }
  // 资讯源管理（M13 T105）：源注册表 DB 化后的运营页，与数据源配置页边界冻结（ADR-0038）
  if (route.startsWith('/info-sources')) {
    return <InfoSources />;
  }
  if (route.startsWith('/task-center')) {
    return <TaskCenter />;
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

  // 登录守卫：无 token 一律渲染登录页（全屏、无 AppLayout），登录成功回原目标路由
  if (!token) {
    const target = route !== '' && route !== '/login' ? route : '/overview';
    return <Login redirectTo={target} onAuthenticated={() => setAuthEpoch((e) => e + 1)} />;
  }

  const effectiveRoute = route === '' || route === '/login' ? '/overview' : route;

  // 登录态挂载通知中心（P1-1）：SSE 长连接随 Provider 建立，登出（token 清除）即卸载断开
  return (
    <NotificationProvider>
      <AppLayout currentRoute={effectiveRoute}>{renderPage(effectiveRoute)}</AppLayout>
    </NotificationProvider>
  );
}
