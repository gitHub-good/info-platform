# 🔍 Spike-2 · LLM 网关调研

> 选定 LLM 厂商+模型，验证结构化输出（JSON mode）与数值校验可行性，测出单次成本与延迟基线，产出厂商选型对比、提示词 v1 模板与各任务契约要点，供 T19~T21/T23/T28 实现与用户拍板厂商。

- 调研性质：时间盒 ≤2 人天，**不写实现代码**，结论以厂商公开 API 文档为据；延迟/真实成本基线需用户提供 API key 后实测，本报告基于公开定价估算。
- 作者 / 日期：架构设计师 / 2026-09-21
- 关联：[技术方案-信息整合平台.md](/docs/02-设计/技术方案-信息整合平台.md) §4.4 LLM 网关、§4.3 流程 2（AI 简报异步生成+事实回链+数值校验）、§8 Spike-2 计划、ADR-0004（自建薄抽象）、ADR-0008（配置驱动多 provider + fallback）
- 上游需求：[REQ-20260920-01-信息整合平台.md](/docs/01-需求/REQ-20260920-01-信息整合平台.md) 故事 2 · AI 分析与推荐

## 🎯 0. 结论速览（一句话选型）

| 厂商 | 默认/备选 | 推荐模型 | 形态 / 调用路径 | 成本量级 | JSON mode | 关键判断 |
| --- | --- | --- | --- | --- | --- | --- |
| **DeepSeek** | **默认** | `deepseek-flash`（DeepSeek-V4.1-Flash） | OpenAI 兼容 REST，`https://api.deepseek.com/chat/completions` | ~0.007 元/次（空闲） | 🟢 已确认 | 性价比档 + JSON mode 稳 + 1M 上下文 + 缓存命中折扣 |
| **GLM/智谱** | **备选 fallback** | `glm-4-flash-250414`（免费档） | OpenAI 兼容 REST，`https://open.bigmodel.cn/api/paas/v4/chat/completions` | **0 元/次（免费档）** | 🟡 需实测确认 | 免费兜底降本 + 国产合规；重负载可切 `glm-4-plus` |
| 通义千问 | 预留 | `qwen-plus` / `qwen-turbo` | OpenAI 兼容，`https://dashscope.aliyuncs.com/compatible-mode/v1` | 低 | 🟢 支持 response_format | enabled=false 按需启用 |
| 文心一言 | 预留 | `ernie-4.0` / `ernie-speed` | 自有协议 + 兼容层 | 低 | 🟡 依赖兼容层 | enabled=false 按需启用 |
| Kimi/Moonshot | 预留 | `moonshot-v1-8k` | OpenAI 兼容 | 中 | 🟢 支持 | 长文本场景候选 |

**核心结论**：DeepSeek 与 GLM 均 OpenAI 兼容（`messages`/`model`/`temperature`/`response_format`/`choices[0].message.content`/`usage`），Java 可用 Spring `RestClient` 直调、**无 SDK 依赖**。两家均为**国内厂商**（DeepSeek 杭州深度求索、GLM 北京智谱），**无跨境合规顾虑**。DeepSeek 性价比高且 JSON mode 文档明确，推荐默认；GLM 免费 flash 模型作 fallback 降本兜底。技术方案 ADR-0008 的"配置驱动多 provider + fallback"完全可行，无需引入 LiteLLM。

> ⚠️ **重要澄清**：技术方案 §4.4 与 ADR-0008 的 `application.yml` 写的模型名 `deepseek-chat` / `glm-4` 已停产/迭代。本调研按 2026-09 厂商文档更新为 `deepseek-flash` / `glm-4-flash-250414`（详见 §8 配置示例）。模型名为配置项，T19 落地时直接用本报告的值，无需改 ADR。

## 📐 1. 调研方法与置信度标注

- **方法**：用 WebFetch 抓取 DeepSeek / GLM 官方 API 文档与定价页，按端点/认证/定价/JSON mode/上下文/限频/Java 可调用性记录。
- **置信度**：
  - 🟢 高 = WebFetch 到官方文档明确确认，可直接落地。
  - 🟡 中 = 文档间接确认或 OpenAI 兼容性推断，需 T19 实现期二次验证（如 GLM 的 `response_format` 参数）。
  - 🔴 低 = 定价页 JS 渲染不可抓取或端点待验证，列跟进。
