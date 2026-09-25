package com.info.platform.domain.feed;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.Normalizer;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Locale;

/**
 * 跨源同文指纹（M13 T104，ADR-0039 裁决 2，纯函数可单测）。
 *
 * <p>归一化规则（逐条可测，§4.2）：①NFKC 规范化（全角标点/数字 → 半角、兼容组合分解如 ①→1）； ②删除全部空白字符（{@code
 * [\s\u3000]}，含标题中间空格——中文标题空格无语义；英文词距不参与判同的保守取舍由 casefold+去空白共同吸收， 误合并风险由「+日期」边界兜底）；③ASCII
 * casefold（英文源 MarketWatch 与转载同题）； ④拼接定界符 {@code #} + published_date（Asia/Shanghai yyyy-MM-dd——源缺时间由调用方回落抓取日；
 * 跨日同题不合并，保守方向宁漏并勿错并）；⑤SHA-256 十六进制小写。
 */
public final class FeedFingerprint {

    /** 指纹日期时区（Asia/Shanghai，运营心智）。 */
    public static final ZoneId ZONE_SH = ZoneId.of("Asia/Shanghai");

    private FeedFingerprint() {}

    /** sha256( NFKC(title) 去全部空白 casefold + "#" + Asia/Shanghai 日期 )，64 位十六进制小写。 */
    public static String fingerprint(String title, Instant publishedAt) {
        String normalized = normalize(title);
        LocalDate date = fingerprintDate(publishedAt);
        return sha256Hex(normalized + "#" + date);
    }

    /** 归一化标题（NFKC + 去全部空白 + casefold；null 视作空串）。 */
    static String normalize(String title) {
        String raw = title == null ? "" : title;
        return Normalizer.normalize(raw, Normalizer.Form.NFKC)
                .replaceAll("[\\s\\u3000]", "")
                .toLowerCase(Locale.ROOT);
    }

    /** 指纹日期段（publishedAt 按 Asia/Shanghai 取 yyyy-MM-dd）。 */
    static LocalDate fingerprintDate(Instant publishedAt) {
        return LocalDate.ofInstant(publishedAt, ZONE_SH);
    }

    private static String sha256Hex(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                hex.append(Character.forDigit((b >> 4) & 0xF, 16))
                        .append(Character.forDigit(b & 0xF, 16));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            // JDK 必带 SHA-256，此处防御性兜底（不吞：启动即败）
            throw new IllegalStateException("SHA-256 摘要算法不可用", e);
        }
    }
}
