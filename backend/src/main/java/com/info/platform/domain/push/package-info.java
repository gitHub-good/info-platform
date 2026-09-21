/**
 * DDD layer 'domain' / domain 'push'：异动/事件推送领域模型与端口。
 *
 * <p>对齐技术方案 §4.2 push_record DDL、§4.3 流程 3（PushService @EventListener 异步消费 AnomalyDetectedEvent →
 * 查订阅 → INSERT push_record 幂等防重推 → SSE 推送在线用户 → 失败重试1次仍失败 status=2 告警）、§4.4
 * 幂等（idempotency_key=userId+pushType+refId， DB UNIQUE 最后防线）、ADR-0006（SSE + Spring 事件驱动）。
 *
 * <p>领域层纯净（ADR-0007，禁 import 框架类型，由 {@code LayeredArchitectureTest} 守护）：本包仅依赖 JDK 类型——
 *
 * <ul>
 *   <li>枚举：{@link com.info.platform.domain.push.AnomalyType}（T13 异动类型）、{@link
 *       com.info.platform.domain.push.PushType}（推送类型/SSE 事件名）、{@link
 *       com.info.platform.domain.push.PushStatus}（推送状态）
 *   <li>实体：{@link com.info.platform.domain.push.AnomalyRecord}（异动流水，T14 消费后置 pushed=1）、{@link
 *       com.info.platform.domain.push.PushRecord}（推送记录，幂等键+状态流转）
 *   <li>事件：{@link com.info.platform.domain.push.AnomalyDetectedEvent}（T13 发、T14 消费的纯 POJO
 *       领域事件）、{@link com.info.platform.domain.push.NotificationEvent}（SSE 推送载荷 record）
 *   <li>端口：{@link com.info.platform.domain.push.AnomalyRepository}（异动仓储）、{@link
 *       com.info.platform.domain.push.PushRepository}（推送记录仓储，saveIfAbsent 幂等防重 + history 游标分页 +
 *       重连补拉）、{@link com.info.platform.domain.push.SubscriptionResolver}（推送目标解析，M1=watchlist
 *       隐含订阅，T26 切换 subscription_config）
 * </ul>
 *
 * <p>跨域协作走领域事件（Spring ApplicationEvent，应用层发布/订阅），不跨域 import 内部类。
 */
package com.info.platform.domain.push;
