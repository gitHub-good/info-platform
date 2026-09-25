import { cleanup, render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, describe, expect, it } from 'vitest';
import { BriefContentCard } from '@/components/aibrief/BriefContentCard';
import type { BriefContent } from '@/types/aibrief';

afterEach(() => {
  cleanup();
});

const BASE_CONTENT: BriefContent = {
  summary: '摘要',
  disclaimer: 'AI 生成，非投资建议',
};

describe('BriefContentCard 每日推荐 Top5 展示（T29 推荐理由标注载体）', () => {
  it('content.topRecommend 非空：按 rank 升序渲染 No./标的/推荐理由', () => {
    // Arrange：rank 乱序返回，渲染层应按 rank 升序
    const content: BriefContent = {
      ...BASE_CONTENT,
      topRecommend: [
        { subjectCode: 'SH600036', subjectName: '招商银行', reason: '信息面活跃（综合分3.0）', rank: 3 },
        {
          subjectCode: 'SZ000858',
          subjectName: '五粮液',
          reason: '个性化相关：已读热度+10.0；标的订阅+6.0；综合分16.5',
          rank: 1,
        },
        { subjectCode: 'SH600519', subjectName: '贵州茅台', reason: '命中主题「白酒」+4.0', rank: 2 },
      ],
    };

    // Act
    render(<BriefContentCard content={content} />);

    // Assert：区块存在，顺序按 rank（五粮液→茅台→招行），每条含代码与推荐理由
    expect(screen.getByTestId('brief-top-recommend')).toBeInTheDocument();
    const items = screen.getAllByTestId(/^brief-top-recommend-\d+$/);
    expect(items).toHaveLength(3);
    expect(screen.getByTestId('brief-top-recommend-0')).toHaveTextContent('五粮液');
    expect(screen.getByTestId('brief-top-recommend-0')).toHaveTextContent('SZ000858');
    expect(screen.getByTestId('brief-top-recommend-reason-0')).toHaveTextContent('已读热度+10.0');
    expect(screen.getByTestId('brief-top-recommend-1')).toHaveTextContent('贵州茅台');
    expect(screen.getByTestId('brief-top-recommend-2')).toHaveTextContent('招商银行');
  });

  it('content.topRecommend 缺省（个股/事件/政策型简报）：不渲染推荐区块', () => {
    // Arrange / Act
    render(<BriefContentCard content={BASE_CONTENT} />);

    // Assert
    expect(screen.queryByTestId('brief-top-recommend')).not.toBeInTheDocument();
  });
});

