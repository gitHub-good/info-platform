# 🖼️ 前端 · SPA

> React 18 + TypeScript + shadcn/ui，Vite 构建。

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

## 🚀 初始化（待执行）

```bash
npm create vite@latest . -- --template react-ts
npx shadcn@latest init
```

## 📐 规范

- 统一响应体 `{ code, msg, data, traceId }`，错误码见技术方案 §4.1
- 所有 AI 生成内容展示"非投资建议"免责标识
- 标的详情页单源缺失显示"暂无数据"不阻断其他分区
