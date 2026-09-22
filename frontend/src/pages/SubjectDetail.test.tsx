import { cleanup, render, screen, within } from '@testing-library/react';
import { afterEach, describe, expect, it } from 'vitest';
import { SubjectDetail } from '@/pages/SubjectDetail';
import { subjectDetailMock } from '@/mocks/subject-detail-mock';

afterEach(() => {
  cleanup();
});

function renderPage() {
  return render(<SubjectDetail subjectId="SH600519" data={subjectDetailMock} />);
}

describe('SubjectDetail 标的详情聚合页', () => {
  // 注入含混合 sourceStatus 的 mock（5 ok / 1 missing / 1 failed），确定性断言渲染逻辑

  it('渲染标的头部（名称 / 代码 / 市场 / 行业）', () => {
    renderPage();
    expect(screen.getByText('贵州茅台')).toBeInTheDocument();
    expect(screen.getByText(/代码：SH600519/)).toBeInTheDocument();
    expect(screen.getByText(/行业：白酒/)).toBeInTheDocument();
    expect(screen.getByText('A 股')).toBeInTheDocument();
  });

  it('行情分区展示价格 / 涨跌幅，并标注来源与时间戳', () => {
    renderPage();
    expect(screen.getByText('1680.00')).toBeInTheDocument();
    expect(screen.getByText('1.23%')).toBeInTheDocument();
    expect(screen.getByText(/行情源（新浪财经）/)).toBeInTheDocument();
    expect(screen.getByText(/更新于 2026-09-21 15:00:00/)).toBeInTheDocument();
  });

  it('财务分区展示营收 / 净利 / 毛利率 / ROE / 报告期', () => {
    renderPage();
    expect(screen.getByText('888.00亿')).toBeInTheDocument();
    expect(screen.getByText('415.50亿')).toBeInTheDocument();
    expect(screen.getByText('91.53%')).toBeInTheDocument();
    expect(screen.getByText('30.56%')).toBeInTheDocument();
    expect(screen.getByText('2026 年中报')).toBeInTheDocument();
  });

  it('估值分区展示 PE / PB', () => {
    renderPage();
    expect(screen.getByText('25.60')).toBeInTheDocument();
    expect(screen.getByText('8.90')).toBeInTheDocument();
  });

  it('公告分区展示 3 条，每条标注来源与时间', () => {
    renderPage();
    const list = screen.getByTestId('announce-list');
    const items = within(list).getAllByRole('listitem');
    expect(items).toHaveLength(3);
    expect(screen.getByText('贵州茅台 2026 年半年度报告')).toBeInTheDocument();
    expect(within(list).getAllByText('来源：公告源（上交所）')).toHaveLength(3);
  });

  it('missing 分区（新闻）显示“暂无数据”且不阻断其他分区', () => {
    renderPage();
    // 徽章 + 兜底文案
    expect(screen.getByText('暂无数据')).toBeInTheDocument();
    expect(screen.getByText('本分区暂未返回数据')).toBeInTheDocument();
    // 列表未渲染
    expect(screen.queryByTestId('news-list')).toBeNull();
    // 其他分区仍正常展示（不阻断）
    expect(screen.getByText('1680.00')).toBeInTheDocument();
  });

  it('failed 分区（政策）显示“获取失败”', () => {
    renderPage();
    expect(screen.getByText('获取失败')).toBeInTheDocument();
    expect(screen.getByText(/本分区数据获取失败/)).toBeInTheDocument();
    expect(screen.queryByTestId('policy-list')).toBeNull();
  });

  it('事件分区展示异动类型 / 涨跌幅 / 详情 / 触发时间（ADR-0013 本地事件源）', () => {
    renderPage();
    const list = screen.getByTestId('event-list');
    const items = within(list).getAllByRole('listitem');
    expect(items).toHaveLength(2);
    expect(within(list).getByText('涨跌幅异动')).toBeInTheDocument();
    expect(within(list).getByText('3.25%')).toBeInTheDocument();
    expect(
      within(list).getByText('日涨跌幅 3.25% 触发阈值 3.0%（现价 1680.50）'),
    ).toBeInTheDocument();
    // 事件条目（无涨跌幅）不渲染百分比，仅展示类型标签与详情
    expect(within(list).getByText('事件')).toBeInTheDocument();
  });

  it('sourceStatus 徽章按分区状态渲染（5 ok / 1 missing / 1 failed）', () => {
    renderPage();
    expect(screen.getAllByText('数据正常')).toHaveLength(5);
    expect(screen.getAllByTestId('status-badge-ok')).toHaveLength(5);
    expect(screen.getAllByTestId('status-badge-missing')).toHaveLength(1);
    expect(screen.getAllByTestId('status-badge-failed')).toHaveLength(1);
  });
});