describe('BriefContentCard 关键事件展示（内容质量：event 兜底 + impact 徽章 + reason 截断展开）', () => {
  it('event 为 null（v1.0 模板产物）：兜底标题取 reason 首句（≤20 字）+ impact 徽章渲染', () => {
    // Arrange：ai_brief id=13 实测形状——event null、内容全在 reason
    const content: BriefContent = {
      ...BASE_CONTENT,
      keyEvents: [
        {
          event: null,
          impact: '利好',
          reason:
            '子公司获得药品注册证书意味着产品取得上市许可资格，为公司业务面构成正向事件，实际收入贡献取决于后续推广',
          sourceUrl: 'https://example.com/a',
        },
      ],
    };

    // Act
    render(<BriefContentCard content={content} />);

    // Assert：兜底标题非空、取 reason 首句截断 ≤20 字；impact 徽章展示方向；原文链接保留
    const title = screen.getByTestId('brief-key-event-title-0');
    expect(title.textContent).toBeTruthy();
    expect(title.textContent!.length).toBeLessThanOrEqual(20);
    expect(title.textContent).toContain('药品注册证书');
    expect(screen.getByTestId('brief-key-event-impact-0')).toHaveTextContent('利好');
    expect(screen.getByTestId('brief-key-event-link-0')).toHaveAttribute(
      'href',
      'https://example.com/a',
    );
  });

  it('event 与 reason 均为空：兜底泛化标题，事件区不出现空白标题', () => {
    // Arrange
    const content: BriefContent = {
      ...BASE_CONTENT,
      keyEvents: [{ event: '', impact: '中性', reason: '' }],
    };

    // Act
    render(<BriefContentCard content={content} />);

    // Assert
    expect(screen.getByTestId('brief-key-event-title-0')).toHaveTextContent('关键事件');
  });

  it('event 已有值：原样展示（不被兜底改写）', () => {
    // Arrange
    const content: BriefContent = {
      ...BASE_CONTENT,
      keyEvents: [{ event: '定增获交易所受理', impact: '中性', reason: '理由' }],
    };

    // Act
    render(<BriefContentCard content={content} />);

    // Assert
    expect(screen.getByTestId('brief-key-event-title-0')).toHaveTextContent('定增获交易所受理');
  });

  it('reason 超长：两行截断，点击可展开/收起', async () => {
    // Arrange：超阈值长理由（两行装不下）
    const longReason =
      '定增处于监管审核问询回复阶段，若最终发行将增加股本，对每股收益构成潜在摊薄压力；' +
      '同时融资落地可补充资金，属双面事件，后续关注发行规模与价格确定的节奏。';
    const content: BriefContent = {
      ...BASE_CONTENT,
      keyEvents: [{ event: '定增问询函回复', impact: '利空', reason: longReason }],
    };
    const user = userEvent.setup();

    // Act
    render(<BriefContentCard content={content} />);
    const reason = screen.getByTestId('brief-key-event-reason-0');
    const toggle = screen.getByTestId('brief-key-event-toggle-0');

    // Assert：初始两行截断（line-clamp-2），全文存在但视觉折叠
    expect(reason.className).toContain('line-clamp-2');
    expect(reason).toHaveTextContent(longReason);

    // Act：展开 → 移除截断
    await user.click(toggle);
    expect(screen.getByTestId('brief-key-event-reason-0').className).not.toContain('line-clamp-2');

    // Act：再点收起 → 恢复截断
    await user.click(screen.getByTestId('brief-key-event-toggle-0'));
    expect(screen.getByTestId('brief-key-event-reason-0').className).toContain('line-clamp-2');
  });

  it('reason 短文本：不渲染展开按钮', () => {
    // Arrange
    const content: BriefContent = {
      ...BASE_CONTENT,
      keyEvents: [{ event: '事件', impact: '中性', reason: '短理由' }],
    };

    // Act + Assert
    render(<BriefContentCard content={content} />);
    expect(screen.queryByTestId('brief-key-event-toggle-0')).not.toBeInTheDocument();
  });
});

describe('BriefContentCard 摘要分句排版', () => {
  it('summary 按句读分段展示（每句独立段落）', () => {
    // Arrange：三句摘要（。！？分隔）
    const content: BriefContent = {
      ...BASE_CONTENT,
      summary: '沃森生物当前价13.58元，较昨收下跌3.55%。定增进入审核问询回复阶段！子公司mRNA疫苗获批上市许可。',
    };

    // Act
    render(<BriefContentCard content={content} />);

    // Assert：三句各一段，顺序保留
    const section = screen.getByTestId('brief-summary');
    expect(within(section).getAllByTestId(/^brief-summary-sentence-\d+$/)).toHaveLength(3);
    expect(screen.getByTestId('brief-summary-sentence-0')).toHaveTextContent(
      '沃森生物当前价13.58元，较昨收下跌3.55%。',
    );
    expect(screen.getByTestId('brief-summary-sentence-1')).toHaveTextContent(
      '定增进入审核问询回复阶段！',
    );
    expect(screen.getByTestId('brief-summary-sentence-2')).toHaveTextContent(
      '子公司mRNA疫苗获批上市许可。',
    );
  });

  it('summary 无句读：单段展示不丢内容', () => {
    // Arrange
    const content: BriefContent = { ...BASE_CONTENT, summary: '一句话摘要无标点结尾' };

    // Act + Assert
    render(<BriefContentCard content={content} />);
    expect(screen.getByTestId('brief-summary-sentence-0')).toHaveTextContent(
      '一句话摘要无标点结尾',
    );
  });
});
