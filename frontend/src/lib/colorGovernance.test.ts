// 市场方向色裸写清零断言（M26 T229，走查问题单关闭口径）：red/green 系只允许出现在
// lib/format.ts 单点导出（方向轨 = changeColorClass/directionTextClass/directionToneClass），
// 页面/组件裸写 text-red-*/text-green-*/bg-red-*/bg-green-* 即双轨回潮 → 用例失败。
// 状态轨（emerald/amber/rose）另因类型徽章等装饰性多色使用（sky/violet 类型色系）不做全量禁写，
// 状态语义场景一律经 statusToneClass/statusTextClass（抽查断言在各组件测试）。
import { describe, expect, it } from 'vitest';
import { readdirSync, readFileSync, statSync } from 'node:fs';
import { join, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';

const SRC_ROOT = join(dirname(fileURLToPath(import.meta.url)), '..');

/** 递归收集源文件（排除测试与 lib/format.ts 单点）。 */
function collectSourceFiles(dir: string): string[] {
  const files: string[] = [];
  for (const name of readdirSync(dir)) {
    const full = join(dir, name);
    if (statSync(full).isDirectory()) {
      files.push(...collectSourceFiles(full));
      continue;
    }
    if (!/\.(tsx?|ts)$/.test(name)) continue;
    if (/\.test\.(tsx?|ts)$/.test(name)) continue;
    if (full.endsWith('lib/format.ts')) continue; // 单点导出豁免
    files.push(full);
  }
  return files;
}

/** 方向轨裸写模式（text/bg/border × red/green 色阶）。 */
const RAW_DIRECTION_COLOR = /(?:text|bg|border)-(?:red|green)-\d{3,4}/;

describe('T229 方向色单点收口（裸写清零）', () => {
  it('src 内（除 lib/format.ts 与测试）无 red/green 裸写类名', () => {
    const offenders: string[] = [];
    for (const file of collectSourceFiles(SRC_ROOT)) {
      const lines = readFileSync(file, 'utf8').split('\n');
      lines.forEach((line, idx) => {
        if (RAW_DIRECTION_COLOR.test(line)) {
          offenders.push(`${file.replace(SRC_ROOT, 'src')}:${idx + 1} ${line.trim()}`);
        }
      });
    }
    expect(offenders, `方向色裸写回潮：\n${offenders.join('\n')}`).toEqual([]);
  });
});
