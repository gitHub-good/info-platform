/**
 * DDD layer 'domain' / domain 'ai' · LLM 网关端口与值对象（T19）+ 提示词模板管理（T20）。
 *
 * <p>纯 JDK，不引框架（ArchUnit 守护）： {@code LlmGateway} 端口 + {@code LlmRequest}/{@code LlmResponse}/
 * {@code LlmUsage}/{@code ChatMessage} 值对象 + {@code LlmProvider} 厂商枚举 + {@code LlmException} 失败异常
 * （T19）； T20 增 {@code BriefType} 简报类型枚举 + {@code PromptTemplate} 模板实体 + {@code
 * PromptTemplateRepository} 加载端口（对齐技术方案 §4.2 prompt_template DDL + §4.3 流程 2 加载 + Spike-2 §7 4 类 v1
 * 模板）。 provider 选择 / fallback / 成本上限 / 缓存为基础设施层职责，不在领域层。对齐技术方案 §4.4 与 ADR-0004 / ADR-0008。
 *
 * <p>跨域协作走应用层领域事件；不跨域 import 内部类（ADR-0007）。
 */
package com.info.platform.domain.ai;