- **评分维度**（对齐 [02 设计架构 · 选型五维度](/project-development/02-design/index.md#tech-evaluation)）：成熟度 / 社区生态 / 学习成本 / 可替换性 / 运维成本，1-5 分高优；另加 **定价量级** 与 **Java 可调用性** 两列。

## 📊 2. 厂商候选对比表

### 2.1 DeepSeek（深度求索）

| 维度 | 取值 | 置信 |
| --- | --- | --- |
| API 端点 | `https://api.deepseek.com/chat/completions`（OpenAI 兼容，base-url 配 `https://api.deepseek.com`） | 🟢 |
| 认证 | `Authorization: Bearer ${DEEPSEEK_API_KEY}` | 🟢 |
| 可用模型 | `deepseek-flash`（DeepSeek-V4.1-Flash，性价比）/ `deepseek-v4-pro`（DeepSeek-V4-Pro-0813，重负载） | 🟢 |
| 上下文窗口 | 1M tokens（两款均 1M）；最大输出 384K | 🟢 |
| 定价·flash（空闲时段） | 输入缓存未命中 1 元/百万 token / 输出 4 元/百万 token；缓存命中 0.02 元/百万 token | 🟢 |
| 定价·flash（高峰时段） | 工作日 9:00-12:00 / 14:00-18:00：输入未命中 2 元 / 输出 8 元（空闲价 ×2） | 🟢 |
| 定价·v4-pro | 输入未命中 4.5 元 / 输出 13.5 元（空闲）；缓存命中 0.15 元/百万输入 | 🟢 |
| 限频 | **按账号并发**：flash 2500 并发 / v4-pro 500 并发；超限返回 429；可提工单扩容 | 🟢 |
| JSON mode | 🟢 支持 `response_format={"type":"json_object"}`；**强制要求** system 或 user prompt 含 "json" 字样；声称保证合法 JSON；**有概率返回空 content**（需兜底）；需设 `max_tokens` 防截断 | 🟢 |
| Function calling | 支持（tools，独立文档页） | 🟢 |
| usage 计费字段 | `prompt_tokens` / `completion_tokens` / `total_tokens` / `prompt_cache_hit_tokens`（缓存命中，折扣）/ `prompt_cache_miss_tokens` / `reasoning_tokens`（思维链） | 🟢 |
| Java 可调用性 | ✅ HTTP REST 直调，OpenAI 兼容请求/响应体，Spring RestClient 无 SDK | 🟢 |
| 成熟度 | 5 | — |
| 社区生态 | 5 | — |
| 学习成本 | 5（OpenAI 兼容，文档全） | — |
| 可替换性 | 5（provider adapter，切厂商改配置） | — |
| 运维成本 | 4（仅管 api-key + 监控成本） | — |

### 2.2 GLM / 智谱（BigModel）

| 维度 | 取值 | 置信 |
| --- | --- | --- |
| API 端点 | `https://open.bigmodel.cn/api/paas/v4/chat/completions`（base-url 配 `.../paas/v4`） | 🟢 |
| 认证 | `Authorization: Bearer ${GLM_API_KEY}`（已弃用旧的 JWT 签名，现标准 Bearer） | 🟢 |
| 可用模型（2026-09） | 文本模型：`glm-5.3`(1M) / `glm-4.7`(200K) / `glm-4.7-flash`(200K,**免费**) / `glm-4.5-flash`(128K,**免费**) / `glm-4-flash-250414`(128K,**免费**) / `glm-4-long`(1M) / `glm-4.5-air`(128K) | 🟢 |
| 免费模型 | `glm-4-flash-250414`、`glm-4.5-flash`、`glm-4.7-flash` 标注为"免费文本模型"，128K~200K 上下文 | 🟢 |
| 上下文窗口 | flash 档 128K~200K；glm-4-long/glm-5.3 1M | 🟢 |
| 定价（付费档） | 定价页 `open.bigmodel.cn/pricing` 为 JS 渲染，WebFetch 不可抓；glm-4-plus 等付费档单价需用户在控制台确认实时定价 | 🔴 |
| 限频 | 文档未在可抓页面暴露 RPM/TPM 数值；免费模型通常有较低 RPD 限制，需控制台确认 | 🔴 |
| JSON mode | 🟡 文档确认 OpenAI 兼容参数（`messages`/`model`/`temperature`/`stream`）+ 支持 `tools` function_calling（Agent 能力）；`response_format` 参数按 OpenAI 兼容性**应支持**，但未在可抓页面直接确认 → **T19 实现期实测验证**，不通过则用 §4 备选方案 | 🟡 |
| usage 计费字段 | `usage`（官方说明"以返回 usage 为准"），含 token 统计；免费模型 cost=0 | 🟢 |
| Java 可调用性 | ✅ HTTP REST 直调，OpenAI 兼容；另有 `ai.z.openapi:zai-sdk`（非必需） | 🟢 |
| 成熟度 | 5 | — |
| 社区生态 | 5（国产头部，政企合作多） | — |
| 学习成本 | 5（OpenAI 兼容） | — |
| 可替换性 | 5 | — |
| 运维成本 | 4 | — |

### 2.3 预留厂商（通义/文心/Kimi，enabled=false）

| 厂商 | 端点（OpenAI 兼容） | 模型 | JSON mode | 说明 |
| --- | --- | --- | --- | --- |
| 通义千问 | `https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions` | `qwen-plus` / `qwen-turbo` | 🟢 支持 `response_format` | 阿里云，备案齐全 |
| 文心一言 | 兼容层 `/v1/chat/completions` | `ernie-4.0` / `ernie-speed` | 🟡 依赖兼容层 | 百度，自有协议为主 |
| Kimi/Moonshot | `https://api.moonshot.cn/v1/chat/completions` | `moonshot-v1-8k` / `moonshot-v1-32k` | 🟢 支持 | 长上下文场景候选 |

> 预留厂商本期不实现，仅 ADR-0008 配置占位；按需启用时各加一个 provider adapter 即可，不影响主链路。

## ⚖️ 3. 选型建议

**默认 provider：DeepSeek（`deepseek-flash`）**

理由：
1. **性价比**：空闲时段单次简报成本 ~0.007 元（见 §7），缓存命中后更低；flash 档对个人/小团队量级足够。
2. **JSON mode 稳**：`response_format=json_object` 文档明确、前提清晰（prompt 含 json 字样），结构化简报可直接落地。
3. **1M 上下文**：聚合简报需塞入行情+财务+事件+新闻+政策上下文，1M 窗口余量充足，不易触发截断。
4. **缓存命中折扣**：`prompt_cache_hit_tokens` 字段支持按命中计费，重复 system prompt + 聚合上下文重叠部分可享折扣，进一步降本。

**备选/fallback：GLM（`glm-4-flash-250414` 免费档）**

理由：
1. **免费兜底**：flash 档免费，作 fallback 时降本价值高（主 provider 限频/故障切免费档继续出简报）。
2. **国产合规**：智谱（北京）政企合作多，备案齐全；若用户有国产合规偏好可提为默认。
3. **OpenAI 兼容**：与 DeepSeek 同构，adapter 复用度高。

**备选升级路径**：若默认 DeepSeek 质量不足（如政策解读需更强推理），可切 `deepseek-v4-pro` 或 GLM `glm-4-plus`（付费档，定价待控制台确认）。

> 选型遵循"无聊但成熟"原则：两家均为 OpenAI 兼容、文档齐全、Java 直调，不绑定 SDK；可替换性 5 分。被否方案：直连单一厂商（绑定，ADR-0004 已否）、引入 LiteLLM 独立服务（个人量级运维不划算，跟进条件保留）。

## 🔌 4. API 契约（端点 / 请求体 / 响应体 / 认证）

### 4.1 DeepSeek

**请求**：`POST https://api.deepseek.com/chat/completions`

```http
Authorization: Bearer ${DEEPSEEK_API_KEY}
Content-Type: application/json
```
```json
{
  "model": "deepseek-flash",
  "messages": [
    {"role": "system", "content": "...必须含 json 字样..."},
    {"role": "user", "content": "聚合上下文 + 输出格式约束"}
  ],
  "response_format": {"type": "json_object"},
  "temperature": 0.3,
  "max_tokens": 2048,
  "stream": false
}
```

**响应**（关键字段）：
```json
{
  "choices": [{"message": {"role": "assistant", "content": "{...合法JSON字符串...}"}}],
  "usage": {
    "prompt_tokens": 4100,
    "completion_tokens": 820,
    "total_tokens": 4920,
    "prompt_cache_hit_tokens": 1800,
    "prompt_cache_miss_tokens": 2300
  }
}
```

### 4.2 GLM / 智谱

**请求**：`POST https://open.bigmodel.cn/api/paas/v4/chat/completions`

```http
Authorization: Bearer ${GLM_API_KEY}
Content-Type: application/json
```
```json
{
  "model": "glm-4-flash-250414",
  "messages": [
    {"role": "system", "content": "..."},
    {"role": "user", "content": "聚合上下文 + 输出格式约束"}
  ],
  "response_format": {"type": "json_object"},
  "temperature": 0.3,
  "max_tokens": 2048,
  "stream": false
}
```

**响应**（OpenAI 兼容结构）：
```json
{
  "choices": [{"message": {"role": "assistant", "content": "{...合法JSON字符串...}"}}],
  "usage": {"prompt_tokens": 4100, "completion_tokens": 820, "total_tokens": 4920}
}
```

> GLM `response_format` 参数支持性 🟡 待 T19 实测；不通过则请求体去掉该字段，改用 §5 "提示词约束 + 解析兜底"方案。

### 4.3 Java RestClient 调用示例（provider adapter 内部，T19 落地）

```java
// infrastructure/ai/DeepSeekProvider.java（实现 domain/ai 的 LlmGateway 端口）
RestClient client = RestClient.builder()
    .baseUrl(providerConfig.getBaseUrl())            // https://api.deepseek.com
    .defaultHeader("Authorization", "Bearer " + providerConfig.getApiKey())
    .defaultHeader("Content-Type", "application/json")
    .build();

Map<String, Object> body = new LinkedHashMap<>();
body.put("model", providerConfig.getModel());        // deepseek-flash
body.put("messages", messages);                      // List<Map role,content>
body.put("response_format", Map.of("type", "json_object"));
body.put("temperature", 0.3);
body.put("max_tokens", 2048);
body.put("stream", false);

Map<String, Object> resp = client.post()
    .uri("/chat/completions")
    .body(body)
    .retrieve()
    .body(Map.class);
String content = (String) ((Map<?, ?>) ((List<?>) resp.get("choices")).get(0)).get("message").get("content");
Map<String, Object> usage = (Map<String, Object>) resp.get("usage");
```

> 两家 provider adapter 共享同一请求/响应结构，差异仅在 `base-url` / `model` / api-key，T19 可抽 `AbstractOpenAiCompatProvider` 公共基类，DeepSeek/GLM 各覆盖配置即可。

## 🧱 5. 结构化输出方案（JSON mode → AI 简报）

### 5.1 简报 JSON Schema（落 `ai_brief.content` 字段）

```json
{
  "summary": "核心事件摘要，2-3 句",
  "keyEvents": [
    {"event": "事件描述", "impact": "利好|利空|中性", "reason": "判断理由", "sourceUrl": "原文链接"}
  ],
  "bias": "利好|利空|中性",
  "biasReason": "整体倾向判断理由",
  "watchSuggestion": "关注建议（非投资建议，不给买卖时点）",
  "facts": [
    {"claim": "归母净利润同比增长15%", "metric": "net_profit_yoy", "value": 15.0, "source": "FINANCE", "sourceUrl": "..."}
  ],
  "disclaimer": "AI 生成，非投资建议"
}
```

- `facts` 数组供幻觉校验回查（见 §6）：每条含 `metric`（与 T04 财务 adapter 字段键对齐）+ `value` + `source`（数据源枚举）。
- `keyEvents[].sourceUrl` / `facts[].sourceUrl` 供事实回链（回链率 100% 验收）。

### 5.2 JSON mode 可行性验证

| 厂商 | JSON mode | 前提 | 风险 | 兜底 |
| --- | --- | --- | --- | --- |
| DeepSeek | 🟢 `response_format=json_object` | prompt 含 "json" 字样 | **有概率返回空 content**；长输出被 `max_tokens` 截断 | content 为空/非法 → status=2 失败重试 1 次；解析失败标"AI 服务暂不可用"降级 |
| GLM | 🟡 待实测 | OpenAI 兼容应支持 | 参数可能不生效 | 不通过则启用 §5.3 备选 |

### 5.3 备选：提示词约束 + 解析兜底（GLM 不支持 response_format 时）

```text
【输出格式约束】只输出一个合法 JSON 对象，不要输出任何 Markdown 代码块标记、
不要输出解释文字。JSON 顶层字段：summary/keyEvents/bias/biasReason/
watchSuggestion/facts/disclaimer。示例：
{"summary":"...","keyEvents":[...],"bias":"中性","facts":[...],"disclaimer":"AI生成，非投资建议"}
```

解析兜底（provider adapter 内）：`content` 去除可能的 ```json 代码块标记 → `JsonSanitizer` 提取首个 `{` 到末个 `}` → Jackson 反序列化 `BriefContent`；仍失败 → 记 WARN + status=2 + 降级提示。

## 🔎 6. 幻觉校验方案（数值回查）

**目标**：AI 简报 `facts` 数组提及的财务数值，回查 T04 财务 adapter 取的数值，不符标 `status=3 待核实`，不展示为已确认结论（对齐 PRD 故事 2 场景 3）。

**流程**（T21 落地，对齐技术方案 §4.3 流程 2）：

```
异步 Worker 调 LLM 得 content(JSON)
  ─ Jackson 反序列化 BriefContent，提取 facts[].{metric, value}
  ─ 对每条 fact：经数据访问层取 T04 FinanceAdapter 的 metric 真值
      └ 真值缺失（源降级 null）→ 该 fact 标 "待核实（源暂不可用）"，整体 status=3
      └ |AI 值 - 真值| / 真值 > 5%（相对误差）或符号方向不一致 → 标 "待核实（数值不符）"，status=3
      └ 吻合 → 该 fact 标 "已核实"，content 回写校验标注
  ─ 任一 fact 待核实 → status=3（整体），前端展示"待核实"角标，不作为已确认结论
  ─ 全部核实 → status=1 完成
```

**关键约定**：
- 校验阈值 5% 相对误差（配置项 `ai.hallucination.tolerance-ratio: 0.05`），方向性指标（同比正负）以符号为准。
- 校验范围仅限**带数值**的财务事实（`facts[].value` 非空且 `metric` 命中 T04 字段键）；定性判断（利好利空理由）不校验。
- 校验失败不阻断简报生成，只降级展示状态——对齐 PRD"降级不阻断"原则。
- 校验依赖 T04 财务 adapter 取数，若 T04 降级返回 null，标"源暂不可用"而非"不符"，区分两类待核实原因。

## 📝 7. 提示词工程建议（4 类简报 v1 模板）

> 模板落 `prompt_template` 表（`brief_type` + `version` + `template` + `status=1`），版本管理见 §7.5。`{占位符}` 由 AIBriefService 在调用前替换为聚合上下文。所有模板 system 段含 "json" 字样以满足 DeepSeek JSON mode 前提。

### 7.1 个股简报（briefType=1）

**system**：
```text
你是金融信息分析师。基于用户提供的标的聚合数据（行情/财务/估值/公告/新闻），
生成结构化 json 简报。规则：1) 只用提供的数据，不得编造数值或事件；
2) 每条事实附原文链接(sourceUrl)；3) 利好利空判断必须给理由；4) 关注建议不得给
具体买卖时点。输出一个合法 json 对象，不要输出 markdown 代码块或解释文字。
```

**user**（占位符由 Service 填充）：
```text
标的：{subjectName}({subjectCode})，行业：{industry}
当前价：{price} 日涨跌幅：{changePct}% 昨收：{preClose}
财务(报告期{reportDate})：营收{revenue} 归母净利{netProfit} 同比{netProfitYoy}%
毛利率{grossMargin}% ROE{roe}%
估值：PE(TTM){peTtm} PB{pb} PS{ps}
近24h公告：
{announcementsList}  // 每条: 标题 | 时间 | url
近7天新闻：
{newsList}  // 每条: 标题 | 时间 | url
请输出 json：{summary, keyEvents[], bias, biasReason, watchSuggestion, facts[], disclaimer}
facts 每条带 metric(如 net_profit_yoy/gross_margin/roe)、value、source、sourceUrl。
```

### 7.2 事件归因简报（briefType=2）

**system**：
```text
你是事件归因分析师。给定一个已发生的金融事件及其关联标的的多源上下文，
判断事件对该标的的影响方向与力度，生成结构化 json 简报。
规则：1) 归因必须基于提供的公告/新闻原文，不得臆测；2) 区分"已披露事实"与"市场推测"；
3) 不给买卖时点。输出合法 json 对象。
```

**user**：
```text
事件：{eventTitle}（{eventTime}，来源{eventSource}）
事件原文摘要：{eventSummary}
原文链接：{eventUrl}
关联标的：{subjectName}({subjectCode})
该标的行情：当前价{price} 事件后涨跌{postEventChangePct}%
该标的近况：{recentContext}  // 财务/估值/近期公告摘要
请输出 json：{summary, keyEvents[], bias, biasReason, watchSuggestion, facts[], disclaimer}
keyEvents 聚焦事件本身的因果链；facts 记录事件提及的数值。
```

### 7.3 政策解读（briefType=3，关联 T28）

**system**：
```text
你是政策分析师。给定一条宏观政策/时事条目及关联行业/标的，判断政策倾向
(利好/利空/中性)并解读，生成结构化 json 简报。规则：1) 倾向判断必须给政策依据；
2) 标注受影响行业与方向；3) 不给买卖时点。输出合法 json 对象。
```

**user**：
```text
政策：{policyTitle}（{publishedAt}，来源{source}）
摘要：{policySummary}
原文链接：{policyUrl}
关联行业：{relatedIndustries}
关联自选标的：{watchlistSubjects}  // 标的名+代码+行业
请输出 json：{summary, keyEvents[], bias(利好|利空|中性), biasReason,
watchSuggestion, facts[], disclaimer}
keyEvents 标注政策对各行业的方向影响；bias 为对该批标的的整体倾向。
```

### 7.4 每日推荐（briefType=4，关联 T23）

**system**：
```text
你是投资信息筛选助手。给定用户自选池各标的的"信息面活跃度+事件重要性"指标，
排序输出 Top5 推荐，每只附一句话推荐理由，生成结构化 json 简报。
规则：1) 排序依据只能是提供的指标，不得编造；2) 理由一句话、非买卖建议；
3) 输出合法 json 对象。
```

**user**：
```text
用户自选池（{poolSize} 只）指标快照：
{subjectsMetrics}  // 每只: 代码|名称|近24h公告数|近7天新闻数|异动次数|事件重要性分
用户订阅主题：{subscribedThemes}
今日日期：{today}
请输出 json：{summary, keyEvents[], bias="中性", biasReason,
watchSuggestion, facts[], disclaimer, topRecommend:[{subjectCode,subjectName,reason,rank}]}
topRecommend 取前5。
```

### 7.5 提示词版本管理建议（T20 落地）

- 表 `prompt_template(brief_type, version, template, status)` 已在技术方案 §4.2 定义；`UNIQUE(brief_type, version)` 防重复。
- 版本号语义：`v1.0`（主版本，输出 schema 变更）/ `v1.1`（次版本，措辞调优）；schema 不兼容才升主版本。
- `ai_brief.prompt_version` 记录每次生成所用版本，便于回溯"某简报为何这么差"。
- 灰度：新版本 `status=1` 启用前先在测试环境对样本用例回归（AI 简报测试要点见技术方案 §6）。
- 缓存键含 `prompt_version`：同 prompt+context 命中缓存（Caffeine TTL 按 brief_type：个股 1h / 每日推荐 24h），改版本自动失效旧缓存。

## 💰 8. 成本 / 延迟基线（基于公开定价估算）

> ⚠️ 真实延迟与成本需用户提供 API key 后实测，本节为基于公开定价的估算。

### 8.1 单次简报成本估算

假设：输入 token ≈ 聚合上下文（system prompt + 标的行情/财务/估值/公告/新闻）≈ 4000 token；输出 token ≈ 简报 JSON ≈ 800 token。

| 厂商·模型 | 输入成本 | 输出成本 | 单次合计 | 备注 |
| --- | --- | --- | --- | --- |
| DeepSeek-flash（空闲） | 4000/1M × 1 元 = 0.004 元 | 800/1M × 4 元 = 0.0032 元 | **~0.007 元** | 缓存命中 1800 token 再降 ~0.0003 元 |
| DeepSeek-flash（高峰） | 0.008 元 | 0.0064 元 | ~0.014 元 | 工作日盘前高峰段 |
| DeepSeek-v4-pro（空闲） | 0.018 元 | 0.0108 元 | ~0.029 元 | 重负载/强推理档 |
| GLM glm-4-flash-250414 | 0 元 | 0 元 | **0 元** | 免费档（限频内） |
| GLM 付费档（glm-4-plus 等） | 待控制台确认 | 待确认 | 估 ~0.02~0.05 元 | 🔴 定价页 JS 不可抓 |

### 8.2 延迟基线（估算）

| 环节 | 估算 | 说明 |
| --- | --- | --- |
| 网络 RTT（国内厂商） | 50~150ms | 国内机房直连 |
| 模型推理（flash 档，~800 token 输出） | 2~5s | 与模型负载、并发相关 |
| 端到端（受理 202 → 完成生成） | 3~8s（flash）/ 8~20s（v4-pro 思维链） | v4-pro 含 reasoning_tokens 显著变慢 |
| 异步任务超时上限 | 30s | 对齐技术方案 §4.3 流程 2 超时配置 |
| 失败兜底 | 超 30min 自动置失败 | Worker 卡死兜底 |

### 8.3 成本治理对齐（T30）

- **单用户日 token 预算**：`daily-token-budget-per-user: 20000`（技术方案 §4.4）。单次 ~4900 token → ~4 次/日/用户，够个人量级。
- **缓存降本**：同 prompt+context Caffeine 缓存（TTL 按 brief_type），重复请求 0 调用；DeepSeek `prompt_cache_hit_tokens` 服务端缓存折扣叠加。
- **超限拦截**：预算耗尽返回 `30030` 配额用尽（429 语义，技术方案 §4.1.4）。
- **成本报表**：`ai_brief.cost_tokens` + `usage` 累计 → T30 成本治理报表，按用户/模型/brief_type 维度统计。

## ⚙️ 9. 配置驱动多 provider + fallback（对齐 ADR-0008）

```yaml
# application.yml（密钥走环境变量，不入库不入 git）
llm:
  providers:
    - name: deepseek
      base-url: https://api.deepseek.com        # OpenAI兼容, RestClient 拼 /chat/completions
      model: deepseek-flash                      # DeepSeek-V4.1-Flash 性价比档(原 design 的 deepseek-chat 已迭代)
      api-key: ${DEEPSEEK_API_KEY}               # 环境变量注入, 不硬编码
      enabled: true
      default: true                              # 待用户拍板(见 §10)
      fallback: glm
    - name: glm
      base-url: https://open.bigmodel.cn/api/paas/v4
      model: glm-4-flash-250414                  # 免费档(128K上下文); 重负载可改 glm-4-plus
      api-key: ${GLM_API_KEY}
      enabled: true
      default: false
      fallback: deepseek
    # 预留(enabled=false, 按需启用, 各加 provider adapter):
    - name: qwen
      base-url: https://dashscope.aliyuncs.com/compatible-mode/v1
      model: qwen-plus
      api-key: ${QWEN_API_KEY}
      enabled: false
      default: false
      fallback: deepseek
    - name: kimi
      base-url: https://api.moonshot.cn/v1
      model: moonshot-v1-8k
      api-key: ${KIMI_API_KEY}
      enabled: false
      default: false
      fallback: deepseek
  timeout-seconds: 30
  retry: 1                  # 仅幂等请求重试, 指数退避
  daily-token-budget-per-user: 20000
  hallucination:
    tolerance-ratio: 0.05   # 数值校验相对误差阈值 5%
  cache:
    ttl:
      brief-type-1: 3600    # 个股简报 1h
      brief-type-4: 86400   # 每日推荐 24h
