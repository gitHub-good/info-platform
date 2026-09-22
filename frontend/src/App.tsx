import { useEffect, useState } from 'react';
import { AiBrief } from '@/pages/AiBrief';
import { JobLog } from '@/pages/JobLog';
import { LlmConfig } from '@/pages/LlmConfig';
import { LlmCostReport } from '@/pages/LlmCostReport';
import { Login } from '@/pages/Login';
import { PlaceholderPage } from '@/pages/PlaceholderPage';
import { Policy } from '@/pages/Policy';
import { SubjectDetail } from '@/pages/SubjectDetail';
import { Watchlist } from '@/pages/Watchlist';
import { DatasourceConfig } from '@/pages/DatasourceConfig';
import { AppLayout } from '@/components/layout/AppLayout';
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
    return (
      <main className="mx-auto w-full max-w-4xl p-4 sm:p-6">
        <AiBrief />
      </main>
    );
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
    // 路由参数化（T38）：#/subjects/:code 或 ?code=xxx，无参回退默认标的
    return (
      <main className="mx-auto w-full max-w-6xl p-4 sm:p-6">
        <SubjectDetail subjectId={parseSubjectCode(route)} />
      </main>
    );
  }
  // 新增 5 页：#/llm-config（T39）与 #/datasource-config（T40）已实现，其余占位（「开发中」），T41~T43 逐页填充
  if (route.startsWith('/overview')) {
    return (
      <PlaceholderPage
        title="概览"
        description="一屏看清平台健康度与今日动态"
        skeletonCards={5}
        testId="overview-placeholder"
      />
    );
  }
  if (route.startsWith('/llm-config')) {
    return <LlmConfig />;
  }
  if (route.startsWith('/datasource-config')) {
    return <DatasourceConfig />;
  }
  if (route.startsWith('/task-center')) {
    return (
      <PlaceholderPage
        title="任务执行中心"
        description="定时任务可手动触发、实时看状态；执行明细见 Job 日志"
        testId="task-center-placeholder"
      />
    );
  }
  if (route.startsWith('/feed')) {
    return (
      <PlaceholderPage
        title="个人信息流"
        description="按时间倒序 · 命中你的主题 / 标的 / 事件类型订阅"
        testId="feed-placeholder"
      />
    );
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

  return <AppLayout currentRoute={effectiveRoute}>{renderPage(effectiveRoute)}</AppLayout>;
}
