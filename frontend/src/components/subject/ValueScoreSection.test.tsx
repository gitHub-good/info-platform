// M20 T173：标的详情「价值评分」区块组件测试（方案 §4.8 + §6 前端组件测试清单）：
// 渲染分解 / 空态 30086 / 突破徽章 / 权重 0 弱化与缺数中性态 / 依据事件下钻 / 免责常驻 / 错误重试 / subjectId 未解析零请求。

import { cleanup, render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { ValueScoreSection } from '@/components/subject/ValueScoreSection';
import type { ValueScoreFactor, ValueScoreView } from '@/types/valueScore';

const SUBJECT_ID = 101;

function ok(data: unknown) {
  return {
    ok: true,
    status: 200,
    json: async () => ({ code: 0, msg: 'ok', data, traceId: 't' }),
  };
}

function notFound() {
  return {
    ok: false,
    status: 404,
    json: async () => ({ code: 30086, msg: '该标的无评分快照', data: null, traceId: 't' }),
  };
}

function fail() {
  return {
    ok: false,
    status: 500,
    json: async () => ({ code: 50000, msg: '服务异常', data: null, traceId: 't' }),
  };
}

const BASIS = 'vs-v1:w=0.40|0.20|0.20|0.20|0.00;win=10|30;hl=5.0;k=3.0|1.5;bt=60|50|80';

function factorOf(
  key: ValueScoreFactor['key'],
  name: string,
  score: number,
  weight: number,
  neutral = false,
): ValueScoreFactor {
  return { key, name, score, weight, neutral };
}

function viewOf(overrides: Partial<ValueScoreView> = {}): ValueScoreView {
  return {
    subjectId: SUBJECT_ID,
    snapshotDate: '2026-09-21',
    totalScore: 72.4,
    breakthrough: true,
    rank: 17,
    percentile: 99,
    factors: [
      factorOf('catalyst', '事件催化', 81.2, 0.4),
      factorOf('conduction', '行业传导', 63, 0.2),
      factorOf('fundamental', '基本面边际', 88.1, 0.2),
      factorOf('risk', '风险安全', 95, 0.2),
      factorOf('valuation', '估值水平', 37.9, 0, false),
    ],
    detail: {
      catalyst: {
        raw: 4.2,
        entries: [
          {
            eventId: 123,
            summary: '三季报预告净利增 30%',
            eventDate: '2026-09-18',
            direction: 'BULLISH',
            importance: 'HIGH',
            coef: 1,
            decay: 0.57,
          },
        ],
      },
    },
    dataFlags: [],
    weightBasis: BASIS,
    computedAt: '2026-09-21T09:30:00Z',
    disclaimer: '评分为多因子信息整理，不构成投资建议',
    ...overrides,
  };
}

function renderSection(fetchMock: ReturnType<typeof vi.fn>, subjectId: number | null = SUBJECT_ID) {
  vi.stubGlobal('fetch', fetchMock);
  return render(<ValueScoreSection subjectId={subjectId} />);
}

afterEach(() => {
  vi.unstubAllGlobals();
  cleanup();
  window.location.hash = '';
});

describe('ValueScoreSection 价值评分区块（M20 T173）', () => {
  it('渲染：总分大数字 + 百分位徽章「超过全市场 X%」+ 排名 + 快照日 + 五维分解', async () => {
    renderSection(vi.fn(async () => ok(viewOf())));

    expect(await screen.findByTestId('value-score-total')).toHaveTextContent('72.4');
    expect(screen.getByTestId('value-score-percentile')).toHaveTextContent('超过全市场 99%');
    expect(screen.getByTestId('value-score-rank')).toHaveTextContent('第 17 名');
    expect(screen.getByTestId('value-score-date')).toHaveTextContent('快照 2026-09-21');
    for (const key of ['catalyst', 'conduction', 'fundamental', 'risk', 'valuation'] as const) {
      expect(screen.getByTestId(`value-score-factor-${key}`)).toBeInTheDocument();
    }
    expect(screen.getByText('事件催化')).toBeInTheDocument();
    expect(screen.getByText('估值水平')).toBeInTheDocument();
  });

  it('分解条：五维分数与权重渲染，估值维权重 0 显示「未启用」弱化态', async () => {
    renderSection(vi.fn(async () => ok(viewOf())));
    await screen.findByTestId('value-score-total');

    const catalyst = screen.getByTestId('value-score-factor-catalyst');
    expect(catalyst).toHaveTextContent('81.2');
    expect(catalyst).toHaveTextContent('权重 0.40');
    expect(screen.getByTestId('value-score-factor-valuation')).toHaveTextContent('未启用');
    expect(screen.getByTestId('value-score-factor-valuation-disabled')).toBeInTheDocument();
    // 弱化态样式（opacity-60 与其他维区分）
    expect(screen.getByTestId('value-score-factor-valuation').className).toContain('opacity-60');
    expect(screen.getByTestId('value-score-factor-catalyst').className).not.toContain('opacity-60');
  });

  it('中性态：估值维缺数（neutral=true）显示「缺数中性」标注', async () => {
    const view = viewOf({
      factors: [
        factorOf('catalyst', '事件催化', 81.2, 0.4),
        factorOf('conduction', '行业传导', 63, 0.2),
        factorOf('fundamental', '基本面边际', 88.1, 0.2),
        factorOf('risk', '风险安全', 95, 0.2),
        factorOf('valuation', '估值水平', 50, 0, true),
      ],
    });
    renderSection(vi.fn(async () => ok(view)));
    await screen.findByTestId('value-score-total');

    expect(screen.getByTestId('value-score-factor-valuation-neutral')).toHaveTextContent('缺数中性');
    expect(screen.queryByTestId('value-score-factor-valuation-disabled')).toBeNull();
  });

  it('「有突破」徽章：条件满足时 emerald 高亮 + tooltip 三条件（阈值取自 weightBasis）', async () => {
    renderSection(vi.fn(async () => ok(viewOf())));
    const badge = await screen.findByTestId('value-score-breakthrough');

    expect(badge).toHaveTextContent('有突破');
    expect(badge.className).toContain('emerald');
    expect(badge.getAttribute('title')).toBe(
      '三条件同时满足：事件催化 ≥ 60 · 行业传导 ≥ 50 · 风险安全 ≥ 80',
    );
  });

  it('无突破时不渲染徽章；tooltip 阈值随 weightBasis 变化', async () => {
    const view = viewOf({
      breakthrough: false,
      weightBasis: 'vs-v1:w=0.40|0.20|0.20|0.20|0.00;win=10|30;hl=5.0;k=3.0|1.5;bt=70|45|85',
    });
    renderSection(vi.fn(async () => ok(view)));
    await screen.findByTestId('value-score-total');

    expect(screen.queryByTestId('value-score-breakthrough')).toBeNull();
  });

  it('依据下钻：catalyst entries 渲染 + eventId 链接跳事件流 focus', async () => {
    renderSection(vi.fn(async () => ok(viewOf())));
    await screen.findByTestId('value-score-total');

    expect(screen.getByText('三季报预告净利增 30%')).toBeInTheDocument();
    expect(screen.getByText('2026-09-18')).toBeInTheDocument();
    const link = screen.getByTestId('value-score-entry-123');
    expect(link.getAttribute('href')).toBe('#/events?focus=123');
    expect(link).toHaveTextContent('查看事件');
  });

  it('空态：30086 → 引导「每日盘后 17:30 生成」不发重试按钮', async () => {
    renderSection(vi.fn(async () => notFound()));

    expect(await screen.findByTestId('value-score-empty')).toHaveTextContent('17:30');
    expect(screen.queryByTestId('value-score-total')).toBeNull();
    expect(screen.queryByTestId('value-score-retry')).toBeNull();
  });

  it('错误态：非 30086 错误就地展示 + 重试可恢复', async () => {
    let failed = true;
    const fetchMock = vi.fn(async () => (failed ? fail() : ok(viewOf())));
    renderSection(fetchMock);
    expect(await screen.findByTestId('value-score-error')).toHaveTextContent('服务异常');

    failed = false;
    await userEvent.setup().click(screen.getByTestId('value-score-retry'));
    expect(await screen.findByTestId('value-score-total')).toHaveTextContent('72.4');
    expect(fetchMock).toHaveBeenCalledTimes(2);
  });

  it('免责一行与参数指纹脚注常驻（区块级轻免责，方案 §4.8）', async () => {
    renderSection(vi.fn(async () => ok(viewOf())));
    await screen.findByTestId('value-score-total');

    expect(screen.getByTestId('value-score-disclaimer')).toHaveTextContent(
      '评分为多因子信息整理，不构成投资建议',
    );
    expect(screen.getByTestId('value-score-basis')).toHaveTextContent(BASIS);
  });

  it('加载态骨架；subjectId 未解析（null）零请求不渲染分区', async () => {
    const fetchMock = vi.fn(async () => ok(viewOf()));
    const { rerender } = renderSection(fetchMock, null);
    expect(document.querySelector('[data-testid="value-score-section"]')).toBeNull();
    expect(fetchMock).not.toHaveBeenCalled();

    rerender(<ValueScoreSection subjectId={SUBJECT_ID} />);
    expect(await screen.findByTestId('value-score-total')).toBeInTheDocument();
    expect(fetchMock).toHaveBeenCalledTimes(1);
  });
});
