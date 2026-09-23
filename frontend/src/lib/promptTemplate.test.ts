import { describe, expect, it } from 'vitest';
import {
  compareVersions,
  countPlaceholderKeys,
  diffPlaceholders,
  extractPlaceholderKeys,
  hardErrorsOf,
  nextVersionPreview,
  splitSections,
} from '@/lib/promptTemplate';

const VALID_TEMPLATE = '---SYSTEM---\n你是分析师，输出 json。\n---USER---\n标的：{{subjectName}}（{{subjectCode}}）';

const REGISTRY = [
  { key: 'subjectName', description: '标的名称' },
  { key: 'subjectCode', description: '标的代码' },
  { key: 'roe', description: 'ROE' },
];

describe('promptTemplate 纯函数（M5 前端口径）', () => {
  it('extractPlaceholderKeys：{{key}} 按出现序去重；单花括号 JSON 形状与空白变体口径', () => {
    const text = '{{price}} {{ subjectName }} {{price}} {summary, outlook} {{newsList}}';
    expect(extractPlaceholderKeys(text)).toEqual(['price', 'subjectName', 'newsList']);
    expect(countPlaceholderKeys(text)).toBe(3);
    expect(extractPlaceholderKeys('')).toEqual([]);
  });

  it('splitSections：主路径切分两段；缺 USER / 顺序颠倒返回 null', () => {
    expect(splitSections(VALID_TEMPLATE)).toEqual({
      system: '\n你是分析师，输出 json。\n',
      user: '\n标的：{{subjectName}}（{{subjectCode}}）',
    });
    expect(splitSections('---SYSTEM---\n只有 system')).toBeNull();
    expect(splitSections('---USER---\n在前\n---SYSTEM---\n在后')).toBeNull();
  });

  it('hardErrorsOf：合法模板无错误；缺 USER / 顺序错 / 缺 json / 空模板逐条镜像后端文案', () => {
    expect(hardErrorsOf(VALID_TEMPLATE)).toEqual([]);
    expect(hardErrorsOf('---SYSTEM---\n输出 json')).toEqual(['缺少 ---USER--- 分段标记']);
    expect(hardErrorsOf('---USER---\nu\n---SYSTEM---\njson')).toEqual([
      '分段标记顺序错误: ---SYSTEM--- 须在 ---USER--- 之前',
    ]);
    // json 大小写不敏感
    expect(hardErrorsOf('---SYSTEM---\n你是分析师，输出 JSON。\n---USER---\nu')).toEqual([]);
    expect(hardErrorsOf('---SYSTEM---\n你是分析师。\n---USER---\nu')).toEqual([
      'system 段须含 json 字样（JSON 输出模式前提）',
    ]);
    expect(hardErrorsOf('   ')).toEqual(['模板不能为空']);
  });

  it('diffPlaceholders：removed = 底稿−新稿（带注册表说明）；unknown = 新稿−注册表', () => {
    const base = '{{subjectName}} {{subjectCode}} {{roe}}';
    const next = '{{subjectName}} {{foo}}';
    expect(diffPlaceholders(next, base, REGISTRY)).toEqual({
      removed: [
        { key: 'subjectCode', description: '标的代码' },
        { key: 'roe', description: 'ROE' },
      ],
      unknown: [{ key: 'foo', description: null }],
    });
    // 底稿为空（无激活版异常态）：removed 恒空
    expect(diffPlaceholders(next, null, REGISTRY).removed).toEqual([]);
    // 注册表未加载：unknown 判定降级为空（保存校验降级为仅硬校验 + 后端兜底）
    expect(diffPlaceholders(next, base, null).unknown).toEqual([]);
  });

  it('nextVersionPreview：MINOR 次版本 +1（v1.9 → v1.10）；MAJOR 归零；非法格式忽略；空集 v1.0', () => {
    expect(nextVersionPreview(['v1.0', 'v1.9', 'v1.2'], 'MINOR')).toBe('v1.10');
    expect(nextVersionPreview(['v1.9'], 'MAJOR')).toBe('v2.0');
    expect(nextVersionPreview(['v2.3', 'v1.9'], 'MAJOR')).toBe('v3.0');
    expect(nextVersionPreview(['v1.0.1', 'dirty'], 'MINOR')).toBe('v1.0');
    expect(nextVersionPreview([], 'MINOR')).toBe('v1.0');
  });

  it('compareVersions：数值序（v1.10 > v1.9 > v1.2）；非法格式退化字符串比较不抛异常', () => {
    expect(compareVersions('v1.10', 'v1.9')).toBeGreaterThan(0);
    expect(compareVersions('v1.9', 'v1.2')).toBeGreaterThan(0);
    expect(compareVersions('v1.2', 'v1.2')).toBe(0);
    expect(compareVersions('a', 'b')).toBeLessThan(0);
  });
});