```

**fallback 机制**（T19 落地）：调用时取 `default: true` 的 provider；主 provider 超时/429 限频/5xx 错误 → 按 `fallback` 指向切下一个 `enabled: true` 厂商（重试 1 次，幂等）；全部失败才置 `ai_brief.status=2` 失败 + 告警。对齐技术方案 §4.3 流程 2 与 ADR-0008。

> base-url 约定：RestClient 在 base-url 后拼 `/chat/completions`。DeepSeek base-url 用 `https://api.deepseek.com`（无 `/v1`，OpenAI 兼容端点在根）；技术方案原写 `.../v1` 亦可（DeepSeek 同时支持 `/v1/chat/completions`），本报告统一用无 `/v1` 形式，T19 以实测可达为准。

## ❓ 10. 待用户拍板点

| # | 拍板项 | 选项 | 我的建议 | 理由 |
| --- | --- | --- | --- | --- |
| 1 | **默认 provider** | DeepSeek / GLM | **DeepSeek（deepseek-flash）** | 性价比高 + JSON mode 稳 + 1M 上下文 + 缓存折扣 |
| 2 | **是否零成本优先** | 用 GLM 免费档作默认 | 取决于预算偏好 | 若极度控成本，可令 GLM 免费档为 default、DeepSeek 为 fallback；质量略让步换 0 成本 |
| 3 | **API key 准备** | DeepSeek key / GLM key / 都要 | **都申请** | default + fallback 两家都要 key 才能跑通切换；T19 落地前需就绪 |
| 4 | **成本预算上限** | 日 token 预算 20000/用户 | 维持 20000 | 个人量级够用（~4 次/日/用户）；可按实际调 |
| 5 | **DeepSeek 合规** | 是否接受 DeepSeek | **可接受** | DeepSeek 为杭州深度求索，**国内厂商，无跨境合规问题**；与 GLM（北京智谱）同属国产 |
| 6 | **GLM 付费档** | 是否需要 glm-4-plus 强模型 | 暂不 | flash 免费档 + DeepSeek 够用；质量不足再升付费档（定价待控制台确认） |
| 7 | **GLM response_format 实测** | T19 是否立即实测 GLM JSON mode | **是** | 🟡 待确认项，T19 首个用例即验；不通过则启用 §5.3 提示词约束+解析兜底 |

