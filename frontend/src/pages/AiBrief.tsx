import { useEffect, useRef, useState } from 'react';
import { Button } from '@/components/ui/button';
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card';
import { Skeleton } from '@/components/ui/skeleton';
import { BriefContentCard } from '@/components/aibrief/BriefContentCard';
import { BriefStatusBadge } from '@/components/aibrief/BriefStatusBadge';
import { BriefTriggerForm } from '@/components/aibrief/BriefTriggerForm';
import { DisclaimerBadge } from '@/components/aibrief/DisclaimerBadge';
import { SourceLinkList } from '@/components/aibrief/SourceLinkList';
import { logout } from '@/api/auth';
import { ApiError } from '@/api/http';
import { trackReadingOnce } from '@/api/readingEvent';
import {
  AI_BRIEF_POLL_INTERVAL_MS,
  createBrief,
  getBrief,
} from '@/api/aibrief';
import { navigate } from '@/lib/navigation';
import { isTerminalStatus } from '@/types/aibrief';
import type {
  AiBriefView,
  BriefStatusCode,
  BriefTypeCode,
} from '@/types/aibrief';

/** 非 ApiError 兜底文案。 */
function messageOf(err: unknown, fallback: string): string {
  return err instanceof ApiError ? err.msg : fallback;
}

/** 创建错误映射：30030 配额用尽 / 30001 标的不存在。 */
function createErrorMessage(err: unknown): string {
  if (err instanceof ApiError) {
    if (err.code === 30030) return '今日 AI 简报配额已用尽，请明日再试';
    if (err.code === 30001) return '标的不存在';
    if (err.code === 2001) return '参数非法，请检查标的 ID 与类型';
    return err.msg;
  }
  return '生成失败，请稍后重试';
}

/** 从 hash query 解析预填标的 ID（#/ai-brief?subjectId=1）。 */
function readQuerySubjectId(): string {
  if (typeof window === 'undefined') return '';
  const q = window.location.hash.split('?')[1];
  if (!q) return '';
  const sid = new URLSearchParams(q).get('subjectId');
  return sid && /^\d+$/.test(sid) ? sid : '';
}

interface AiBriefProps {
  /** 轮询间隔（默认 2s，测试可缩短加速）。 */
  pollIntervalMs?: number;
}

interface LastParams {
  subjectId: number | null;
  briefType: BriefTypeCode;
}

/**
 * AI 简报页（技术方案 §4.1.4 + PRD 故事 2 + T22）。
 * 流程：选标的 + briefType → POST /ai-briefs（Idempotency-Key，Bearer）→ 202 {taskId}
 *   → 每 pollIntervalMs 轮询 GET /ai-briefs/{taskId} 直到终态（1 完成 / 2 失败 / 3 待核实）。
 * - 完成（1）：展示 summary/keyEvents/bias/watchSuggestion + 事实回链 + 免责。
 * - 待核实（3）：content 仍展示，附「部分数值待核实」降级提示（幻觉校验不符）。
 * - 失败（2）：展示「生成失败，请重试」+ 重试。
 * - 处理中（0）/ 首次查询未返回：loading（骨架，不裸转圈）。
 * 受保护接口 401 由 http 层统一清 token 跳 /login。
 */
