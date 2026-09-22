/**
 * 任务执行中心基础设施（T37，方案 §4.5）：集中调度中心 + 统一执行通道 + 任务中心端口实现。
 *
 * <p>JobScheduler 按 runtime_config 注册/取消/热重注册（订阅配置变更事件）；JobExecutor 承载 CAS 防重入与
 * 显式留痕（定时与手动共用，字段语义与退役 AOP 时代一致）；JobCenterFacadeImpl 组装总览/详情/更新/触发。
 */
package com.info.platform.infrastructure.jobrun;
