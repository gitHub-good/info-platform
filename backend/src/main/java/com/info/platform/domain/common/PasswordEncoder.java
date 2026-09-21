package com.info.platform.domain.common;

/**
 * 密码编码器端口（依赖倒置：领域层定义、基础设施层 BCrypt 实现）。
 *
 * <p>领域层纯净接口。认证应用层用它校验登录密码、注册时哈希原始密码。永不在领域层暴露明文密码细节。
 */
public interface PasswordEncoder {

    /** 对原始密码做单向哈希（每次盐随机）。 */
    String encode(String rawPassword);

    /** 校验原始密码与已存储的哈希是否匹配（恒定时间比较由实现保证）。 */
    boolean matches(String rawPassword, String encodedHash);
}
