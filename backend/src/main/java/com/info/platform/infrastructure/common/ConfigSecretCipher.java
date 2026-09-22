package com.info.platform.infrastructure.common;

import com.info.platform.domain.common.BusinessException;
import com.info.platform.domain.common.ErrorCode;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 配置密文工具（AES-256-GCM，T34 / ADR-0018）。
 *
 * <p>主密钥 = 环境变量 {@code CONFIG_SECRET}（经 {@code config.secret} 绑定，≥32 字节；配置但过短启动 fail-fast，与
 * JWT_SECRET 同策略），经 SHA-256 派生为 AES-256 密钥（任意 ≥32 字节口令均可作主密钥）。 密文格式 = base64(随机 12 字节 IV ‖
 * AES-256-GCM 密文)，自包含可移植；同一明文每次加密 IV 不同 （随机 IV 抗重放/字典比对）。
 *
 * <p>降级（{@code CONFIG_SECRET} 未配置）：不阻断启动、不禁读——{@link #encrypt} 抛 30064（写接口据此返回 503），API key
 * 页面降级为只读态「key 走环境变量」。密文与尾 4 位存于 runtime_config 的 provider 文档内（{@code apiKeyCipher} / {@code
 * apiKeyLast4}），明文永不落库、不落日志。
 */
@Component
public class ConfigSecretCipher {

    private static final Logger log = LoggerFactory.getLogger(ConfigSecretCipher.class);

    /** AES-256 主密钥下限：32 字节。 */
    public static final int MIN_SECRET_BYTES = 32;

    /** GCM 推荐 IV 长度（12 字节，NIST SP 800-38D）。 */
    private static final int GCM_IV_BYTES = 12;

    /** GCM 认证标签长度（128 位）。 */
    private static final int GCM_TAG_BITS = 128;

    private static final String TRANSFORMATION = "AES/GCM/NoPadding";

    private static final SecureRandom RANDOM = new SecureRandom();

    /** 主密钥；null = 未配置 CONFIG_SECRET（降级态）。 */
    private final SecretKey key;

    public ConfigSecretCipher(@Value("${config.secret:}") String secret) {
        if (secret == null || secret.isBlank()) {
            this.key = null;
            log.warn("CONFIG_SECRET 未配置：API key 写入功能降级（写接口返回 30064，key 继续走环境变量）");
            return;
        }
        byte[] secretBytes = secret.getBytes(StandardCharsets.UTF_8);
        if (secretBytes.length < MIN_SECRET_BYTES) {
            throw new IllegalStateException(
                    "config.secret 过短：AES-256 要求 >=32 字节，当前 " + secretBytes.length + " 字节");
        }
        this.key = deriveAesKey(secretBytes);
        log.info("ConfigSecretCipher 就绪（AES-256-GCM，API key 加密写入已启用）");
    }

    /**
     * 主密钥 → AES-256 密钥：SHA-256 摘要派生（输入 ≥32 字节任意长度，输出恒 32 字节—— AES 密钥长度只允许 16/24/32 字节，裸用 35 字节口令会
     * InvalidKeyException）。
     */
    private static SecretKey deriveAesKey(byte[] secretBytes) {
        try {
            java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
            return new SecretKeySpec(digest.digest(secretBytes), "AES");
        } catch (java.security.NoSuchAlgorithmException e) {
            // JDK 必带 SHA-256，理论不可达
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }

    /** 是否已启用（CONFIG_SECRET 已配置且合法）。 */
    public boolean enabled() {
        return key != null;
    }

    /**
     * 加密明文 → base64(IV ‖ 密文)。
     *
     * @throws BusinessException 30064 未启用（CONFIG_SECRET 未配置）
     * @throws BusinessException 50000 加密失败
     */
    public String encrypt(String plaintext) {
        if (key == null) {
            throw new BusinessException(ErrorCode.API_KEY_WRITE_DISABLED);
        }
        try {
            byte[] iv = new byte[GCM_IV_BYTES];
            RANDOM.nextBytes(iv);
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(GCM_TAG_BITS, iv));
            byte[] ciphertext = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            byte[] out = new byte[iv.length + ciphertext.length];
            System.arraycopy(iv, 0, out, 0, iv.length);
            System.arraycopy(ciphertext, 0, out, iv.length, ciphertext.length);
            return Base64.getEncoder().encodeToString(out);
        } catch (Exception e) {
            throw new BusinessException(ErrorCode.SERVER_ERROR, "API key 加密失败: " + e);
        }
    }

    /**
     * 解密 {@link #encrypt} 产出的密文。
     *
     * @throws BusinessException 50000 密文损坏 / CONFIG_SECRET 已轮换（认证标签校验失败）
     */
    public String decrypt(String encoded) {
        byte[] in;
        try {
            in = Base64.getDecoder().decode(encoded);
        } catch (IllegalArgumentException e) {
            throw new BusinessException(ErrorCode.SERVER_ERROR, "API key 密文格式非法");
        }
        if (key == null || in.length <= GCM_IV_BYTES) {
            throw new BusinessException(ErrorCode.SERVER_ERROR, "API key 密文格式非法");
        }
        try {
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(
                    Cipher.DECRYPT_MODE,
                    key,
                    new GCMParameterSpec(GCM_TAG_BITS, in, 0, GCM_IV_BYTES));
            byte[] plain = cipher.doFinal(in, GCM_IV_BYTES, in.length - GCM_IV_BYTES);
            return new String(plain, StandardCharsets.UTF_8);
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            throw new BusinessException(
                    ErrorCode.SERVER_ERROR, "API key 密文解密失败（CONFIG_SECRET 可能已轮换，需重新录入 key）");
        }
    }

    /** 明文尾 4 位（脱敏回显展示用）。 */
    public static String last4(String plaintext) {
        if (plaintext == null || plaintext.isEmpty()) {
            return null;
        }
        return plaintext.length() <= 4 ? plaintext : plaintext.substring(plaintext.length() - 4);
    }
}
