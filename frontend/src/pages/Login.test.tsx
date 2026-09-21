import { cleanup, render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { Login } from '@/pages/Login';

/** 构造类 Response 的 mock（只暴露 request() 用到的 ok/status/json）。 */
function mockResponse(status: number, body: unknown) {
  return {
    ok: status >= 200 && status < 300,
    status,
    json: async () => body,
  } as unknown as Response;
}

afterEach(() => {
  vi.unstubAllGlobals();
  cleanup();
  localStorage.clear();
  window.location.hash = '';
});

async function fillAndSubmit(user: ReturnType<typeof userEvent.setup>, username: string, password: string) {
  render(<Login />);
  await user.type(screen.getByTestId('login-username'), username);
  await user.type(screen.getByTestId('login-password'), password);
  await user.click(screen.getByTestId('login-submit'));
}

describe('Login 登录页', () => {
  it('登录成功：POST /auth/login 存 token 并跳转 /watchlists', async () => {
    const fetchMock = vi.fn().mockResolvedValue(
      mockResponse(200, {
        code: 0,
        msg: 'ok',
        traceId: 't1',
        data: { accessToken: 'jwt-abc', refreshToken: 'r', tokenType: 'Bearer', expiresIn: 3600 },
      }),
    );
    vi.stubGlobal('fetch', fetchMock);

    const user = userEvent.setup();
    await fillAndSubmit(user, 'admin', 'admin123');

    // 断言请求：POST /auth/login，body 含 username/password，未带 Authorization（白名单）
    await waitFor(() => expect(fetchMock).toHaveBeenCalled());
    const [url, init] = fetchMock.mock.calls[0] as [string, RequestInit];
    expect(url).toContain('/auth/login');
    expect(init.method).toBe('POST');
    expect(JSON.parse(init.body as string)).toEqual({ username: 'admin', password: 'admin123' });
    expect(init.headers).not.toHaveProperty('Authorization');

    // 成功：token 落 localStorage，路由跳 /watchlists
    await waitFor(() => expect(localStorage.getItem('access_token')).toBe('jwt-abc'));
    expect(window.location.hash).toBe('#/watchlists');
  });

  it('凭证错误 1001(401)：展示错误且不存 token、不跳转', async () => {
    const fetchMock = vi.fn().mockResolvedValue(
      mockResponse(401, { code: 1001, msg: '凭证错误', traceId: 't2', data: null }),
    );
    vi.stubGlobal('fetch', fetchMock);

    const user = userEvent.setup();
    await fillAndSubmit(user, 'admin', 'wrong');

    expect(await screen.findByTestId('login-error')).toHaveTextContent('用户名或密码错误');
    expect(localStorage.getItem('access_token')).toBeNull();
    expect(window.location.hash).not.toContain('watchlists');
  });

  it('限流 1002(429)：展示限流提示', async () => {
    const fetchMock = vi.fn().mockResolvedValue(
      mockResponse(429, {
        code: 1002,
        msg: '登录尝试过于频繁，请稍后再试',
        traceId: 't3',
        data: null,
      }),
    );
    vi.stubGlobal('fetch', fetchMock);

    const user = userEvent.setup();
    await fillAndSubmit(user, 'admin', 'x');

    expect(await screen.findByTestId('login-error')).toHaveTextContent(
      '登录尝试过于频繁，请稍后再试',
    );
  });
});
