package com.info.platform.infrastructure.feed;

import com.info.platform.domain.feed.FetchContext;
import com.info.platform.domain.feed.FetchResult;
import com.info.platform.domain.feed.InfoSource;
import com.info.platform.domain.feed.RawFeedItem;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jsoup.Jsoup;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * 预置 HTML 列表页适配器基类（M14 T111/T112，REQ-20260925-11 拍板一 #4~#9）：官方政策源与报纸媒体源共用的取数管线 与中文墙钟解析。
 *
 * <p>六源均为服务端渲染列表页（jsoup 解析标题/链接/时间），无翻页参数形态（首页块/首屏列表即窗口）， 单轮一页、 cursorType=NONE（增量靠 (source_id,
 * external_id) 唯一索引幂等吸收，裁量记录 ADR-0044：日期粒度过粗 / 列表乱序 / 时间相对化，TIME/ID 游标的「遇已见止」会漏同粒度新条目）。
 *
 * <p>时间口径：源侧均为国内站点墙钟（Asia/Shanghai）→ Instant；相对时间（「N小时前」「昨天 HH:mm」）以注入 {@link Clock}
 * 的即时折算，单测可固定时钟（04 测试规范「时间要 mock」）；无年份日期取时钟当年（跨年回看 -1 年防未来时刻）。
 */
abstract class AbstractHtmlListFeedAdapter implements PresetFeedAdapter {

    /** 源侧墙钟时区（六源均为北京时间口径）。 */
    static final ZoneId SHANGHAI = ZoneId.of("Asia/Shanghai");

    /** 每源连接/读取超时（方案 §5：单源超时 5s）。 */
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(2);

    private static final Duration READ_TIMEOUT = Duration.ofSeconds(5);

