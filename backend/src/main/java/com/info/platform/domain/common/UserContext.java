package com.info.platform.domain.common;

/**
 * 请求级认证主体持有器（ThreadLocal），供下游行级权限取 {@code userId}。
 *
 * <p>由接口层 {@code JwtAuthFilter} 在请求入口解析令牌后 {@link #set}，请求结束 {@link #clear}（防线程池复用串味）； 应用层（如 T11
 * watchlist）经 {@link #get} 取当前用户 {@code userId}，做行级数据权限校验（只能操作自己 user_id 的资源）。 领域层纯
 * JDK（ThreadLocal），不依赖框架；放在 common 域共享内核，各层均可读写而不破分层。
 */
public final class UserContext {

    private static final ThreadLocal<Principal> HOLDER = new ThreadLocal<>();

    private UserContext() {}

    /** 写入当前请求的认证主体。 */
    public static void set(Principal principal) {
        HOLDER.set(principal);
    }

    /** 取当前请求的认证主体；未认证或白名单路径下返回 {@code null}。 */
    public static Principal get() {
        return HOLDER.get();
    }

    /** 清除当前请求的认证主体（过滤器 finally 必调）。 */
    public static void clear() {
        HOLDER.remove();
    }

    /** 认证主体：{@code userId} 为行级权限取数键，{@code username} 供日志/审计。 */
    public record Principal(long userId, String username) {}
}
