package com.info.platform.infrastructure.feed;

import static org.assertj.core.api.Assertions.assertThat;

import com.info.platform.domain.feed.RobotsPolicyChecker.RobotsVerdict;
import org.junit.jupiter.api.Test;

/**
 * RobotsPolicyClient 判读单测（T105，普查 §1.1 / RFC 9309 子集）：robots 文本评估纯函数 fixture 全覆盖（通配组 / 前缀命中 / 空
 * Disallow 即全放 / Allow 长于 Disallow 胜出 / 无通配组放行），零外呼。
 */
class RobotsPolicyClientTest {

    @Test
    void evaluate_wildcardDisallowAll_blocksAnyPath() {
        String robots =
                """
                User-agent: *
                Disallow: /
                """;

        assertThat(RobotsPolicyClient.evaluate(robots, "/anything/here").allowed()).isFalse();
        assertThat(RobotsPolicyClient.evaluate(robots, "/").allowed()).isFalse();
    }

    @Test
    void evaluate_prefixRule_blocksOnlyMatchingPrefix() {
        String robots =
                """
                User-agent: *
                Disallow: /api/
                Disallow: /admin
                """;

        assertThat(RobotsPolicyClient.evaluate(robots, "/api/flash").allowed()).isFalse();
        assertThat(RobotsPolicyClient.evaluate(robots, "/public/feed").allowed()).isTrue();
        // 前缀匹配不越界：/administrator 命中 /admin 前缀（RFC 9309 前缀语义，无段边界特判——保守拦截）
        assertThat(RobotsPolicyClient.evaluate(robots, "/administrator").allowed()).isFalse();
    }

    @Test
    void evaluate_emptyDisallowRule_meansAllowAll() {
        String robots =
                """
                User-agent: *
                Disallow:
                """;

        assertThat(RobotsPolicyClient.evaluate(robots, "/feed.xml").allowed()).isTrue();
    }

    @Test
    void evaluate_longerAllowWinsOverShorterDisallow() {
        String robots =
                """
                User-agent: *
                Disallow: /api/
                Allow: /api/public/
                """;

        assertThat(RobotsPolicyClient.evaluate(robots, "/api/public/feed").allowed()).isTrue();
        assertThat(RobotsPolicyClient.evaluate(robots, "/api/private").allowed()).isFalse();
    }

    @Test
    void evaluate_noWildcardGroup_specificAgentRulesIgnored() {
        String robots =
                """
                User-agent: Baiduspider
                Allow: /
                User-agent: GPTBot
                Disallow: /
                """;

        assertThat(RobotsPolicyClient.evaluate(robots, "/flash_newest.js").allowed()).isTrue();
    }

    @Test
    void evaluate_blankOrMissingRules_allowAll() {
        assertThat(RobotsPolicyClient.evaluate("", "/x").allowed()).isTrue();
        assertThat(RobotsPolicyClient.evaluate("# 注释而已\n", "/x").allowed()).isTrue();
        assertThat(RobotsPolicyClient.evaluate(null, "/x").allowed()).isTrue();
    }

    @Test
    void evaluate_disallowedVerdictCarriesHitRuleNote() {
        String robots =
                """
                User-agent: *
                Disallow: /flash
                """;

        RobotsVerdict verdict = RobotsPolicyClient.evaluate(robots, "/flash_newest.js");

        assertThat(verdict.allowed()).isFalse();
        assertThat(verdict.note()).contains("/flash");
    }

    @Test
    void robotsUrl_derivesSchemeAndHostRoot() {
        assertThat(RobotsPolicyClient.robotsUrlFor("https://www.jin10.com/flash_newest.js?x=1"))
                .isEqualTo("https://www.jin10.com/robots.txt");
        assertThat(RobotsPolicyClient.robotsUrlFor("http://example.com:8080/a/b"))
                .isEqualTo("http://example.com:8080/robots.txt");
    }
}
