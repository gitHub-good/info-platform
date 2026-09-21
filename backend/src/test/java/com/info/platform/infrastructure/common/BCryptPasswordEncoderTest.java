package com.info.platform.infrastructure.common;

import static org.assertj.core.api.Assertions.assertThat;

import com.info.platform.domain.common.PasswordEncoder;
import org.junit.jupiter.api.Test;

/**
 * BCryptPasswordEncoder 单测（T17）：encode 生成不同盐哈希、matches 往返、错误密码不匹配、null 安全。 另校验 V3 种子哈希对初始密码
 * "admin123" 可匹配（防种子哈希与文档密码脱节）。
 */
class BCryptPasswordEncoderTest {

    private final PasswordEncoder encoder = new BCryptPasswordEncoder();

    /** V3 迁移播种的 admin 用户 password_hash（BCrypt cost=10，明文 admin123）。 */
    private static final String SEEDED_ADMIN_HASH =
            "$2a$10$tAs2zalIu.jzVe9iXO3k3uwQh04h/Tm6PnOOS/lvTv00nRBLa7wRe";

    @Test
    void encode_producesBcryptHashWithRandomSalt() {
        String h1 = encoder.encode("admin123");
        String h2 = encoder.encode("admin123");
        assertThat(h1).startsWith("$2a$10$").hasSize(60);
        // 每次盐不同 → 相同明文产出不同哈希
        assertThat(h1).isNotEqualTo(h2);
    }

    @Test
    void matches_correctPassword_returnsTrue() {
        String hash = encoder.encode("s3cret-pw");
        assertThat(encoder.matches("s3cret-pw", hash)).isTrue();
    }

    @Test
    void matches_wrongPassword_returnsFalse() {
        String hash = encoder.encode("correct");
        assertThat(encoder.matches("wrong", hash)).isFalse();
    }

    @Test
    void matches_nullInputs_returnsFalse() {
        assertThat(encoder.matches(null, encoder.encode("x"))).isFalse();
        assertThat(encoder.matches("x", null)).isFalse();
    }

    @Test
    void seededAdminHash_matchesInitialPassword() {
        // 防御：V3 种子哈希必须能被初始密码 "admin123" 校验通过，否则登录种子用户必败
        assertThat(encoder.matches("admin123", SEEDED_ADMIN_HASH)).isTrue();
        assertThat(encoder.matches("not-the-password", SEEDED_ADMIN_HASH)).isFalse();
    }
}
