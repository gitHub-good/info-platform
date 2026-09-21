/**
 * DDD layer 'interfaces' / domain 'push'：推送接口层。
 *
 * <p>对齐技术方案 §4.1.3 推送接口 + ADR-0006（SSE + Spring 事件驱动）。
 *
 * <ul>
 *   <li>{@link com.info.platform.interfaces.push.NotificationController} —— SSE 长连接（{@code
 *       /stream}）+ history 游标分页 （{@code /api/v1/notifications?cursor=&type=}），受 JWT 保护，userId 取
 *       {@link com.info.platform.domain.common.UserContext}。
 * </ul>
 *
 * <p>接口层只做参数校验与编排，业务规则在应用层 {@code PushService}；不直依赖基础设施层（由 {@code LayeredArchitectureTest} 守护）。
 */
package com.info.platform.interfaces.push;
