// 认证数据适配层（对齐技术方案 §4.1 认证段 + 后端 AuthController）。
// POST /api/v1/auth/login：username+password → accessToken/refreshToken；
//   凭证错误 1001(401) / 限流 1002(429) / 令牌无效 1003(401)。
// 登录端点在 JWT 白名单内，auth=false 不触发 401 跳转（401 在此是合法业务错误）。

import { clearToken, getToken, request, setToken } from './http';
import type { LoginResponse } from '@/types/api';

/**
 * 登录：成功后把 accessToken 写入 localStorage（key=access_token），
 * 供受保护接口（聚合 / watchlist）自动携带。
 * @throws ApiError 凭证错误(1001) / 限流(1002)
 */
export async function login(username: string, password: string): Promise<LoginResponse> {
  const data = await request<LoginResponse>('/auth/login', {
    method: 'POST',
    body: { username, password },
    auth: false,
  });
  setToken(data.accessToken);
  return data;
}

/** 登出：清除本地令牌（后端为无状态 JWT，不调服务端）。 */
export function logout(): void {
  clearToken();
}

export { getToken };
