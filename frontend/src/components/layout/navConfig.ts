import {
  Bot,
  Bookmark,
  Coins,
  Database,
  FileText,
  Flame,
  LayoutDashboard,
  Library,
  LineChart,
  MessageSquareText,
  Newspaper,
  PlayCircle,
  Rss,
  ScrollText,
  Sparkles,
  Star,
  Trophy,
  Gauge,
  Zap,
  type LucideIcon,
} from 'lucide-react';

/** 导航项（纯静态配置渲染，不依赖任何业务接口——UI 方案 §2.3 导航健壮性）。 */
export interface NavItem {
  /** 路由前缀（active 判定与 data-testid 后缀）。 */
  to: string;
  /** 展示文案。 */
  label: string;
  icon: LucideIcon;
}

export interface NavGroup {
  label: string;
  items: NavItem[];
}

/** 4 分组 19 页导航总表 + 底部登出（UI 方案 §2.1；V2.3-M23 T204 源两页合一「源管理」后 20→19 页；抓取大盘 M14、行业热度/事件流 M15、资讯库 M19、全市场推荐 M21）。 */
export const NAV_GROUPS: NavGroup[] = [
  {
    label: '总览',
    items: [{ to: '/overview', label: '概览', icon: LayoutDashboard }],
  },
  {
    label: '数据',
    items: [
      { to: '/watchlists', label: '自选清单', icon: Star },
      // 标的详情入口不带参：无参路由回退最近浏览标的，无历史落默认标的（UI 方案 §6.3）
      { to: '/subjects', label: '标的详情', icon: LineChart },
      { to: '/policies', label: '政策时事', icon: Newspaper },
      // 资讯库（M19 T161，数据组第 4 项 / 全站第 19 页）：news_item 原始库全量列表——
      // 与「信息流」（分析组，订阅过滤后的个人化消费流）形成 库 → 流 两级心智（REQ 拍板一）
      { to: '/news-library', label: '资讯库', icon: Library },
    ],
  },
  {
    label: '分析',
    items: [
      { to: '/ai-brief', label: 'AI 简报', icon: FileText },
      // 行业热度与日报（M15 T126，分析组第 4 项 / 全站第 16 页）：AI 分析产出，紧邻 AI 简报
      { to: '/industry-heat', label: '行业热度', icon: Flame },
      // 事件流（M15 T127，分析组第 5 项 / 全站第 17 页）：L2 结构化事件卡片流，插「行业热度」后
      { to: '/events', label: '事件流', icon: Zap },
      // 推荐中心（M16 T135，分析组第 6 项 / 全站第 18 页）：动态推荐卡片流 + 反馈闭环，紧邻事件流
      { to: '/recommendations', label: '推荐中心', icon: Sparkles },
      // 全市场推荐（M21 T184，全站第 20 页「分析」组）：四层漏斗 Top10 榜单 + 方法论子路由，插「推荐中心」后（§4.8.1）
      { to: '/market-top', label: '全市场推荐', icon: Trophy },
      // 订阅管理（体检 P1-3）：信息流的数据源头，排在信息流之前
      { to: '/subscriptions', label: '订阅管理', icon: Bookmark },
      { to: '/feed', label: '信息流', icon: Rss },
    ],
  },
  {
    label: '运维',
    items: [
      { to: '/task-center', label: '任务中心', icon: PlayCircle },
      { to: '/job-logs', label: 'Job 日志', icon: ScrollText },
      { to: '/cost-report', label: '成本报表', icon: Coins },
      { to: '/llm-config', label: '模型配置', icon: Bot },
      // 源管理（V2.3-M23 T204，全站 20→19 页）：资讯源 + 业务数据源双 Tab 单页 #/sources
      // （?tab=info|biz；旧 #/datasource-config / #/info-sources 由 App 归一层 replace 重定向）
      { to: '/sources', label: '源管理', icon: Database },
      // 抓取大盘（M14 T116 运维组）：紧邻源管理，同属源运行域（REQ 拍板二：独立只读监控页）
      { to: '/feed-dashboard', label: '抓取大盘', icon: Gauge },
      // 运维组第 7 项（全站第 15 页，M5 T47）：紧邻模型/业务数据源，同属「改 AI 产出」入口（UI 方案 D1）
      { to: '/prompt-templates', label: '提示词模板', icon: MessageSquareText },
    ],
  },
];

/** 当前路由是否命中导航项（精确 / 子路径 / 查询串三种形态）。 */
export function routeMatches(route: string, to: string): boolean {
  return route === to || route.startsWith(`${to}/`) || route.startsWith(`${to}?`);
}

/** 当前页标题（窄屏顶栏展示；未匹配返回空串）。 */
export function titleForRoute(route: string): string {
  for (const group of NAV_GROUPS) {
    const hit = group.items.find((item) => routeMatches(route, item.to));
    if (hit) return hit.label;
  }
  return '';
}
