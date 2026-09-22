import type { SubjectDetailData } from '@/types/subject-detail';

/**
 * mock 聚合接口响应（技术方案 §4.1.1）。
 *
 * 故意构造混合 sourceStatus，验证降级 UI：
 *   - quote / finance / valuation / announce / event：ok（正常展示，标注来源与时间戳）
 *   - news：missing（分区显示“暂无数据”，不阻断其他分区）
 *   - policy：failed（分区显示“获取失败”）
 *
 * 即 5 ok / 1 missing / 1 failed。联调日把 src/api/subject.ts 的 adapter.mock.enabled
 * 切 false 即改走真实接口，此文件不再被读取。
 */
export const subjectDetailMock: SubjectDetailData = {
  subject: {
    subjectCode: 'SH600519',
    name: '贵州茅台',
    market: 'A_SHARE',
    type: 1,
    industry: '白酒',
  },
  quote: {
    price: 1680.0,
    changePct: 1.23,
    open: 1665.0,
    high: 1695.0,
    low: 1658.0,
    preClose: 1659.5,
    volume: 21567800,
    amount: 3620000000,
    turnoverRate: 0.17,
    amplitude: 2.23,
    source: '行情源（新浪财经）',
    updatedAt: '2026-09-21 15:00:00',
  },
  finance: {
    revenue: 88800000000,
    netProfit: 41550000000,
    grossMargin: 91.53,
    roe: 30.56,
    reportPeriod: '2026 年中报',
    source: '财务源（同花顺）',
    updatedAt: '2026-08-28 20:30:00',
  },
  valuation: {
    pe: 25.6,
    pb: 8.9,
    source: '估值源（东方财富）',
    updatedAt: '2026-09-19 16:00:00',
  },
  announcements: [
    {
      id: 'ANN-2026-001',
      title: '贵州茅台 2026 年半年度报告',
      publishedAt: '2026-08-28 20:30:00',
      category: '定期报告',
      url: 'https://www.sse.com.cn/disclosure/listinginfo/announcement/ann001',
      source: '公告源（上交所）',
    },
    {
      id: 'ANN-2026-002',
      title: '关于控股股东权益变动的提示性公告',
      publishedAt: '2026-09-15 08:00:00',
      category: '权益变动',
      url: 'https://www.sse.com.cn/disclosure/listinginfo/announcement/ann002',
      source: '公告源（上交所）',
    },
    {
      id: 'ANN-2026-003',
      title: '关于召开 2026 年第三次临时股东大会的通知',
      publishedAt: '2026-09-18 18:00:00',
      category: '股东大会',
      url: 'https://www.sse.com.cn/disclosure/listinginfo/announcement/ann003',
      source: '公告源（上交所）',
    },
  ],
  // news: missing —— sourceStatus.news = 'missing'，分区显示“暂无数据”
  news: null,
  // policies: failed —— sourceStatus.policy = 'failed'，分区显示“获取失败”
  policies: null,
  // events（ADR-0013）：本地 anomaly_event 近期异动样例
  events: [
    {
      anomalyType: 'PRICE_CHANGE',
      changePct: 3.25,
      currentPrice: 1680.5,
      triggerTime: '2026-09-21T07:35:00Z',
      detail: '日涨跌幅 3.25% 触发阈值 3.0%（现价 1680.50）',
    },
    {
      anomalyType: 'EVENT',
      triggerTime: '2026-09-20T09:00:00Z',
      detail: '重大公告：控股股东权益变动（样例）',
    },
  ],
  sourceStatus: {
    quote: 'ok',
    finance: 'ok',
    valuation: 'ok',
    announce: 'ok',
    news: 'missing',
    policy: 'failed',
    event: 'ok',
  },
};
