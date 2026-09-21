import { useState, type FormEvent } from 'react';
import { Button } from '@/components/ui/button';
import { Card, CardContent, CardHeader, CardTitle, CardDescription } from '@/components/ui/card';
import { Input } from '@/components/ui/input';
import { ApiError } from '@/api/http';
import { login } from '@/api/auth';
import { navigate } from '@/lib/navigation';

const DEMO_HINT = '演示账号：admin / admin123';

/** 把后端认证错误码映射成友好文案。 */
function loginErrorMessage(err: unknown): string {
  if (err instanceof ApiError) {
    if (err.code === 1001) return '用户名或密码错误';
    if (err.code === 1002) return '登录尝试过于频繁，请稍后再试';
    return err.msg;
  }
  return '登录失败，请稍后重试';
}

/**
 * 登录页（技术方案 §4.1 认证段 + T12）。
 * 用户名 + 密码 → POST /api/v1/auth/login → 成功存 accessToken 跳 /watchlists；
 * 凭证错误 1001(401) / 限流 1002(429) 展示对应提示。
 */
export function Login() {
  const [username, setUsername] = useState('');
  const [password, setPassword] = useState('');
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const submit = async (e: FormEvent) => {
    e.preventDefault();
    const u = username.trim();
    const p = password;
    if (!u || !p || submitting) return;
    setSubmitting(true);
    setError(null);
    try {
      await login(u, p);
      navigate('/watchlists');
    } catch (err) {
      setError(loginErrorMessage(err));
    } finally {
      setSubmitting(false);
    }
  };

  return (
    <main className="mx-auto flex min-h-screen w-full max-w-sm flex-col justify-center p-4">
      <Card data-testid="login-card">
        <CardHeader>
          <CardTitle>登录</CardTitle>
          <CardDescription>{DEMO_HINT}</CardDescription>
        </CardHeader>
        <CardContent>
          <form className="flex flex-col gap-3" onSubmit={submit} data-testid="login-form">
            <label className="flex flex-col gap-1 text-sm">
              <span>用户名</span>
              <Input
                value={username}
                onChange={(e) => setUsername(e.target.value)}
                placeholder="admin"
                autoComplete="username"
                data-testid="login-username"
              />
            </label>
            <label className="flex flex-col gap-1 text-sm">
              <span>密码</span>
              <Input
                type="password"
                value={password}
                onChange={(e) => setPassword(e.target.value)}
                placeholder="••••••"
                autoComplete="current-password"
                data-testid="login-password"
              />
            </label>
            {error ? (
              <p className="text-sm text-destructive" data-testid="login-error" role="alert">
                {error}
              </p>
            ) : null}
            <Button type="submit" disabled={submitting} data-testid="login-submit">
              {submitting ? '登录中…' : '登录'}
            </Button>
          </form>
        </CardContent>
      </Card>
    </main>
  );
}

export default Login;
