// 统一响应体契约（对齐技术方案 §4.1：{ code, msg, data, traceId }）。
// code=0 成功；1xxx 认证 / 2xxx 参数 / 3xxx 业务 / 5xxx 服务端。
// 与 subject-detail.ts 中的 ApiResponse 同形；新模块以此处为权威。

export interface ApiResponse<T> {
  code: number;
  msg: string;
  data: T;
  traceId?: string;
}

/** POST /api/v1/auth/login 成功响应的 data。 */
export interface LoginResponse {
  accessToken: string;
  refreshToken: string;
  tokenType: string;
  expiresIn: number;
}
