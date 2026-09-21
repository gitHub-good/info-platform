package com.info.platform.infrastructure.common;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 时钟 Bean（基础设施层横切）。
 *
 * <p>统一注入 {@link Clock} 供需要「可测时间」的应用层组件使用——单元测试可注入 {@link Clock#fixed} 固定时间， 满足 04 测试规范「时间/随机数要
 * mock 或固定种子」的可重复性要求。生产用 {@link Clock#systemUTC()}（与全局 {@code Instant.now()} 一致，UTC 即时）。
 */
@Configuration
public class ClockConfig {

    @Bean
    public Clock systemClock() {
        return Clock.systemUTC();
    }
}
