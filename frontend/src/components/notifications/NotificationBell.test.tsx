// 通知中心测试（P1-1）：SSE 生命周期（EventSource mock：连接/重连退避/登出断开/history 兜底）
// + 铃铛面板交互（未读徽章/全部已读/清空/跳转/三态/空态/失败重试）。
// jsdom 无原生 EventSource——统一 stub MockEventSource；history 兜底走 fetch stub。

import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { NotificationBell } from '@/components/notifications/NotificationBell';
import { NotificationProvider } from '@/components/notifications/NotificationProvider';
import type { PushEventPayload } from '@/types/notification';

/** 受控 EventSource mock：记录实例、监听器与关闭态，供用例手动驱动 open/error/事件。 */
class MockEventSource {
  static instances: MockEventSource[] = [];
  url: string;
  closed = false;
  onopen: ((ev?: unknown) => void) | null = null;
  onerror: ((ev?: unknown) => void) | null = null;
  private listeners = new Map<string, Array<(ev: unknown) => void>>();

  constructor(url: string) {
    this.url = url;
    MockEventSource.instances.push(this);
  }

  addEventListener(type: string, callback: (ev: unknown) => void): void {
    const list = this.listeners.get(type) ?? [];
    list.push(callback);
    this.listeners.set(type, list);
  }

  close(): void {
    this.closed = true;
  }

  simulateOpen(): void {
    this.onopen?.({});
  }

  simulateError(): void {
    this.onerror?.({});
  }

  /** 模拟后端命名事件（data=JSON 字符串，lastEventId=push_record.id）。 */
  dispatch(type: string, payload: PushEventPayload, lastEventId: string): void {
    for (const cb of this.listeners.get(type) ?? []) {
      cb({ data: JSON.stringify(payload), lastEventId });
    }
  }
}

function anomalyPayload(overrides: Partial<PushEventPayload> = {}): PushEventPayload {
  return {
    type: 'anomaly',
    subjectId: 1,
    subjectCode: 'SH600519',
    refId: '42',
    content: '日涨跌幅 5.00% 触发阈值 3.00%',
    ...overrides,
  };
}

/** history 兜底响应（最近 N 条）。 */
function historyResponse(items: Array<Record<string, unknown>> = []) {
  return {
    ok: true,
    status: 200,
    json: async () => ({ code: 0, msg: 'ok', data: { items, nextCursor: null }, traceId: 't' }),
  } as unknown as Response;
}

function setupEnvironment({ historyItems = [] as Array<Record<string, unknown>> } = {}) {
  localStorage.setItem('access_token', 'jwt-x');
  const fetchMock = vi.fn(async (_url: string) => historyResponse(historyItems));
  vi.stubGlobal('fetch', fetchMock);
  vi.stubGlobal('EventSource', MockEventSource as unknown as typeof EventSource);
  const renderBell = () =>
    render(
      <NotificationProvider>
        <NotificationBell />
      </NotificationProvider>,
    );
  return { fetchMock, renderBell };
}

beforeEach(() => {
  MockEventSource.instances = [];
});

afterEach(() => {
  vi.useRealTimers();
  vi.unstubAllGlobals();
  cleanup();
  localStorage.clear();
  window.location.hash = '';
});

