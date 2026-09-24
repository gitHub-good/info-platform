import { useCallback, useEffect, useState } from 'react';
import type { SubjectDetailData } from '@/types/subject-detail';
import { fetchSubjectByCode, fetchSubjectDetail } from '@/api/subject';

export interface SubjectDetailState {
  data: SubjectDetailData | null;
  /** 解析出的数字主键（「AI 简报」等带参跳转用；未解析/失败为 null） */
  subjectId: number | null;
  loading: boolean;
  error: Error | null;
  /** 错误态重试（P0-1 顺带项：错误页不把用户锁死） */
  retry: () => void;
}

/** effect 内部维护的请求状态（retry 由 hook 组装，不进 state） */
interface DetailFetchState {
  data: SubjectDetailData | null;
  subjectId: number | null;
  loading: boolean;
  error: Error | null;
}

const INITIAL_STATE: DetailFetchState = {
  data: null,
  subjectId: null,
  loading: true,
  error: null,
};

/**
 * 获取标的详情聚合数据（P0-1 真实化）。
 *
 * 两跳：路由代码（如 SH600519）先经 by-code 端点解析数字主键，
 * 再请求聚合详情接口；retry 触发同 code 重放（attempt 计数驱动 effect）。
 *
 * @param subjectCode 标的代码（内部统一代码，如 SH600519）
 */
export function useSubjectDetail(subjectCode: string): SubjectDetailState {
  const [attempt, setAttempt] = useState(0);
  const [state, setState] = useState<DetailFetchState>(INITIAL_STATE);

  useEffect(() => {
    let cancelled = false;
    setState(INITIAL_STATE);
    fetchSubjectByCode(subjectCode)
      .then((subject) =>
        fetchSubjectDetail(subject.id).then((data) => {
          if (!cancelled) {
            setState({ data, subjectId: subject.id, loading: false, error: null });
          }
        }),
      )
      .catch((err: unknown) => {
        if (cancelled) return;
        const error = err instanceof Error ? err : new Error(String(err));
        setState({ data: null, subjectId: null, loading: false, error });
      });
    return () => {
      cancelled = true;
    };
  }, [subjectCode, attempt]);

  const retry = useCallback(() => setAttempt((current) => current + 1), []);

  return { ...state, retry };
}