> 拍板结论请回传，T19 将据此定 default provider 与模型名；未拍板前 T19 可先用 DeepSeek key 推进（两家 OpenAI 兼容，切换零成本）。

## 📋 11. 给 T19~T21/T23/T28 的契约要点

### 11.1 LlmGateway 端口（domain/ai，T19 定义 / infrastructure/ai 实现）

**端口接口（领域层，纯净不引框架）**：
```java
// domain/ai/gateway/LlmGateway.java（端口，依赖倒置）
public interface LlmGateway {
    /** 调用 LLM 生成结构化简报；返回原始 content + usage 计费信息 */
    LlmResponse complete(LlmRequest request);

    /** 异步调用（AI 简报走异步任务，T21 用） */
    CompletableFuture<LlmResponse> completeAsync(LlmRequest request);
}

// 领域值对象（纯净）
public record LlmRequest(String briefTypeKey, List<ChatMessage> messages,
                         String responseFormatType, double temperature, int maxTokens) {}
public record LlmResponse(String content, Map<String,Object> usage, String providerName, String model) {}
public record ChatMessage(String role, String content) {}
```

**provider adapter（基础设施层，T19 实现）**：
- `AbstractOpenAiCompatProvider`（公共基类）：封装 RestClient + Bearer 认证 + OpenAI 兼容请求/响应解析 + usage 提取；DeepSeek/GLM/通义/Kimi 共用。
- `DeepSeekProvider` / `GlmProvider` / ...：各覆盖 `base-url`/`model`/`response_format` 支持性差异（GLM 若不支持 response_format 则走提示词约束分支）。
- `LlmGatewayImpl`：按配置选 default provider，失败按 fallback 切换；超时 30s + 重试 1 次（幂等）+ 弹性四件套（对齐方案 07）。

