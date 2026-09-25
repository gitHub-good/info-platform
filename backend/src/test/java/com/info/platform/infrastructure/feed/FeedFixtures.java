package com.info.platform.infrastructure.feed;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/** feed 预置源 fixture 装载（src/test/resources/feed/，真实响应截样本，零外呼单测用）。 */
final class FeedFixtures {

    private FeedFixtures() {}

    static String load(String path) {
        try (InputStream in = FeedFixtures.class.getClassLoader().getResourceAsStream(path)) {
            if (in == null) {
                throw new IllegalStateException("fixture 缺失: " + path);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("fixture 读取失败", e);
        }
    }
}
