package com.info.platform.infrastructure.common;

import org.springframework.stereotype.Component;

/**
 * {@link com.info.platform.domain.common.PasswordEncoder} 端口的 BCrypt 实现（基础设施层）。
 *
 * <p>委托 spring-security-crypto 单 jar 的 {@code BCryptPasswordEncoder}（cost=10，与 V3 种子哈希一致）。 BCrypt
 * 自带随机盐、抗彩虹表；恒定时间比较由底层实现保证。 不引全量 spring-security，仅依赖 spring-security-crypto。
 */
@Component
public class BCryptPasswordEncoder implements com.info.platform.domain.common.PasswordEncoder {

    private final org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder delegate =
            new org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder(10);

    @Override
    public String encode(String rawPassword) {
        return delegate.encode(rawPassword);
    }

    @Override
    public boolean matches(String rawPassword, String encodedHash) {
        if (rawPassword == null || encodedHash == null) {
            return false;
        }
        return delegate.matches(rawPassword, encodedHash);
    }
}
