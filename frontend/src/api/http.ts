// 统一 HTTP 请求层（对齐前端基线「请求层统一封装」）。
// - 业务代码不直接裸调 fetch，统一经 request()。
// - 自动注入 Authorization: Bearer（受 JWT 保护端点）。
// - 统一解析 { code, msg, data, traceId }，code !== 0 抛 ApiError。
// - 受保护端点返回 401（令牌缺失/无效/过期）→ 清 token + 跳登录页。
// base URL 取 VITE_API_BASE_URL，默认 '/api/v1'（本地经 Vite proxy 转发到后端 8080）。

import { navigate } from '@/lib/navigation';
import type { ApiResponse } from '@/types/api';

export const API_BASE_URL: string = import.meta.env.VITE_API_BASE_URL ?? '/api/v1';

/** localStorage 键（与 T10 subject.ts 共用，登录后聚合接口自动带同一 token）。 */
const TOKEN_KEY = 'access_token';

/** 读取 access_token；localStorage 不可用时按未登录处理。 */
export function getToken(): string | null {
  try {
    return localStorage.getItem(TOKEN_KEY);
  } catch {
    return null;
  }
}

export function setToken(token: string): void {
  try {
    localStorage.setItem(TOKEN_KEY, token);
  } catch {
    // 隐私模式 / SSR：静默忽略，鉴权退化为未登录
  }
}

export function clearToken(): void {
  try {
    localStorage.removeItem(TOKEN_KEY);
  } catch {
    // 同上
  }
}

/**
 * 统一业务错误：携带后端错误码（1xxx/2xxx/3xxx/5xxx）、HTTP 状态码与 traceId，
 * 供调用方按 code 分支展示友好提示。
 */
export class ApiError extends Error {
  code: number;
  msg: string;
  httpStatus: number;
  traceId?: string;

  constructor(code: number, msg: string, httpStatus: number, traceId?: string) {
    super(msg);
    this.name = 'ApiError';
    this.code = code;
    this.msg = msg;
    this.httpStatus = httpStatus;
    this.traceId = traceId;
  }
}

export interface RequestOptions {
  method?: string;
  /** 请求体（自动 JSON 序列化）。 */
  body?: unknown;
  /** 额外请求头，如 Idempotency-Key。 */
  headers?: Record<string, string>;
  /** 是否携带 Bearer 并在 401 时跳登录（默认 true）；登录/换发端点传 false。 */
  auth?: boolean;
  signal?: AbortSignal;
}

/** 解析响应体为统一响应结构；响应非 JSON 时兜底为服务异常。 */
async function parseResult(res: Response): Promise<ApiResponse<unknown>> {
  try {
    return (await res.json()) as ApiResponse<unknown>;
  } catch {
    throw new ApiError(50000, `服务响应无法解析（HTTP ${res.status}）`, res.status);
  }
}

/**
 * 统一请求入口：返回后端 data，失败抛 ApiError。
 * 受保护端点（auth !== false）返回 401 时清 token 并跳 /login。
 */
export async function request<T>(path: string, options: RequestOptions = {}): Promise<T> {
  const {
    method = 'GET',
    body,
    headers = {},
    auth = true,
    signal,
  } = options;

  const url = path.startsWith('http') ? path : `${API_BASE_URL}${path}`;
  const finalHeaders: Record<string, string> = { Accept: 'application/json', ...headers };
  if (auth) {
    const token = getToken();
    if (token) finalHeaders.Authorization = `Bearer ${token}`;
  }
  if (body !== undefined) finalHeaders['Content-Type'] = 'application/json';

  let res: Response;
  try {
    res = await fetch(url, {
      method,
      headers: finalHeaders,
      body: body !== undefined ? JSON.stringify(body) : undefined,
      signal,
    });
  } catch (err) {
    throw new ApiError(
      50000,
      `网络请求失败：${err instanceof Error ? err.message : String(err)}`,
      0,
    );
  }

  const parsed = await parseResult(res);
  if (!res.ok || parsed.code !== 0) {
    // 受保护端点 401 = 令牌缺失/无效/过期 → 失效后跳登录
    if (res.status === 401 && auth) {
      clearToken();
      navigate('/login');
    }
    throw new ApiError(
      parsed.code ?? 50000,
      parsed.msg ?? '服务异常',
      res.status,
      parsed.traceId,
    );
  }
  return parsed.data as T;
}
