import {
  Bot,
  Coins,
  Database,
  FileText,
  LayoutDashboard,
  LineChart,
  MessageSquareText,
  Newspaper,
  PlayCircle,
  Rss,
  ScrollText,
  Star,
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

/** 4 分组 12 页导航总表 + 底部登出（UI 方案 §2.1；页面路由以 UI 方案为准）。 */
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
    ],
  },
  {
    label: '分析',
    items: [
      { to: '/ai-brief', label: 'AI 简报', icon: FileText },
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
      { to: '/datasource-config', label: '数据源配置', icon: Database },
      // 运维组第 6 项（全站第 13 页，M5 T47）：紧邻模型/数据源配置，同属「改 AI 产出」入口（UI 方案 D1）
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
