// 提示词模板前端口径纯函数（M5 T47/T48，UI 方案 §5.3）：
// 分段标记切分 / 占位符提取（与后端渲染正则同口径）/ 硬校验镜像（文案与后端 30067 逐条一致）/
// 版本号预览推算（D9，最终以后端生成为准）/ 移除与未知占位符 diff（基准 = 底稿版本）。
// 后端为权威校验，此处为体验级预检镜像（方案 §4.8 前端清单）；
// 纯函数置 lib（组件文件仅导出组件，沿 lib/highlight.ts 先例）。

import type { PromptPlaceholderItem } from '@/types/promptTemplate';

/** system 段标记（与后端 PromptSections.SYSTEM_MARKER 同字面）。 */
export const SYSTEM_MARKER = '---SYSTEM---';

/** user 段标记（与后端 PromptSections.USER_MARKER 同字面）。 */
export const USER_MARKER = '---USER---';

/** 占位符 {{key}}（键名 \w+，允许前后空白；单花括号 JSON 形状不参与——渲染同口径）。 */
const PLACEHOLDER_PATTERN = /\{\{\s*(\w+)\s*\}\}/g;

/** 模板长度上限（镜像后端校验器 V3 防御性上限）。 */
export const MAX_TEMPLATE_LENGTH = 65536;

/** 兜底起步版本（场景无任何合法版本行，镜像后端生成器 FIRST_VERSION）。 */
const FIRST_VERSION = 'v1.0';

/** 合法版本格式 v{major}.{minor}（两段式非负整数）。 */
const VERSION_PATTERN = /^v(\d+)\.(\d+)$/;

/** 提取占位符键名（按出现序去重；空文本返回空数组）。 */
export function extractPlaceholderKeys(text: string): string[] {
  if (!text) return [];
  const keys: string[] = [];
  for (const match of text.matchAll(PLACEHOLDER_PATTERN)) {
    const key = match[1];
    if (!keys.includes(key)) keys.push(key);
  }
  return keys;
}

/** 占位符键数（去重；版本卡「占位符 N 项」展示口径）。 */
export function countPlaceholderKeys(text: string): number {
  return extractPlaceholderKeys(text).length;
}

/** 切分 system/user 两段正文（不含标记行）；标记缺失或顺序错误返回 null（降级整段展示）。 */
export function splitSections(template: string): { system: string; user: string } | null {
  const sysIdx = template.indexOf(SYSTEM_MARKER);
  const userIdx = template.indexOf(USER_MARKER);
  if (sysIdx < 0 || userIdx <= sysIdx) return null;
  return {
    system: template.slice(sysIdx + SYSTEM_MARKER.length, userIdx),
    user: template.slice(userIdx + USER_MARKER.length),
  };
}

/** 硬校验（镜像后端 30067：空 / 超长 / 缺标记 / 顺序错 / system 段缺 json 字样，文案逐条对齐）。 */
export function hardErrorsOf(template: string): string[] {
  if (template.trim() === '') {
    return ['模板不能为空'];
  }
  const errors: string[] = [];
  if (template.length > MAX_TEMPLATE_LENGTH) {
    errors.push(`模板长度超过上限 ${MAX_TEMPLATE_LENGTH} 字符`);
  }
  const systemPresent = template.includes(SYSTEM_MARKER);
  const userPresent = template.includes(USER_MARKER);
  if (!systemPresent) errors.push(`缺少 ${SYSTEM_MARKER} 分段标记`);
  if (!userPresent) errors.push(`缺少 ${USER_MARKER} 分段标记`);
  if (systemPresent && userPresent) {
    const sections = splitSections(template);
    if (sections === null) {
      errors.push(`分段标记顺序错误: ${SYSTEM_MARKER} 须在 ${USER_MARKER} 之前`);
    } else if (!sections.system.toLowerCase().includes('json')) {
      errors.push('system 段须含 json 字样（JSON 输出模式前提）');
    }
  }
  return errors;
}

/**
 * 占位符 diff：removed = 底稿 − 新稿（携带注册表 description）；unknown = 新稿 − 注册表。
 * registry 为 null（注册表未加载/加载失败）时 unknown 判定降级为空——保存校验降级为
 * 「仅硬校验 + 后端兜底」（UI 方案 §3.3 交互 4）；底稿为空（无激活版异常态）removed 恒空。
 */
export function diffPlaceholders(
  nextTemplate: string,
  baseTemplate: string | null,
  registry: readonly PromptPlaceholderItem[] | null,
): { removed: PromptPlaceholderItem[]; unknown: PromptPlaceholderItem[] } {
  const registryKeys =
    registry === null ? null : new Set(registry.map((item) => item.key));
  const nextKeys = extractPlaceholderKeys(nextTemplate);
  const unknown: PromptPlaceholderItem[] =
    registryKeys === null
      ? []
      : nextKeys
          .filter((key) => !registryKeys.has(key))
          .map((key) => ({ key, description: null }));
  if (!baseTemplate) {
    return { removed: [], unknown };
  }
  const nextKeySet = new Set(nextKeys);
  const descriptionOf = (key: string) =>
    registry?.find((item) => item.key === key)?.description ?? null;
  const removed: PromptPlaceholderItem[] = extractPlaceholderKeys(baseTemplate)
    .filter((key) => !nextKeySet.has(key))
    .map((key) => ({ key, description: descriptionOf(key) }));
  return { removed, unknown };
}

/** 解析 v{major}.{minor}；非法格式（手工改库产物）返回 null，不参与版本序计算。 */
export function parseVersion(version: string): [number, number] | null {
  const match = VERSION_PATTERN.exec(version.trim());
  return match ? [Number(match[1]), Number(match[2])] : null;
}

/** 数值版本比较（升序；非法格式退化为字符串比较保证全序，镜像后端 compareNumeric）。 */
export function compareVersions(a: string, b: string): number {
  const left = parseVersion(a);
  const right = parseVersion(b);
  if (left && right) {
    return left[0] !== right[0] ? left[0] - right[0] : left[1] - right[1];
  }
  return a < b ? -1 : a > b ? 1 : 0;
}

/** 下一版本号预览（D9：按场景全部既有版本取数值 max 推算；MINOR 次版本 +1 / MAJOR 主版本归零）。 */
export function nextVersionPreview(
  existing: readonly string[],
  strategy: 'MINOR' | 'MAJOR',
): string {
  const parsed = existing
    .map(parseVersion)
    .filter((parts): parts is [number, number] => parts !== null);
  if (parsed.length === 0) {
    return FIRST_VERSION;
  }
  const max = parsed.reduce((acc, cur) =>
    cur[0] !== acc[0] ? (cur[0] > acc[0] ? cur : acc) : cur[1] > acc[1] ? cur : acc,
  );
  return strategy === 'MAJOR' ? `v${max[0] + 1}.0` : `v${max[0]}.${max[1] + 1}`;
}
