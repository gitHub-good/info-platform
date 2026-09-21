package com.info.platform.application.push;

import com.info.platform.domain.push.NotificationEvent;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * 在线用户 SSE 推送通道端口（应用层定义、基础设施层 {@code SseConnectionRegistry} 实现）。
 *
 * <p>把 SSE 传输细节（{@link SseEmitter} 生命周期管理、JSON 序列化、并发连接表）封装在基础设施层， 应用层 {@code PushService}
 * 只依赖本端口，不触碰 Spring MVC 传输类型内部（除 {@link SseEmitter} 作为长连接句柄透传给接口层）。
 *
 * <p>方法语义：
 *
 * <ul>
 *   <li>{@link #open} —— 为用户注册一条 SSE 长连接（超时由实现配置），返回 {@link SseEmitter} 供接口层写入响应； 重复开连接时实现应优雅关闭旧连接。
 *   <li>{@link #send} —— 向在线用户推送一条事件（SSE {@code event:<type>} / {@code id:<recordId>} / {@code
 *       data:<json>}）； 用户离线返回 false（应用层据此决定留 status=0 待推 / 重试 / 告警）。
 *   <li>{@link #isOnline} —— 用户是否当前有活跃 SSE 连接。
 *   <li>{@link #close} —— 主动关闭用户连接（注销）。
 * </ul>
 *
 * <p>对齐技术方案 §4.1.3（SSE 长连接）、§4.3 流程 3（SSE 推送给在线用户）、ADR-0006（SSE + Spring 事件驱动）。
 */
public interface NotificationChannel {

    /** 注册一条 SSE 长连接并返回句柄；重复开连接优雅关闭旧连接。 */
    SseEmitter open(long userId);

    /**
     * 向在线用户推送一条事件。
     *
     * @param recordId push_record.id，作为 SSE {@code id:} 字段供客户端 {@code Last-Event-ID} 补拉。
     * @return true 推送成功；false 用户离线或连接已失效（应用层据此留待推/重试/告警）。
     */
    boolean send(long userId, NotificationEvent event, long recordId);

    /** 用户是否当前有活跃 SSE 连接。 */
    boolean isOnline(long userId);

    /** 主动注销用户连接。 */
    void close(long userId);
}