    /** 礼貌抓取浏览器 UA（与既有预置源一致口径）。 */
    static final String USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko)"
                    + " Chrome/120 Safari/537.36";

    /** 标题清洗后截断上限（标题级入库，沿 SinaZhiboAdapter 口径）。 */
    static final int TITLE_MAX_CHARS = 300;

    /** 摘要截断上限（news_item.summary 为 TEXT 无硬限，防超长摘要放大单轮写入）。 */
    static final int SUMMARY_MAX_CHARS = 500;

    /** 相对时间：N分钟前 / N小时前 / N天前。 */
    private static final Pattern RELATIVE = Pattern.compile("^(\\d{1,4})(分钟|小时|天)前$");

    /** 绝对日期（可带时间）：yyyy-MM-dd / yyyy/MM/dd [HH:mm]。 */
    private static final Pattern ABSOLUTE =
            Pattern.compile(
                    "^(\\d{4})[-/](\\d{1,2})[-/](\\d{1,2})(?:[ \\t]+(\\d{1,2}):(\\d{2}))?$");

    /** 无年份日期：MM-dd [HH:mm]（年份取时钟当年）。 */
    private static final Pattern MONTH_DAY =
            Pattern.compile("^(\\d{1,2})-(\\d{1,2})(?:[ \\t]+(\\d{1,2}):(\\d{2}))?$");

    /** 当天时间：HH:mm（跨零点回看 -1 天防未来时刻）。 */
    private static final Pattern TIME_ONLY = Pattern.compile("^(\\d{1,2}):(\\d{2})$");

    /** 昨天 HH:mm。 */
    private static final Pattern YESTERDAY = Pattern.compile("^昨天[ \\t]*(\\d{1,2}):(\\d{2})$");

    private final RestClient restClient;

    private final Clock clock;

    protected AbstractHtmlListFeedAdapter(RestClient restClient, Clock clock) {
        this.restClient = restClient;
        this.clock = clock;
    }

    /** 可测时钟（相对时间/当年推断的基准）。 */
    protected final Clock clock() {
        return clock;
    }

    /** 生产装配用请求工厂（2s/5s 超时）。 */
    static SimpleClientHttpRequestFactory requestFactory() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout((int) CONNECT_TIMEOUT.toMillis());
        factory.setReadTimeout((int) READ_TIMEOUT.toMillis());
        return factory;
    }

    /** GET 列表页 HTML（UA/Referer 礼貌头；空响应/HTTP 失败抛 {@link FeedFetchException} 带源与 URL 上下文）。 */
    final String httpGet(InfoSource source, String referer) {
        String url = source.getEndpoint();
        try {
            String body =
                    restClient
                            .get()
                            .uri(url)
                            .header("User-Agent", USER_AGENT)
                            .header("Referer", referer)
                            .retrieve()
                            .body(String.class);
            if (body == null || body.isBlank()) {
                throw new FeedFetchException(
                        source.getName() + " 空响应 " + source.getSourceCode() + " " + url);
            }
            return body;
        } catch (RestClientException e) {
            throw new FeedFetchException(
                    source.getName()
                            + " 取数失败 "
                            + source.getSourceCode()
                            + " "
                            + url
                            + ": "
                            + e.getMessage(),
                    e);
        }
    }

    /** 单页取数（六源无翻页形态：首页块/首屏列表即增量窗口，重复轮由唯一索引去重）。 */
    @Override
    public FetchResult fetch(InfoSource source, FetchContext context) {
        return FetchResult.of(parseList(httpGet(source, referer()), source));
    }

    /** 列表页 HTML → 条目（包内可见，fixture 单测直调零外呼）。 */
    abstract List<RawFeedItem> parseList(String body, InfoSource source);

    /** 源站 Referer（礼貌头，子类声明）。 */
    abstract String referer();

    /**
     * 中文墙钟文本 → Instant（Asia/Shanghai）：支持 yyyy[-/]MM[-/]dd[ HH:mm]、MM-dd[ HH:mm]（年份取时钟当年， 结果晚于时钟 +1
     * 天则回看上一年——12 月末条目在 1 月初被抓的跨年边界）、HH:mm（当天，晚于时钟则回看前一天—— 零点后抓到昨夜条目）、昨天
     * HH:mm、N分钟前/N小时前/N天前（相对时钟即时）。不可识别返回 null（发布时间回落 抓取时刻归摄取层）。
     */
    static final Instant parseChineseWallClock(String raw, Clock clock) {
        if (raw == null) {
            return null;
        }
        String text = raw.strip().replace('\u3000', ' ').trim();
        if (text.isEmpty()) {
            return null;
        }
        Instant now = clock.instant();
        Matcher relative = RELATIVE.matcher(text);
        if (relative.matches()) {
            long amount = Long.parseLong(relative.group(1));
            return switch (relative.group(2)) {
                case "分钟" -> now.minus(Duration.ofMinutes(amount));
                case "小时" -> now.minus(Duration.ofHours(amount));
                default -> now.minus(Duration.ofDays(amount));
            };
        }
        Matcher yesterday = YESTERDAY.matcher(text);
        if (yesterday.matches()) {
            return shanghaiDate(now, 1)
                    .atTime(LocalTime.of(hour(yesterday.group(1)), minute(yesterday.group(2))))
                    .atZone(SHANGHAI)
                    .toInstant();
        }
        Matcher absolute = ABSOLUTE.matcher(text);
        if (absolute.matches()) {
            LocalDate date =
                    LocalDate.of(
                            Integer.parseInt(absolute.group(1)),
                            Integer.parseInt(absolute.group(2)),
                            Integer.parseInt(absolute.group(3)));
            LocalTime time =
                    absolute.group(4) == null
                            ? LocalTime.MIDNIGHT
                            : LocalTime.of(hour(absolute.group(4)), minute(absolute.group(5)));
            return date.atTime(time).atZone(SHANGHAI).toInstant();
        }
        Matcher monthDay = MONTH_DAY.matcher(text);
        if (monthDay.matches()) {
            int month = Integer.parseInt(monthDay.group(1));
            int day = Integer.parseInt(monthDay.group(2));
            LocalDate date = LocalDate.of(now.atZone(SHANGHAI).getYear(), month, day);
            if (date.atStartOfDay(SHANGHAI).toInstant().isAfter(now.plus(Duration.ofDays(1)))) {
                date = date.minusYears(1);
            }
            LocalTime time =
                    monthDay.group(3) == null
                            ? LocalTime.MIDNIGHT
                            : LocalTime.of(hour(monthDay.group(3)), minute(monthDay.group(4)));
            return date.atTime(time).atZone(SHANGHAI).toInstant();
        }
        Matcher timeOnly = TIME_ONLY.matcher(text);
        if (timeOnly.matches()) {
            LocalDate today = shanghaiDate(now, 0);
            LocalTime time = LocalTime.of(hour(timeOnly.group(1)), minute(timeOnly.group(2)));
            Instant candidate = today.atTime(time).atZone(SHANGHAI).toInstant();
            if (candidate.isAfter(now)) {
                candidate = shanghaiDate(now, 1).atTime(time).atZone(SHANGHAI).toInstant();
            }
            return candidate;
        }
        return null;
    }

    /** 时钟即时在源时区的日期回退天（0=今天，1=昨天）。 */
    private static LocalDate shanghaiDate(Instant now, long daysBack) {
        return now.atZone(SHANGHAI).toLocalDate().minusDays(daysBack);
    }

    private static int hour(String raw) {
        return Integer.parseInt(raw);
    }

    private static int minute(String raw) {
        return Integer.parseInt(raw);
    }

    /** 清洗并截断标题（jsoup 文本化）；清洗后为空返回 null。 */
    static String cleanTitle(String raw) {
        return truncate(Jsoup.parse(raw == null ? "" : raw).text().strip(), TITLE_MAX_CHARS);
    }

    /** 清洗并截断摘要；清洗后为空返回 null。 */
    static String cleanSummary(String raw) {
        return truncate(Jsoup.parse(raw == null ? "" : raw).text().strip(), SUMMARY_MAX_CHARS);
    }

    private static String truncate(String text, int maxChars) {
        if (text == null || text.isEmpty()) {
            return null;
        }
        return text.length() <= maxChars ? text : text.substring(0, maxChars);
    }

    /** 按 externalId 去重保序（首页/列表页可能多处出现同条目，首现优先）。 */
    static List<RawFeedItem> dedupeByExternalId(List<RawFeedItem> items) {
        LinkedHashMap<String, RawFeedItem> byId = new LinkedHashMap<>();
        for (RawFeedItem item : items) {
            byId.putIfAbsent(item.externalId(), item);
        }
        return List.copyOf(new ArrayList<>(byId.values()));
    }
}