describe('SSE 连接生命周期（useNotificationStream）', () => {
  it('登录后建立连接：EventSource 指向 stream 端点且 token 走查询参数', () => {
    const { renderBell } = setupEnvironment();
    renderBell();

    expect(MockEventSource.instances).toHaveLength(1);
    expect(MockEventSource.instances[0].url).toBe(
      '/api/v1/notifications/stream?access_token=jwt-x',
    );
  });

  it('onopen → 在线态，且 history 兜底拉取最近记录（面板未开也预取）', async () => {
    const { fetchMock, renderBell } = setupEnvironment();
    renderBell();
    MockEventSource.instances[0].simulateOpen();

    await waitFor(() =>
      expect(
        fetchMock.mock.calls.some((call) => String(call[0]).includes('/notifications?latest=20')),
      ).toBe(true),
    );
  });

  it('断线自动重连：关闭旧连接，指数退避后重建（1s 首退避）', async () => {
    vi.useFakeTimers();
    const { renderBell } = setupEnvironment({ historyItems: [] });
    renderBell();
    const first = MockEventSource.instances[0];

    first.simulateError();
    expect(first.closed).toBe(true);

    // 未到退避时间不重连
    vi.advanceTimersByTime(500);
    expect(MockEventSource.instances).toHaveLength(1);

    vi.advanceTimersByTime(600);
    expect(MockEventSource.instances).toHaveLength(2);
    expect(MockEventSource.instances[1].url).toContain('access_token=jwt-x');

    // 二次失败退避翻倍（2s）：1s 内不重建
    MockEventSource.instances[1].simulateError();
    vi.advanceTimersByTime(1000);
    expect(MockEventSource.instances).toHaveLength(2);
    vi.advanceTimersByTime(1100);
    expect(MockEventSource.instances).toHaveLength(3);
  });

  it('重连成功后补拉 history（断线期间丢失的推送经兜底进面板）', async () => {
    vi.useFakeTimers();
    const { fetchMock, renderBell } = setupEnvironment({
      historyItems: [
        {
          id: 77,
          type: 'anomaly',
          subjectId: 1,
          subjectCode: 'SH600519',
          refId: '9',
          content: '断线期间的异动',
          status: 1,
          pushedAt: '2026-09-22T01:00:00Z',
          createdAt: '2026-09-22T01:00:00Z',
        },
      ],
    });
    renderBell();
    MockEventSource.instances[0].simulateError();

    // 退避 1s 后重连，新连接 open 触发 history 补拉
    vi.advanceTimersByTime(1100);
    expect(MockEventSource.instances).toHaveLength(2);
    MockEventSource.instances[1].simulateOpen();
    vi.useRealTimers(); // 重连已发生，恢复真实时钟供异步断言

    await waitFor(() =>
      expect(
        fetchMock.mock.calls.some((call) => String(call[0]).includes('/notifications?latest=20')),
      ).toBe(true),
    );
    fireEvent.click(screen.getByTestId('notification-bell'));
    expect(await screen.findByTestId('notification-item-77')).toBeInTheDocument();
    expect(screen.getByText('断线期间的异动')).toBeInTheDocument();
  });

  it('登出（Provider 卸载）→ 断开连接且不再重连', async () => {
    vi.useFakeTimers();
    const { renderBell } = setupEnvironment();
    const { unmount } = renderBell();
    const first = MockEventSource.instances[0];

    unmount();
    expect(first.closed).toBe(true);

    // 卸载后定时器已清理：推进任意时间无新连接
    vi.advanceTimersByTime(60_000);
    expect(MockEventSource.instances).toHaveLength(1);
  });

  it('命名事件入列：anomaly 推送进入面板并计未读（comment 心跳帧不触发 onmessage 语义）', async () => {
    const { renderBell } = setupEnvironment();
    renderBell();
    fireEvent.click(screen.getByTestId('notification-bell'));

    MockEventSource.instances[0].dispatch('anomaly', anomalyPayload(), '101');

    expect(await screen.findByTestId('notification-item-101')).toBeInTheDocument();
    expect(screen.getByTestId('notification-unread-badge')).toHaveTextContent('1');
    // 异动条目展示类型徽章与标的代码
    expect(screen.getByText('异动')).toBeInTheDocument();
    expect(screen.getByText('SH600519')).toBeInTheDocument();
    expect(screen.getByText(/日涨跌幅 5\.00%/)).toBeInTheDocument();
  });
});

