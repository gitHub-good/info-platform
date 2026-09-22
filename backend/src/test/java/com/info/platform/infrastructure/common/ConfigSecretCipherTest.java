package com.info.platform.infrastructure.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.info.platform.domain.common.BusinessException;
import com.info.platform.domain.common.ErrorCode;
import org.junit.jupiter.api.Test;

/**
 * {@link ConfigSecretCipher} 测试（T34 / ADR-0018）：加解密往返、IV 随机性（同明文两次密文不同）、CONFIG_SECRET 缺失降级 （写
 * 30064）、过短密钥 fail-fast、密文损坏/密钥不符（认证标签）拒绝。
 */
class ConfigSecretCipherTest {

    private static final String SECRET = "test-config-secret-0123456789abcdef"; // 33 字节 ≥32

    @Test
    void encryptDecrypt_roundtrip_restoresPlaintext() {
        // Arrange
        ConfigSecretCipher cipher = new ConfigSecretCipher(SECRET);

        // Act
        String encoded = cipher.encrypt("sk-live-9f8e7d6c5b4a");

        // Assert
        assertThat(cipher.enabled()).isTrue();
        assertThat(cipher.decrypt(encoded)).isEqualTo("sk-live-9f8e7d6c5b4a");
        assertThat(encoded).doesNotContain("sk-live"); // 密文不含明文片段
    }

    @Test
    void encrypt_samePlaintextTwice_producesDifferentCiphertext() {
        // Arrange（随机 IV：同明文两次密文不同，抗字典比对）
        ConfigSecretCipher cipher = new ConfigSecretCipher(SECRET);

        // Act
        String first = cipher.encrypt("sk-same");
        String second = cipher.encrypt("sk-same");

        // Assert：密文不同但均可解回同一明文
        assertThat(first).isNotEqualTo(second);
        assertThat(cipher.decrypt(first)).isEqualTo("sk-same");
        assertThat(cipher.decrypt(second)).isEqualTo("sk-same");
    }

    @Test
    void missingSecret_degradesToDisabled_encryptThrows30064() {
        // Arrange：未配置 CONFIG_SECRET（降级态：不阻断启动、不禁读）
        ConfigSecretCipher cipher = new ConfigSecretCipher("");

        // Act + Assert：encrypt 抛 30064（API key 写入未启用），写接口据此返回 503
        assertThat(cipher.enabled()).isFalse();
        assertThatThrownBy(() -> cipher.encrypt("sk-new"))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        ex ->
                                assertThat(ex.getErrorCode())
                                        .isEqualTo(ErrorCode.API_KEY_WRITE_DISABLED));
    }

    @Test
    void shortSecret_failsFastAtConstruction() {
        // Assert：配置但 <32 字节 → 构造期 fail-fast（与 JWT_SECRET 同策略）
        assertThatThrownBy(() -> new ConfigSecretCipher("short-secret"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("32 字节");
    }

    @Test
    void decrypt_tamperedCiphertext_rejected() {
        // Arrange
        ConfigSecretCipher cipher = new ConfigSecretCipher(SECRET);
        String encoded = cipher.encrypt("sk-plain");

        // Act：篡改密文尾字节（GCM 认证标签校验失败）
        byte[] bytes = java.util.Base64.getDecoder().decode(encoded);
        bytes[bytes.length - 1] ^= 0x01;
        String tampered = java.util.Base64.getEncoder().encodeToString(bytes);

        // Assert
        assertThatThrownBy(() -> cipher.decrypt(tampered))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        ex -> assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.SERVER_ERROR))
                .hasMessageContaining("解密失败");
    }

    @Test
    void decrypt_wrongKey_rejected() {
        // Arrange：密文用 SECRET 加密，另一实例持不同主密钥（模拟 CONFIG_SECRET 轮换）
        ConfigSecretCipher original = new ConfigSecretCipher(SECRET);
        ConfigSecretCipher rotated = new ConfigSecretCipher("another-config-secret-0123456789");
        String encoded = original.encrypt("sk-plain");

        // Act + Assert
        assertThatThrownBy(() -> rotated.decrypt(encoded)).hasMessageContaining("重新录入");
    }

    @Test
    void decrypt_illegalInput_rejected() {
        ConfigSecretCipher cipher = new ConfigSecretCipher(SECRET);
        assertThatThrownBy(() -> cipher.decrypt("not-base64!!!"))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(
                        () ->
                                cipher.decrypt(
                                        java.util.Base64.getEncoder().encodeToString(new byte[5])))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void last4_extractsTailForDisplay() {
        assertThat(ConfigSecretCipher.last4("sk-live-abcd")).isEqualTo("abcd");
        assertThat(ConfigSecretCipher.last4("abc")).isEqualTo("abc");
        assertThat(ConfigSecretCipher.last4("")).isNull();
        assertThat(ConfigSecretCipher.last4(null)).isNull();
    }
}