**契约约定**：
- 入参 `messages` 由 AIBriefService（应用层）从 `prompt_template` 加载 + 填充聚合上下文后传入；gateway 不关心业务，只管调用。
- 出参 `content` 为 LLM 返回的 JSON 字符串（可能空/非法），解析责任在 AIBriefService（应用层）+ BriefContent 值对象（领域层）。
- `usage` 含 token 统计，AIBriefService 写 `ai_brief.cost_tokens` 供 T30 成本报表。
- `providerName`/`model` 写 `ai_brief.model` 供回溯。

### 11.2 T19 · LLM 网关抽象 + provider adapter + 成本上限

- 落 `domain/ai/gateway/LlmGateway`（端口）+ `infrastructure/ai/DeepSeekProvider` + `infrastructure/ai/GlmProvider` + `LlmGatewayImpl`。
- 配置类 `LlmProperties`（`@ConfigurationProperties("llm")`）绑定 §9 的 yaml；api-key 走环境变量，**永不入 git**（红线）。
- 成本上限：`LlmGatewayImpl` 调用前查 `ai_brief` 当日累计 token 是否超 `daily-token-budget-per-user`，超限抛 `BizException(30030)`。
- 缓存：同 `prompt_version + contextHash` 命中 Caffeine（TTL 按 brief_type，§9 配置），命中直接返回 0 调用。
- **首个用例即实测 GLM response_format**（🟡 待确认项），不通过切 §5.3 兜底。
- 估时 2 人天（技术方案任务表）。

