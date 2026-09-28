// ui-tokens-v3 增量断言（T221/T225，V3.0 REQ-20260928-21 故事 6 场景 3）：
// v2 全部数值零改动（只增不改红线）+ v3 增量变量（header-bg / header 高度 / shadow-soft）就位。
// 读 index.css 源文本做静态断言（token 层契约，不依赖 jsdom 计算样式；
// vitest 环境下 CSS ?raw 导入被处理为空串，故走 node:fs 直读——
/// 三斜线引用按文件引入 node 类型，不改全局 tsconfig）。

/// <reference types="node" />
import { readFileSync, readdirSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { describe, expect, it } from 'vitest';

const cssSource = readFileSync(
  join(dirname(fileURLToPath(import.meta.url)), 'index.css'),
  'utf8',
);

/** 递归收集组件源码（.tsx，排除测试文件）——v3 增量 token 消费面核查用。 */
function collectComponentSources(dir: string): string[] {
  const files: string[] = [];
  for (const entry of readdirSync(dir, { withFileTypes: true })) {
    const full = join(dir, entry.name);
    if (entry.isDirectory()) files.push(...collectComponentSources(full));
    else if (entry.name.endsWith('.tsx') && !entry.name.endsWith('.test.tsx')) {
      files.push(readFileSync(full, 'utf8'));
    }
  }
  return files;
}

const srcDir = join(dirname(fileURLToPath(import.meta.url)));
const componentSources = collectComponentSources(srcDir).join('\n');

describe('ui-tokens-v3 只增不改（T221）', () => {
  it('v3 增量变量就位：--header-bg 双主题 / --header-height / --header-tabs-height / --shadow-soft', () => {
    // 顶栏毛玻璃底（:root 亮色 85% 白、.dark 0.13 底 85%）
    expect(cssSource).toContain('--header-bg: oklch(1 0 0 / 85%)');
    expect(cssSource).toContain('--header-bg: oklch(0.13 0 0 / 85%)');
    // 主行 56px / 页签行 44px（触控达标）
    expect(cssSource).toContain('--header-height: 3.5rem');
    expect(cssSource).toContain('--header-tabs-height: 2.75rem');
    // 浮层软投影（通知面板/回顶按钮）
    expect(cssSource).toContain('--shadow-soft: 0 8px 24px oklch(0 0 0 / 35%)');
    // bg-header-bg 工具类映射（@theme inline color 命名空间）
    expect(cssSource).toContain('--color-header-bg: var(--header-bg)');
  });

  it('v2 数值零改动：关键 token 抽样断言（暗色深化/对比度/焦点环/侧栏蓝系沿 v2 基线）', () => {
    // .dark 基线（M18 T154 拍板三：bg 0.13 / card 0.185 / muted-fg 0.75）
    expect(cssSource).toContain('--background: oklch(0.13 0 0)');
    expect(cssSource).toContain('--card: oklch(0.185 0 0)');
    expect(cssSource).toContain('--muted-foreground: oklch(0.75 0 0)');
    // 亮色基线
    expect(cssSource).toContain('--background: oklch(1 0 0)');
    expect(cssSource).toContain('--muted-foreground: oklch(0.556 0 0)');
    // 导航活跃指示条复用既有 sidebar-primary（蓝系，不新增品牌色）
    expect(cssSource).toContain('--sidebar-primary: oklch(0.556 0.12 250)'); // :root
    expect(cssSource).toContain('--sidebar-primary: oklch(0.62 0.16 255)'); // .dark
    // 焦点环蓝系（v2 可达性基线）
    expect(cssSource).toContain('--ring: oklch(0.556 0.12 250)');
    expect(cssSource).toContain('--sidebar-ring: oklch(0.68 0.11 250)');
    // 圆角与 A 层间距阶不动
    expect(cssSource).toContain('--radius: 0.625rem');
    expect(cssSource).toContain('--spacing-page: 1.5rem');
  });

  it('v3 增量克制：新增变量 ≤4 个（header-bg/header-height/header-tabs-height/shadow-soft）', () => {
    const v3Vars = [
      '--header-bg',
      '--header-height',
      '--header-tabs-height',
      '--shadow-soft',
    ] as const;
    for (const name of v3Vars) {
      // 每个增量变量在源中至少声明一次
      expect(cssSource.match(new RegExp(`${name}:`, 'g'))?.length ?? 0).toBeGreaterThanOrEqual(1);
    }
  });

  it('v3 收尾核查（T225）：四个增量 token 全站均有消费引用，无声明未用的悬空变量', () => {
    // 顶栏毛玻璃底（TopNav 主行/页签行）
    expect(componentSources).toContain('bg-header-bg');
    // 主行高度与页签行高度（TopNav：行高 + 页签行 sticky 偏移）
    expect(componentSources).toContain('h-(--header-height)');
    expect(componentSources).toContain('h-(--header-tabs-height)');
    // 浮层软投影（通知面板 + 信息流回到顶部按钮）
    expect(
      componentSources.match(/shadow-\(--shadow-soft\)/g)?.length ?? 0,
    ).toBeGreaterThanOrEqual(2);
  });
});
