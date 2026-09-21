import { useEffect, useState } from 'react';
import { Login } from '@/pages/Login';
import { SubjectDetail } from '@/pages/SubjectDetail';
import { Watchlist } from '@/pages/Watchlist';
import { getToken } from '@/api/http';
import { navigate } from '@/lib/navigation';

/**
 * 轻量 hash 路由（#/login · #/watchlists · #/subjects）。
 * 不引入 react-router 以避免新依赖与离线构建风险；页面增多后可平滑替换。
 * 登录态感知：无 token 默认进登录页，有 token 默认进 watchlist 页。
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

export default function App() {
  const hash = useHashRoute();
  const route = hash.replace(/^#/, '');

  // 空 hash：按登录态落到默认页
  useEffect(() => {
    if (route === '') {
      navigate(getToken() ? '/watchlists' : '/login');
    }
  }, [route]);

  if (route.startsWith('/watchlists')) {
    return <Watchlist />;
  }
  if (route.startsWith('/subjects')) {
    return (
      <main className="mx-auto w-full max-w-6xl p-4 sm:p-6">
        <SubjectDetail subjectId="SH600519" />
      </main>
    );
  }
  // 默认（含 /login 与未知路由）渲染登录页
  return <Login />;
}
