import type { ApiResponse, SectionCode, SubjectDetailData } from '@/types/subject-detail';
import { subjectDetailMock } from '@/mocks/subject-detail-mock';

/**
 * 数据适配层开关。
 * - mock.enabled = true（当前阶段）：返回本地 mock，供前端搭骨架。
 * - 联调日把 mock.enabled 改 false，自动走真实聚合接口；组件层无需改动。
 *
 * 真实接口受 JWT 保护（T17），需 Bearer token；login / refresh / actuator 在白名单内。
 */
export const adapter = {
  baseUrl: import.meta.env.VITE_API_BASE_URL ?? '/api/v1',
  mock: {
    enabled: true,
  },
};

const ALL_SECTIONS: SectionCode[] = [
  'quote',
  'finance',
  'valuation',
  'announce',
  'news',
  'policy',
];

/** 读取登录后写入 localStorage 的 access_token（T17 颁发） */
function getAccessToken(): string | null {
  try {
    return localStorage.getItem('access_token');
  } catch {
    // localStorage 不可用（如 SSR / 隐私模式）时按未登录处理
    return null;
  }
}

/**
 * 拉取标的详情聚合数据。
 * §4.1.1 GET /api/v1/subjects/{subjectId}/detail?sections=quote,finance,...
 * 单源缺失不阻断，后端在 sourceStatus 中标注每分区状态。
 */
export async function fetchSubjectDetail(
  subjectId: string,
  sections: SectionCode[] = ALL_SECTIONS,
  signal?: AbortSignal,
): Promise<SubjectDetailData> {
  if (adapter.mock.enabled) {
    // mock：模拟一次网络往返，便于联调前验证 loading / 三态
    await new Promise((resolve) => setTimeout(resolve, 120));
    return subjectDetailMock;
  }

  const url = `${adapter.baseUrl}/subjects/${encodeURIComponent(subjectId)}/detail?sections=${sections.join(',')}`;
  const headers: Record<string, string> = { Accept: 'application/json' };
  const token = getAccessToken();
  if (token) headers.Authorization = `Bearer ${token}`;

  const res = await fetch(url, { headers, signal });
  if (!res.ok) {
    throw new Error(`聚合接口请求失败：HTTP ${res.status}`);
  }
  const body = (await res.json()) as ApiResponse<SubjectDetailData>;
  if (body.code !== 0) {
    throw new Error(`聚合接口返回错误：code=${body.code} msg=${body.msg}`);
  }
  return body.data;
}
