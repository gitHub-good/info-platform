# 💻 后端 · 模块化单体（DDD 分层）

> Java 21 + Spring Boot 3 + MyBatis-Plus + SQLite(WAL) + Caffeine，`java -jar` 单包部署。整体模块化单体（ADR-0001），各业务域内部按 DDD 四层组织（ADR-0007）。

## 🧱 DDD 四层

分层铁律：依赖只能向下、禁止跨层调用、领域层不 import 框架/中间件类型（可单测可移植）、依赖倒置（领域层定义端口、基础设施层实现）。

| 层 | 职责 | 本项目落点 |
| --- | --- | --- |
| 接口层 Controller/API | 参数校验、协议转换、鉴权 | 各域 REST Controller + common 统一响应/鉴权过滤器 |
| 应用层 Service | 用例编排、事务边界，不写业务规则 | 各域 ApplicationService 编排聚合/AI/推送/订阅流程 |
| 领域层 Domain | 实体/值对象/领域服务/业务规则（最稳定、纯净） | 各域 Entity/ValueObject/DomainService + Repository 端口接口 |
| 基础设施层 Repository | 数据库、缓存、MQ、第三方调用 | 各域 Repository 实现 + SourceAdapter + LlmGateway provider + common 缓存/限频/定时/弹性/配置 |

## 📦 业务域（层内分包）

| 域 | 四层落点 | 主要任务 |
| --- | --- | --- |
| aggregation | 全四层 | T02~T09、T16、T33 |
| ai | 全四层 | T19~T21、T23、T28、T33 |
| push | 全四层 | T13~T15、T33 |
| policy | 全四层 | T07、T24、T33 |
| subscription | 全四层 | T26、T27、T33 |
| common | 接口层 + 基础设施层（横切） | T17、T18、T32 |

跨域协作只走应用层领域事件（Spring ApplicationEvent），不 import 其他域内部类；单拆某域为服务时抽其各层切片即可，演进缝仍保留。

## 🔌 LLM 多厂商配置（ADR-0008）

配置驱动接入 DeepSeek / GLM（智谱）等厂商，运行时可切默认 provider、支持 fallback，密钥走环境变量不硬编码：

```yaml
llm:
  providers:
    - name: deepseek
      base-url: https://api.deepseek.com/v1
      model: deepseek-chat
      api-key: ${DEEPSEEK_API_KEY}
      enabled: true
      default: true
      fallback: glm
    - name: glm
      base-url: https://open.bigmodel.cn/api/paas/v4
      model: glm-4
      api-key: ${GLM_API_KEY}
      enabled: true
      default: false
      fallback: deepseek
    # 预留: qwen / wenxin / kimi (enabled=false, 按需启用)
```

- `LlmGateway` 接口定义在领域层（纯净可单测），基础设施层按 provider 实现 adapter。
- 主 provider 超时/限频/错误则按 `fallback` 切下一个 `enabled: true` 厂商，全部失败才置简报失败并告警。
- 增减厂商只改配置不改代码。

## 🚀 构建与运行

```bash
cd backend
mvn clean compile        # 编译
mvn test                 # 单测 + 集成测试（SQLite 共享内存库 + Flyway 建表）
mvn spring-boot:run      # 本地启动（首次需先建 data/ 目录用于 SQLite 文件库）
```

- 数据源：SQLite（WAL），库文件 `backend/data/info-platform.db`（不入 git，`.gitignore` 已含 `data/`）；测试用共享内存库 `jdbc:sqlite:file::memory:?cache=shared`。
- 迁移：Flyway，脚本在 `src/main/resources/db/migration`，每个 DDL 附 `U__` 回滚脚本（社区版不自动执行 Undo，作手动回滚留存）。
- 分层守护：DDD 四层依赖方向由 ArchUnit（`LayeredArchitectureTest`）守护，领域层纯净不引框架、禁跨层调用。
- 已落地批：T32 脚手架 + ArchUnit、T01 标的主数据表/迁移/领域建模、T18 统一响应/全局异常/错误码/traceId。

## 📐 规范

- 接口契约、数据模型、核心流程见 [技术方案 §4](/docs/02-设计/技术方案-信息整合平台.md)
- 编码规范见个人知识库 [03 编码规范](/project-development/03-coding-standards/index.md)
- 所有跨进程调用（6 个数据源、LLM）必配弹性四件套：超时 / 重试 / 幂等 / 降级
- 三类写操作（AI 简报 / 推送 / 订阅创建）一律业务语义幂等键，不用 UUID
- DDD 分层依赖校验用 ArchUnit 守护（领域层不引框架/禁跨层调用），见 T32
