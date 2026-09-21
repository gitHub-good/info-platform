// 轻量 hash 路由导航（不引入 react-router，避免新依赖与离线构建风险）。
// 页面增多后可平滑替换为 react-router；navigate 只改 window.location.hash，
// App 的 useHashRoute 监听 hashchange 重渲染。

/**
 * 跳转到指定路由（如 '/login'、'/watchlists'）。
 * 入参可带或不带前导 #；与当前 hash 相同时不触发重复赋值。
 */
export function navigate(to: string): void {
  if (typeof window === 'undefined') return;
  const target = to.startsWith('#') ? to : `#${to}`;
  if (window.location.hash !== target) {
    window.location.hash = target;
  }
}

/** 读取当前路由（去掉前导 #，空 hash 返回空串）。 */
export function currentRoute(): string {
  if (typeof window === 'undefined') return '';
  return window.location.hash.replace(/^#/, '');
}
