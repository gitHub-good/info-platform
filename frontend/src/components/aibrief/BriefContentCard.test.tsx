import { cleanup, render, screen } from '@testing-library/react';
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
