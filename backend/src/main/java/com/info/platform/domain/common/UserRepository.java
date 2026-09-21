package com.info.platform.domain.common;

import java.util.Optional;

/**
 * 用户仓储端口（依赖倒置：领域层定义、基础设施层实现）。
 *
 * <p>领域层纯净接口，不依赖 MyBatis/Spring 等框架类型。供认证应用层按用户名加载用户、判定占用。
 */
public interface UserRepository {

    /** 按用户名加载用户（登录用）。 */
    Optional<User> findByUsername(String username);

    /** 判定用户名是否已被占用（注册/种子校验用）。 */
    boolean existsByUsername(String username);
}