### 11.3 T20 · 提示词版本表 + 模板管理

- 落 `prompt_template` 表（技术方案 §4.2 已建）+ Repository + 加载器。
- 初始化 §7 的 4 个 v1 模板（briefType 1~4，version `v1.0`，status=1）。
- `AIBriefService` 调用前按 `brief_type` + `status=1` 加载最新模板，占位符替换聚合上下文。
- 估时 1 人天。

### 11.4 T21 · AI 简报生成（异步+事实校验+回链）

- 对齐技术方案 §4.3 流程 2：POST `/ai-briefs`（幂等键 `subjectId+briefType+yyyyMMdd`）→ 202 + taskId → 异步 Worker → LLM 调用 → 解析 JSON → **幻觉校验（§6）** → 写 `source_links` → status=1/3 → 发 `AiBriefDoneEvent` → PushService SSE 推。
- 幻觉校验：`BriefContent.facts[]` 逐条回查 T04 FinanceAdapter 真值，超 5% 相对误差标 status=3 待核实。
- 事实回链：`facts[].sourceUrl` + `keyEvents[].sourceUrl` 收集写 `ai_brief.source_links`，回链率 100%（PRD 验收）。
- 依赖：T19（gateway）+ T20（模板）+ T02（数据访问层取上下文）。
- 估时 2 人天。