describe('通知铃铛与面板交互', () => {
  it('面板打开拉 history 兜底；未读徽章随水位推进', async () => {
    const user = userEvent.setup();
    const { renderBell } = setupEnvironment({
      historyItems: [
        {
          id: 5,
          type: 'policy',
          subjectId: null,
          subjectCode: null,
          refId: null,
          content: '新政策发布',
          status: 1,
          pushedAt: null,
          createdAt: '2026-09-22T02:00:00Z',
        },
      ],
    });
    renderBell();
    await user.click(screen.getByTestId('notification-bell'));

    expect(await screen.findByTestId('notification-item-5')).toBeInTheDocument();
    expect(screen.getByTestId('notification-unread-badge')).toHaveTextContent('1');
    expect(screen.getByText('政策')).toBeInTheDocument();
  });

  it('全部已读：徽章清零，条目保留', async () => {
    const user = userEvent.setup();
    const { renderBell } = setupEnvironment();
    renderBell();
    MockEventSource.instances[0].dispatch('anomaly', anomalyPayload(), '101');
    await user.click(screen.getByTestId('notification-bell'));
    expect(await screen.findByTestId('notification-item-101')).toBeInTheDocument();
    expect(screen.getByTestId('notification-unread-badge')).toBeInTheDocument();

    await user.click(screen.getByTestId('notification-mark-all'));

    expect(screen.queryByTestId('notification-unread-badge')).toBeNull();
    expect(screen.getByTestId('notification-item-101')).toBeInTheDocument();
  });

  it('清空：列表空态文案，未读不复活（水位推进）', async () => {
    const user = userEvent.setup();
    const { renderBell } = setupEnvironment();
    renderBell();
    MockEventSource.instances[0].dispatch('anomaly', anomalyPayload(), '101');
    await user.click(screen.getByTestId('notification-bell'));
    await screen.findByTestId('notification-item-101');

    await user.click(screen.getByTestId('notification-clear'));

    expect(await screen.findByTestId('notification-empty')).toBeInTheDocument();
    expect(screen.getByTestId('notification-empty')).toHaveTextContent('暂无通知');
  });

  it('条目点击：已读推进 + 跳转标的详情 + 面板关闭（水位只进不退）', async () => {
    const user = userEvent.setup();
    const { renderBell } = setupEnvironment();
    renderBell();
    MockEventSource.instances[0].dispatch('anomaly', anomalyPayload(), '101');
    MockEventSource.instances[0].dispatch('anomaly', anomalyPayload({ refId: '43' }), '102');
    await user.click(screen.getByTestId('notification-bell'));
    expect(await screen.findByTestId('notification-unread-badge')).toHaveTextContent('2');

    // 先点较旧的 101：水位=101，较新的 102 仍未读（剩 1）
    await user.click(screen.getByTestId('notification-item-101'));
    expect(window.location.hash).toBe('#/subjects/SH600519');
    expect(screen.queryByTestId('notification-panel')).toBeNull(); // 面板关闭
    expect(screen.getByTestId('notification-unread-badge')).toHaveTextContent('1');

    // 再点最新的 102：水位=102，全部已读（徽章消失）
    await user.click(screen.getByTestId('notification-bell'));
    await user.click(await screen.findByTestId('notification-item-102'));
    expect(screen.queryByTestId('notification-unread-badge')).toBeNull();
  });

  it('无 subjectCode 的条目不可点击（不跳转）', async () => {
    const user = userEvent.setup();
    const { renderBell } = setupEnvironment();
    renderBell();
    MockEventSource.instances[0].dispatch(
      'policy',
      { type: 'policy', subjectId: null, subjectCode: null, refId: null, content: '政策速递' },
      '103',
    );
    await user.click(screen.getByTestId('notification-bell'));
    const row = await screen.findByTestId('notification-item-103');

    expect(row).toBeDisabled();
  });

  it('连接三态指示：connecting / online / offline', async () => {
    const user = userEvent.setup();
    const { renderBell } = setupEnvironment();
    renderBell();
    await user.click(screen.getByTestId('notification-bell'));

    expect(screen.getByTestId('notification-status')).toHaveTextContent('连接中');
    MockEventSource.instances[0].simulateOpen();
    await waitFor(() =>
      expect(screen.getByTestId('notification-status')).toHaveTextContent('在线'),
    );
    MockEventSource.instances[0].simulateError();
    await waitFor(() =>
      expect(screen.getByTestId('notification-status')).toHaveTextContent('离线'),
    );
  });

  it('history 拉取失败：面板内错误提示 + 重试成功恢复', async () => {
    const user = userEvent.setup();
    localStorage.setItem('access_token', 'jwt-x');
    let fail = true;
    const fetchMock = vi.fn(async () => {
      if (fail) {
        return {
          ok: false,
          status: 500,
          json: async () => ({ code: 50000, msg: '服务异常', data: null, traceId: 't' }),
        } as unknown as Response;
      }
      return historyResponse([
        {
          id: 9,
          type: 'anomaly',
          subjectId: 1,
          subjectCode: 'SH600519',
          refId: '9',
          content: '重试后到达',
          status: 1,
          pushedAt: null,
          createdAt: '2026-09-22T03:00:00Z',
        },
      ]);
    });
    vi.stubGlobal('fetch', fetchMock);
    vi.stubGlobal('EventSource', MockEventSource as unknown as typeof EventSource);
    render(
      <NotificationProvider>
        <NotificationBell />
      </NotificationProvider>,
    );

    await user.click(screen.getByTestId('notification-bell'));
    expect(await screen.findByTestId('notification-retry')).toBeInTheDocument();

    fail = false;
    await user.click(screen.getByTestId('notification-retry'));
    expect(await screen.findByTestId('notification-item-9')).toBeInTheDocument();
    expect(screen.getByText('重试后到达')).toBeInTheDocument();
  });

  it('空态：无任何通知显示空态文案（而非错误）', async () => {
    const user = userEvent.setup();
    const { renderBell } = setupEnvironment({ historyItems: [] });
    renderBell();
    await user.click(screen.getByTestId('notification-bell'));

    expect(await screen.findByTestId('notification-empty')).toBeInTheDocument();
    expect(screen.queryByTestId('notification-retry')).toBeNull();
  });
});
