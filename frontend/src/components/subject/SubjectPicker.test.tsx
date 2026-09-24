import { cleanup, render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { useState } from 'react';
import { SubjectPicker } from '@/components/subject/SubjectPicker';
import type { SubjectSummary } from '@/api/subject';

// —— fetch mock：GET /subjects/search?q=…（可编程成功/失败序列） —— #

const MAOTAI: SubjectSummary = {
  id: 1,
  subjectCode: 'SH600519',
  name: '贵州茅台',
  market: 'A_SHARE',
  type: 1,
  industry: '白酒',
};
const CATL: SubjectSummary = {
  id: 40,
  subjectCode: 'SZ300750',
  name: '宁德时代',
  market: 'A_SHARE',
  type: 1,
  industry: '动力电池',
};

function okResponse(data: unknown) {
  return {
    ok: true,
    status: 200,
    json: async () => ({ code: 0, msg: 'ok', data, traceId: 't' }),
  };
}

/** 按 URL 解析查询词 → 返回命中列表；可注入按调用次序的强制失败；matchAll 跳过本地过滤（键盘用例固定两行）。 */
function makeSearchFetch(
  results: SubjectSummary[] = [MAOTAI, CATL],
  failures = 0,
  matchAll = false,
) {
  let calls = 0;
  const fetch = vi.fn(async (url: string) => {
    const q = new URL(String(url), 'http://localhost').searchParams.get('q') ?? '';
    if (calls < failures) {
      calls++;
      return {
        ok: false,
        status: 500,
        json: async () => ({ code: 50000, msg: '服务异常', data: null, traceId: 't' }),
      };
    }
    calls++;
    const matched = matchAll
      ? results
      : results.filter((s) => s.name.includes(q) || s.subjectCode.includes(q.toUpperCase()));
    return okResponse(matched);
  });
  return { fetch };
}

afterEach(() => {
  cleanup();
  vi.unstubAllGlobals();
  localStorage.clear();
});

/** 有状态包装：真实消费方持有 value 状态（受控组件回填/清除依赖父级回写）。 */
function StatefulPicker({
  initial = null,
  onChange,
  debounceMs,
}: {
  initial?: SubjectSummary | null;
  onChange?: (s: SubjectSummary | null) => void;
  debounceMs?: number;
}) {
  const [value, setValue] = useState<SubjectSummary | null>(initial);
  return (
    <SubjectPicker
      value={value}
      onChange={(s) => {
        setValue(s);
        onChange?.(s);
      }}
      debounceMs={debounceMs}
    />
  );
}

describe('SubjectPicker 标的搜索选择器', () => {
  it('输入联想：防抖后请求 search，下拉展示代码/名称/行业，点击选中回填并回调对象', async () => {
    const { fetch } = makeSearchFetch();
    vi.stubGlobal('fetch', fetch);
    const user = userEvent.setup();
    const onChange = vi.fn();
    render(<StatefulPicker onChange={onChange} debounceMs={10} />);

    await user.type(screen.getByTestId('subject-picker-input'), '茅台');

    // 下拉出现：代码 + 名称 + 行业徽章
    const option = await screen.findByTestId('subject-picker-option-1');
    expect(option).toHaveTextContent('SH600519');
    expect(option).toHaveTextContent('贵州茅台');
    expect(option).toHaveTextContent('白酒');

    // 点击选中：onChange 收到完整标的对象，输入切换为已选态
    await user.click(option);
    expect(onChange).toHaveBeenCalledWith(MAOTAI);
    expect(screen.getByTestId('subject-picker-selected')).toHaveTextContent('SH600519');
    expect(screen.getByTestId('subject-picker-selected')).toHaveTextContent('贵州茅台');

    // 请求带查询词与 limit
    const calledUrl = String(fetch.mock.calls[0][0]);
    expect(calledUrl).toContain('/subjects/search');
    expect(calledUrl).toContain(encodeURIComponent('茅台'));
    expect(calledUrl).toContain('limit=20');
  });

  it('防抖收敛：连续输入多字符只发起一次搜索请求', async () => {
    const { fetch } = makeSearchFetch();
    vi.stubGlobal('fetch', fetch);
    const user = userEvent.setup();
    render(<StatefulPicker debounceMs={50} />);

    await user.type(screen.getByTestId('subject-picker-input'), '600519');

    await waitFor(() => expect(fetch).toHaveBeenCalledTimes(1));
    // 等待防抖窗口稳定后仍只有一次（末次查询词）
    await new Promise((r) => setTimeout(r, 80));
    expect(fetch).toHaveBeenCalledTimes(1);
    expect(String(fetch.mock.calls[0][0])).toContain(encodeURIComponent('600519'));
  });

  it('键盘操作：↓ 移动高亮、Enter 确认选中', async () => {
    const { fetch } = makeSearchFetch([MAOTAI, CATL], 0, true); // 固定返回两行，保证 ↓ 有目标
    vi.stubGlobal('fetch', fetch);
    const user = userEvent.setup();
    const onChange = vi.fn();
    render(<StatefulPicker onChange={onChange} debounceMs={10} />);

    await user.type(screen.getByTestId('subject-picker-input'), '茅');
    await screen.findByTestId('subject-picker-option-1');

    // 默认高亮第 0 行；↓ 移到第 1 行，Enter 确认 → 选中宁德时代
    await user.keyboard('{ArrowDown}');
    await user.keyboard('{Enter}');

    expect(onChange).toHaveBeenCalledWith(CATL);
    expect(screen.getByTestId('subject-picker-selected')).toHaveTextContent('宁德时代');
  });

  it('三态：搜索中 → 无结果（含查询词提示）', async () => {
    const { fetch } = makeSearchFetch([]); // 无命中
    vi.stubGlobal('fetch', fetch);
    const user = userEvent.setup();
    render(<StatefulPicker debounceMs={10} />);

    await user.type(screen.getByTestId('subject-picker-input'), '不存在');

    expect(await screen.findByTestId('subject-picker-empty')).toHaveTextContent('未找到');
    expect(screen.queryByTestId('subject-picker-option-1')).toBeNull();
  });

  it('三态：失败展示错误与重试，重试成功恢复结果', async () => {
    const { fetch } = makeSearchFetch([MAOTAI], 1); // 首次失败、重试成功
    vi.stubGlobal('fetch', fetch);
    const user = userEvent.setup();
    const onChange = vi.fn();
    render(<StatefulPicker onChange={onChange} debounceMs={10} />);

    await user.type(screen.getByTestId('subject-picker-input'), '茅台');
    expect(await screen.findByTestId('subject-picker-error')).toHaveTextContent('搜索失败');

    await user.click(screen.getByTestId('subject-picker-retry'));
    const option = await screen.findByTestId('subject-picker-option-1');
    await user.click(option);

    expect(onChange).toHaveBeenCalledWith(MAOTAI);
    expect(fetch).toHaveBeenCalledTimes(2);
  });

  it('受控清除：已选态点 × 回调 null 并回到输入态', async () => {
    vi.stubGlobal('fetch', vi.fn());
    const user = userEvent.setup();
    const onChange = vi.fn();
    render(<StatefulPicker initial={MAOTAI} onChange={onChange} />);

    await user.click(screen.getByTestId('subject-picker-clear'));

    expect(onChange).toHaveBeenCalledWith(null);
    expect(screen.getByTestId('subject-picker-input')).toBeInTheDocument();
  });
});
