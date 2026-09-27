// 轻量 hash 路由导航与路由解析（不引入 react-router，避免新依赖与离线构建风险）。
// 页面增多后可平滑替换为 react-router；navigate 只改 window.location.hash，
// App 的 useHashRoute 监听 hashchange 重渲染。

/** 默认标的（标的详情路由无参时的回退，UI 方案 §6.3 默认值先行）。 */
export const DEFAULT_SUBJECT_CODE = 'SH600519';

/** 最近浏览标的的 localStorage 键（侧栏「标的详情」入口指向）。 */
const LAST_SUBJECT_KEY = 'last_viewed_subject';

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

/** 解析 hash 路由中的查询串（'/job-logs?jobName=x' → jobName=x；无查询串返回空参数集）。 */
export function queryOf(route: string): URLSearchParams {
  const queryIndex = route.indexOf('?');
  return new URLSearchParams(queryIndex >= 0 ? route.slice(queryIndex + 1) : '');
}

/**
 * 旧源页路由归一（V2.3-M23 T204 → V2.4 T212 去 Tab 修订，REQ-20260928-20 拍板一路由兼容红线）：
 * - /datasource-config（含 query 透传）→ /sources?section=biz（业务数据源段定位）
 * - /info-sources → /sources（?source= 等定位参数原样透传，面板定位逻辑不变）
 * - /sources 自身：?tab=biz → ?section=biz（段定位参数）；?tab=info 静默归一（删除）
 * 归一层全局兜底（前缀匹配）：书签/深链等未枚举散点自动被捕获；非旧路由原样返回。
 */
export function canonicalizeRoute(route: string): string {
  const queryIndex = route.indexOf('?');
  const path = queryIndex >= 0 ? route.slice(0, queryIndex) : route;
  const query = queryOf(route);
  if (path === '/datasource-config' || path.startsWith('/datasource-config/')) {
    // 旧页语义 = 业务数据源配置：无 tab / tab=biz 落业务段定位；tab=info/非法值回默认（无段定位）
    const tab = query.get('tab');
    normalizeSourcesTab(query);
    if ((!tab || tab === 'biz') && !query.get('section')) {
      query.set('section', 'biz');
    }
    const queryText = query.toString();
    return `/sources${queryText ? `?${queryText}` : ''}`;
  }
  if (path === '/info-sources' || path.startsWith('/info-sources/')) {
    normalizeSourcesTab(query);
    const queryText = query.toString();
    return `/sources${queryText ? `?${queryText}` : ''}`;
  }
  if (path === '/sources' || path.startsWith('/sources/')) {
    if (!normalizeSourcesTab(query)) {
      return route; // 无 tab 参数：原样返回（零改写零误伤）
    }
    const queryText = query.toString();
    return `/sources${queryText ? `?${queryText}` : ''}`;
  }
  return route;
}

/**
 * ?tab= 参数退役归一（V2.4 T212）：tab=biz → section=biz 段定位；tab=info/非法值静默删除。
 *
 * @returns 是否发生变更（无 tab 参数返回 false，路由保持原样）
 */
function normalizeSourcesTab(query: URLSearchParams): boolean {
  const tab = query.get('tab');
  if (!tab) return false;
  query.delete('tab');
  if (tab === 'biz' && !query.get('section')) {
    query.set('section', 'biz');
  }
  return true;
}

/**
 * 标的详情路由的标的代码（T38 参数化）：
 * '/subjects/:code' 或 '/subjects?code=xxx' 优先；无参回退最近浏览标的，
 * 无历史再回默认标的 SH600519（UI 方案 §6.3 默认值先行——侧栏「标的详情」入口指向最近浏览）。
 */
export function parseSubjectCode(route: string): string {
  const path = route.split('?')[0] ?? route;
  const rest = path.replace(/^\/subjects/, '');
  const segment = rest.startsWith('/') ? rest.slice(1) : '';
  if (segment) return decodeURIComponent(segment);
  const fromQuery = queryOf(route).get('code');
  if (fromQuery) return fromQuery;
  return lastViewedSubject();
}

/** 最近浏览标的（侧栏「标的详情」入口用）；无历史或 localStorage 不可用回退默认标的。 */
export function lastViewedSubject(): string {
  try {
    return localStorage.getItem(LAST_SUBJECT_KEY) || DEFAULT_SUBJECT_CODE;
  } catch {
    return DEFAULT_SUBJECT_CODE;
  }
}

/** 记录最近浏览标的（标的详情页挂载时调用；隐私模式静默忽略）。 */
export function rememberSubject(code: string): void {
  if (!code) return;
  try {
    localStorage.setItem(LAST_SUBJECT_KEY, code);
  } catch {
    // 隐私模式 / SSR：静默忽略，仅退化侧栏入口回默认标的
  }
}