export function AiBrief({ pollIntervalMs = AI_BRIEF_POLL_INTERVAL_MS }: AiBriefProps = {}) {
  const [lastParams, setLastParams] = useState<LastParams | null>(null);
  const [taskId, setTaskId] = useState<number | null>(null);
  const [pollEpoch, setPollEpoch] = useState(0);
  const [view, setView] = useState<AiBriefView | null>(null);
  const [creating, setCreating] = useState(false);
  const [createError, setCreateError] = useState<string | null>(null);
  const [pollError, setPollError] = useState<string | null>(null);

  // pollIntervalMs 经 ref 读取，避免轮询 effect 依赖它重启（同一次生成内间隔恒定）
  const pollRef = useRef(pollIntervalMs);
  pollRef.current = pollIntervalMs;

  const handleTrigger = async (subjectId: number | null, briefType: BriefTypeCode) => {
    setLastParams({ subjectId, briefType });
    setCreating(true);
    setCreateError(null);
    setView(null);
    setPollError(null);
    try {
      const id = await createBrief(subjectId, briefType);
      setTaskId(id);
      setPollEpoch((e) => e + 1); // 即便幂等返回同 taskId 也强制重启轮询（重试场景）
    } catch (err) {
      setCreateError(createErrorMessage(err));
    } finally {
      setCreating(false);
    }
  };

  // 简报阅读埋点引用（T29）：终态且有内容时上报一次；ref 读取避免轮询 effect 依赖重启
  const trackRef = useRef<(taskId: number) => void>(() => {});
  trackRef.current = (taskId: number) => {
    trackReadingOnce(`brief:${taskId}`, {
      contentType: 'AI_BRIEF',
      contentRef: String(taskId),
      subjectId: lastParams?.subjectId ?? undefined,
    });
  };

  // 轮询：taskId 变化或重试 epoch 变化时启动；status=0 继续轮询，终态停止；卸载即取消。
  useEffect(() => {
    if (taskId == null) return;
    const controller = new AbortController();
    let cancelled = false;
    let timer: ReturnType<typeof setTimeout> | undefined;

    const tick = async () => {
      if (cancelled) return;
      try {
        const v = await getBrief(taskId, controller.signal);
        if (cancelled) return;
        setView(v);
        setPollError(null);
        // 阅读埋点（T29）：终态且有内容（完成/待核实）→ 上报一次（会话级去重、静默失败）
        if (isTerminalStatus(v.status) && v.content) {
          trackRef.current(taskId);
        }
        if (!isTerminalStatus(v.status)) {
          timer = setTimeout(() => void tick(), pollRef.current);
        }
      } catch (err) {
        if (cancelled || controller.signal.aborted) return;
        setPollError(messageOf(err, '简报查询失败，请重试'));
      }
    };

    void tick();
    return () => {
      cancelled = true;
      controller.abort();
      if (timer) clearTimeout(timer);
    };
    // pollEpoch 让重试（同 taskId）也能重启轮询
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [taskId, pollEpoch]);

  const status: BriefStatusCode | null = view?.status ?? null;
  const isLoading = taskId != null && (!view || status === 0);
  const isDone = status === 1;
  const isVerify = status === 3;
  const isFailed = status === 2;

  const handleRetry = () => {
    if (!lastParams) return;
    void handleTrigger(lastParams.subjectId, lastParams.briefType);
  };

  const handleLogout = () => {
    logout();
    navigate('/login');
  };

  return (
    <main className="mx-auto w-full max-w-4xl p-4 sm:p-6" data-testid="ai-brief-page">
      <header className="mb-4 flex items-center justify-between">
        <h1 className="text-xl font-medium">AI 简报</h1>
        <div className="flex items-center gap-2">
          <Button variant="outline" size="sm" onClick={() => navigate('/watchlists')} data-testid="ai-brief-back">
            返回清单
          </Button>
          <Button variant="outline" size="sm" onClick={handleLogout} data-testid="ai-brief-logout">
            登出
          </Button>
        </div>
      </header>

      <Card className="mb-4">
        <CardHeader>
          <CardTitle className="text-base">生成简报</CardTitle>
        </CardHeader>
        <CardContent>
          <BriefTriggerForm
            submitting={creating}
            error={createError}
            briefType={lastParams?.briefType}
            defaultSubjectId={readQuerySubjectId()}
            onTrigger={handleTrigger}
          />
        </CardContent>
      </Card>

      <ResultArea
        taskId={taskId}
        isLoading={isLoading}
        view={view}
        isDone={isDone}
        isVerify={isVerify}
        isFailed={isFailed}
        pollError={pollError}
        onRetry={handleRetry}
      />
    </main>
  );
}

interface ResultAreaProps {
  taskId: number | null;
  isLoading: boolean;
  view: AiBriefView | null;
  isDone: boolean;
  isVerify: boolean;
  isFailed: boolean;
  pollError: string | null;
  onRetry: () => void;
}

function ResultArea({
  taskId,
  isLoading,
  view,
  isDone,
  isVerify,
  isFailed,
  pollError,
  onRetry,
}: ResultAreaProps) {
  // 初始：未触发
  if (taskId == null && !view) {
    return (
      <div
        className="py-10 text-center text-sm text-muted-foreground"
        data-testid="brief-empty"
      >
        选择标的与简报类型，点击「生成 AI 简报」。
      </div>
    );
  }

  // 处理中 / 首次查询未返回
  if (isLoading) {
    return (
      <div className="flex flex-col gap-3" data-testid="brief-loading">
        <div className="flex items-center gap-2">
          <BriefStatusBadge status={0} />
          <span className="text-sm text-muted-foreground">生成中…</span>
        </div>
        <Skeleton className="h-24 w-full" />
        <Skeleton className="h-40 w-full" />
      </div>
    );
  }

  // 失败
  if (isFailed) {
    return (
      <div className="flex flex-col items-start gap-2" data-testid="brief-failed">
        <div className="flex items-center gap-2">
          <BriefStatusBadge status={2} />
          <span className="text-sm text-destructive">生成失败，请重试</span>
        </div>
        <Button variant="outline" size="sm" onClick={onRetry} data-testid="brief-retry">
          重试
        </Button>
      </div>
    );
  }

  // 轮询异常（任务不存在等）
  if (pollError && !isDone && !isVerify) {
    return (
      <div className="flex flex-col items-start gap-2" data-testid="brief-poll-error">
        <p className="text-sm text-destructive">{pollError}</p>
        <Button variant="outline" size="sm" onClick={onRetry} data-testid="brief-retry">
          重试
        </Button>
      </div>
    );
  }

  // 完成 / 待核实：展示 content + 事实回链 + 免责
  if ((isDone || isVerify) && view?.content) {
    return (
      <div className="flex flex-col gap-4" data-testid="brief-result">
        <div className="flex items-center gap-2">
          <BriefStatusBadge status={view.status} />
          <DisclaimerBadge text={view.disclaimer} />
        </div>
        <BriefContentCard content={view.content} needVerify={isVerify} />
        <SourceLinkList
          facts={view.content.facts ?? []}
          sourceLinks={view.sourceLinks}
        />
      </div>
    );
  }

  // 兜底（理论上不会到达）
  return (
    <div className="py-10 text-center text-sm text-muted-foreground" data-testid="brief-unknown">
      暂无可展示内容
    </div>
  );
}

export default AiBrief;
