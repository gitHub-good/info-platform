# 🖼️ 前端 · SPA

> React + TypeScript + shadcn/ui + Tailwind，Vite 构建（暗色主题）。

## 📄 页面

| 页面 | 对应后端接口 | 任务 |
| --- | --- | --- |
| 标的详情聚合页 | `GET /api/v1/subjects/{id}/detail` | T10 |
| watchlist 管理页 | `/api/v1/watchlists/*` | T12 |
| AI 简报页 | `POST /api/v1/ai-briefs`（异步轮询 + SSE） | T22 |
| 政策时事页 | `GET /api/v1/policies/*` | T25 |
| 个人信息流/订阅页 | `/api/v1/subscriptions` `/api/v1/feed/personal` | T27 |

## 📡 实时通知

- SSE 长连接：`GET /api/v1/notifications/stream`
- 断线自动重连 + 按 `lastEventId` 补拉 `GET /api/v1/notifications`

## 🚀 命令

```bash
npm install        # 安装依赖
npm run dev        # 本地开发（Vite）
npm run build      # 类型检查 + 构建（tsc -b && vite build）
npm run test       # 单元测试（Vitest + jsdom + React Testing Library）
npm run test:watch # 测试监听
npm run lint       # oxlint
```

## 📐 规范

- 统一响应体 `{ code, msg, data, traceId }`，错误码见技术方案 §4.1
- 所有 AI 生成内容展示“非投资建议”免责标识
- 标的详情页单源缺失显示“暂无数据”不阻断其他分区
- 暗色模式：`<html class="dark">` 切换 shadcn 暗色 token

## 🧪 T10 · 标的详情聚合页

- 页面：`src/pages/SubjectDetail.tsx`，组件在 `src/components/subject/`
- mock 数据：`src/mocks/subject-detail-mock.ts`（4 ok / 1 missing / 1 failed，验证降级 UI）
- 数据适配层：`src/api/subject.ts` 的 `adapter.mock.enabled` —— 联调日切 `false` 即走真实聚合接口（自动带 `Authorization: Bearer <token>`）
