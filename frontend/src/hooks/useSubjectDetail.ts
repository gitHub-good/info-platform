import { useEffect, useState } from 'react';
import type { SubjectDetailData } from '@/types/subject-detail';
import { fetchSubjectDetail } from '@/api/subject';

export interface SubjectDetailState {
  data: SubjectDetailData | null;
  loading: boolean;
  error: Error | null;
}

/**
 * 获取标的详情聚合数据。
 *
 * @param subjectId 标的代码（内部统一代码，如 SH600519）
 * @param injected 测试 / 预渲染注入的数据；提供时直接返回，不触发请求，
 *                 便于用例对渲染逻辑做确定性断言。真实页面不传该参数，
 *                 走 fetchSubjectDetail（当前 mock，联调日切真实接口）。
 */
export function useSubjectDetail(
  subjectId: string,
  injected?: SubjectDetailData | null,
): SubjectDetailState {
  const [state, setState] = useState<SubjectDetailState>(() =>
    injected
      ? { data: injected, loading: false, error: null }
      : { data: null, loading: true, error: null },
  );

  useEffect(() => {
    if (injected) return;
    let cancelled = false;
    setState({ data: null, loading: true, error: null });
    fetchSubjectDetail(subjectId)
      .then((data) => {
        if (!cancelled) setState({ data, loading: false, error: null });
      })
      .catch((err: unknown) => {
        if (cancelled) return;
        const error = err instanceof Error ? err : new Error(String(err));
        setState({ data: null, loading: false, error });
      });
    return () => {
      cancelled = true;
    };
  }, [subjectId, injected]);

  return state;
}
