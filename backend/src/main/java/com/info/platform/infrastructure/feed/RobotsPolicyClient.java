package com.info.platform.infrastructure.feed;

import com.info.platform.domain.feed.RobotsPolicyChecker;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * robots.txt 判读客户端（M13 T105，普查 §1.1 / RFC 9309 子集）：保存硬拦截（30075）与连通性测试复判的落地件。
 *
 * <p>判读口径：{@code 200} → 解析通配组（{@code User-agent: *}）规则，最长前缀胜出（Allow/Disallow 平局 Allow 胜）；
 * 404/410/403/3xx 跳页/空文件/5xx/网络不可达 → 按无限制处理并注记（普查 §1.1 落地口径）。仅命中通配组显式 {@code Disallow} 才拒绝——
 * 全站禁抓源（央行/国务院搜索 API，普查 §6-R3）在保存时即被 30075 拦下。
 */
@Component
public class RobotsPolicyClient implements RobotsPolicyChecker {

    private static final Logger log = LoggerFactory.getLogger(RobotsPolicyClient.class);

    /** robots 拉取超时（不拖慢保存路径）。 */
    private static final Duration ROBOTS_TIMEOUT = Duration.ofSeconds(3);

    private final RestClient restClient;

    /** 生产装配（3s 超时）。 */
    @Autowired
    public RobotsPolicyClient(RestClient.Builder builder) {
        this(builder.requestFactory(requestFactory()).build());
    }

    /** 全参构造（单测注入受控 RestClient）。 */
    RobotsPolicyClient(RestClient restClient) {
        this.restClient = restClient;
    }

    private static SimpleClientHttpRequestFactory requestFactory() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout((int) ROBOTS_TIMEOUT.toMillis());
        factory.setReadTimeout((int) ROBOTS_TIMEOUT.toMillis());
        return factory;
    }

    @Override
    public RobotsVerdict check(String endpoint) {
        String robotsUrl;
        try {
            robotsUrl = robotsUrlFor(endpoint);
        } catch (IllegalArgumentException e) {
            // 非法端点交由 SourceConfigValidator 字段级提示（30072），此处不重复拦截
            return new RobotsVerdict(true, "端点未通过 URL 校验，跳过 robots 判读");
        }
        String body;
        try {
            body = fetchRobots(robotsUrl);
        } catch (RestClientException | IllegalStateException | java.io.IOException e) {
            log.info("robots 拉取不可达（按无限制处理） {}: {}", robotsUrl, e.getMessage());
            return new RobotsVerdict(true, "robots 不可达（按无限制处理）");
        }
        if (body == null) {
            return new RobotsVerdict(true, "robots 非 200（按无限制处理）");
        }
        URI uri = URI.create(endpoint);
        return evaluate(
                body, uri.getPath() == null || uri.getPath().isEmpty() ? "/" : uri.getPath());
    }

    /** 拉取 robots.txt：2xx 返回正文；其余状态返回 null（按无限制）。 */
    private String fetchRobots(String robotsUrl) throws java.io.IOException {
        return restClient
                .get()
                .uri(robotsUrl)
                .exchange(
                        (request, response) -> {
                            if (!response.getStatusCode().is2xxSuccessful()) {
                                log.info(
                                        "robots 状态 {} → 按无限制处理: {}",
                                        response.getStatusCode(),
                                        robotsUrl);
                                return null;
                            }
                            try (InputStream in = response.getBody()) {
                                return new String(in.readAllBytes(), StandardCharsets.UTF_8);
                            }
                        });
    }

    /**
     * robots 文本评估（纯函数，fixture 单测入口）：解析通配组的 Allow/Disallow，最长匹配规则胜出。 RFC 9309 §2.2.2 子集：前缀匹配； 通配符
     * {@code *} 与行尾锚 {@code $} 不支持（保守方向为放行，全站禁抓类规则必然命中）； 空 {@code Disallow} 值 = 显式放行全部。
     */
    static RobotsVerdict evaluate(String robotsBody, String path) {
        if (robotsBody == null || robotsBody.isBlank()) {
            return new RobotsVerdict(true, "robots 无有效规则（404/空文件）");
        }
        Rule matched = null;
        List<String> groupAgents = new ArrayList<>();
        boolean inRules = false;
        boolean groupApplies = false;
        for (String rawLine : robotsBody.split("\\R")) {
            String line = stripComment(rawLine);
            int colon = line.indexOf(':');
            if (colon < 0) {
                continue;
            }
            String field = line.substring(0, colon).trim().toLowerCase(Locale.ROOT);
            String value = line.substring(colon + 1).trim();
            switch (field) {
                case "user-agent" -> {
                    if (inRules) {
                        // 新组开始：连续 user-agent 行同组，遇规则行后再次出现即开新组
                        groupAgents.clear();
                        inRules = false;
                    }
                    groupAgents.add(value);
                }
                case "allow", "disallow" -> {
                    if (!inRules) {
                        inRules = true;
                        groupApplies = groupAgents.contains("*");
                    }
                    if (!groupApplies || (value.isEmpty() && "allow".equals(field))) {
                        continue;
                    }
                    // 空 Disallow 值 = 放行全部（RFC 9309），以「允许」身份参与最长匹配
                    Rule rule = new Rule("allow".equals(field) || value.isEmpty(), value);
                    if (rule.matches(path) && rule.winsOver(matched)) {
                        matched = rule;
                    }
                }
                default -> {
                    // crawl-delay 等字段忽略
                }
            }
        }
        if (matched == null || matched.allow()) {
            return new RobotsVerdict(
                    true, matched == null ? "robots 未命中规则" : "Allow: " + matched.path());
        }
        return new RobotsVerdict(false, "Disallow: " + matched.path());
    }

    /** 端点 → robots.txt 地址（scheme/host 根路径，保留端口）。 */
    static String robotsUrlFor(String endpoint) {
        URI uri = URI.create(endpoint);
        String scheme = uri.getScheme();
        String host = uri.getHost();
        if (scheme == null || host == null) {
            throw new IllegalArgumentException("端点须为绝对 http(s) URL: " + endpoint);
        }
        int port = uri.getPort();
        return scheme + "://" + host + (port == -1 ? "" : ":" + port) + "/robots.txt";
    }

    private static String stripComment(String rawLine) {
        int hash = rawLine.indexOf('#');
        return hash >= 0 ? rawLine.substring(0, hash) : rawLine;
    }

    /** 单条路径规则（空 path 前缀匹配一切——仅空 Disallow 会构造出该形态）。 */
    private record Rule(boolean allow, String path) {

        boolean matches(String target) {
            return target.startsWith(path);
        }

        /** 最长路径胜出；同长 Allow 胜 Disallow（RFC 9309 平局规则）。 */
        boolean winsOver(Rule other) {
            if (other == null) {
                return true;
            }
            if (path.length() != other.path.length()) {
                return path.length() > other.path.length();
            }
            return allow && !other.allow();
        }
    }
}
