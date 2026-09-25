package com.info.platform.infrastructure.feed;

import com.info.platform.domain.feed.InfoSource;
import com.info.platform.domain.feed.InfoSourceRepository;
import com.info.platform.domain.feed.SourcePollStateRepository;
import java.time.Clock;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * 预置源种子（M13 T100，ADR-0032 同系列）：启动 seed-if-absent——按 {@code source_code} 插入 {@link
 * InfoSourceCatalog} 预置源（存量行不覆盖，DB 为权威），并为新行初始化 {@code source_poll_state}。
 *
 * <p>错峰（ADR-0040）：{@code next_due_at = now + abs(hash(source_code)) mod interval}——同批种子在各自间隔窗内散开，
 * 防首启/重启后齐发。用户通用源不经种子（注册服务落库时自建运行态行）。
 *
 * <p>时序：{@code @Order(50)} 晚于配置中心种子导入（@Order(0)）、早于调度中心注册（@Order(100)）—— 首个 tick 读到的即完整源清单。
 */
@Component
public class InfoSourceSeeder {

    private static final Logger log = LoggerFactory.getLogger(InfoSourceSeeder.class);

    private final InfoSourceRepository infoSourceRepository;
    private final SourcePollStateRepository stateRepository;
    private final SourceConfigCodec codec = new SourceConfigCodec();
    private final Clock clock;

    public InfoSourceSeeder(
            InfoSourceRepository infoSourceRepository,
            SourcePollStateRepository stateRepository,
            Clock clock) {
        this.infoSourceRepository = infoSourceRepository;
        this.stateRepository = stateRepository;
        this.clock = clock;
    }

    @EventListener(ApplicationReadyEvent.class)
    @Order(50)
    public void onApplicationReady() {
        seedIfAbsent();
    }

    /** 幂等播种：预置源 INSERT OR IGNORE（按 source_code）+ 新行初始化运行态（hash 错峰）。 */
    public void seedIfAbsent() {
        int seeded = 0;
        for (InfoSourceCatalog.PresetEntry entry : InfoSourceCatalog.presets()) {
            InfoSource source =
                    InfoSource.create(
                            entry.sourceCode(),
                            entry.name(),
                            entry.category(),
                            entry.adapterType(),
                            entry.adapterRef(),
                            entry.endpoint(),
                            codec.parse(entry.configJson()),
                            entry.intervalMinutes(),
                            true,
                            true);
            if (infoSourceRepository.insertIfAbsent(source)) {
                Instant now = clock.instant();
                stateRepository.insertIfAbsent(
                        source.getId(),
                        staggeredNextDue(entry.sourceCode(), entry.intervalMinutes(), now),
                        now);
                seeded++;
            }
        }
        if (seeded > 0) {
            log.info("预置资讯源种子新增 {} 个（存量行不动，DB 为权威）", seeded);
        }
    }

    /** 错峰：now + |hash(source_code)| mod interval 分钟（确定性散列，重启稳定不漂移）。 */
    static Instant staggeredNextDue(String sourceCode, int intervalMinutes, Instant now) {
        int offset = Math.abs(sourceCode.hashCode()) % Math.max(1, intervalMinutes);
        return now.plusSeconds(offset * 60L);
    }
}