### 11.5 T23 · 每日推荐排序（盘前 Top5）

- `briefType=4`，@Scheduled 盘前触发（技术方案 §4.1.6 `GET /recommendations/daily`，对齐方案 08 定时任务）。
- 输入：自选池各标的"信息面活跃度+事件重要性"指标快照（公告数/新闻数/异动次数/事件重要性分）。
- 输出：`topRecommend` 前5，每只一句话理由（§7.4 模板）。
- 降级：LLM 不可用 → 规则兜底（信息面活跃度排序，技术方案 §5 降级预案）。
- 依赖 T21（简报异步链路复用）。
- 估时 2 人天。

### 11.6 T28 · 政策倾向 AI 判断（利好/利空/中性）

- `briefType=3`，对齐 §7.3 模板；`PolicyService` 在政策入库 / 用户点击时触发。
- 输出写 `policy_item.ai_tendency`（1利好 2利空 3中性，技术方案 §4.2 已建字段）。
- 复用 T19 gateway + T20 模板；倾向判断必须有政策依据（防臆测）。
- 依赖 T19（gateway）+ T24（政策流，提供 policy 上下文）。
- 估时 1.5 人天。

---

本调研遵循个人知识库《架构设计师角色文档》与 [02 设计架构](/project-development/02-design/index.md) 流程产出，权威依据见：

- [技术方案-信息整合平台.md](/docs/02-设计/技术方案-信息整合平台.md) §4.4 / §4.3 流程 2 / §8 Spike-2 / ADR-0004 / ADR-0008
- [REQ-20260920-01-信息整合平台.md](/docs/01-需求/REQ-20260920-01-信息整合平台.md) 故事 2 · AI 分析与推荐
- [Spike-1 · 数据源调研](/docs/02-设计/spike-1-数据源调研.md)（T04 财务 adapter 字段键，幻觉校验回查依据）
- [02 设计架构 · 选型五维度](/project-development/02-design/index.md#tech-evaluation)
- [个人技术栈基线 · 后端](/project-development/03-coding-standards/backend.md)
- [编辑文件规范](/personal-assistant/standards/editing-standards.md)
