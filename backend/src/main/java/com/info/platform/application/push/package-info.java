/**
 * DDD layer 'application' / domain 'push'：推送应用服务与 SSE 通道端口。
 *
 * <p>对齐技术方案 §4.3 流程 3 + §4.1.3 推送接口 + ADR-0006（SSE + Spring 事件驱动）。
 *
 * <ul>
 *   <li>{@link com.info.platform.application.push.PushService} ——
 *       推送应用服务：{@code @Async @EventListener} 异步消费 {@link
 *       com.info.platform.domain.push.AnomalyDetectedEvent}，编排「查订阅→幂等写 push_record→SSE
 *       推在线用户→失败重试1次→离线待推」； 同时提供 SSE 连接开启（含重连补拉）与 history 游标分页。
 *   <li>{@link com.info.platform.application.push.NotificationChannel} —— SSE 推送通道端口（应用层定义、基础设施层
 *       {@code SseConnectionRegistry} 实现），隔离 {@code SseEmitter} 传输细节。
 *   <li>{@link com.info.platform.application.push.NotificationView} / {@link
 *       com.info.platform.application.push.NotificationHistory} —— history 接口 DTO。
 *   <li>{@link com.info.platform.application.push.PushAsyncConfig} —— {@code @EnableAsync} +
 *       虚拟线程执行器。
 *   <li>{@link com.info.platform.application.push.PushRetryJob} —— T15 补推 job：@Scheduled 每 30s 扫
 *       anomaly_event pushed=0（重启未消费异动，复用 PushService 全链路补推）与 push_record status=0（离线待推，
 *       在线补推/离线跳过/失败重试1次仍失败 status=2 告警），补全推送链路完整性。
 * </ul>
 *
 * <p>{@link com.info.platform.application.push.AnomalyDetectionJob}（T13）、{@link
 * com.info.platform.application.push.PushService}（T14）、{@link
 * com.info.platform.application.push.PushRetryJob}（T15）同居本包： T13 发事件、T14 实时消费推送、T15 补推 job 扫待推与
 * 重启未消费异动补推，经 Spring ApplicationEvent + @Scheduled 解耦（ADR-0001/0006）。
 */
package com.info.platform.application.push;
