package com.info.platform;

import org.apache.ibatis.annotations.Mapper;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 信息整合与 AI 分析平台后端入口。
 *
 * <p>模块化单体（ADR-0001）+ DDD 四层（ADR-0007）。Mapper 实现统一落在基础设施层 {@code infrastructure} 下各业务域的 persistence
 * 子包，这里集中扫描。
 *
 * <p>{@code annotationClass = Mapper.class} 只扫描带 {@link Mapper} 的接口，避免把基础设施层其它纯端口接口（如 {@code
 * CircuitBreaker}）误当 Mapper 注册。
 */
@SpringBootApplication
@MapperScan(basePackages = "com.info.platform.infrastructure", annotationClass = Mapper.class)
public class InfoPlatformApplication {

    public static void main(String[] args) {
        SpringApplication.run(InfoPlatformApplication.class, args);
    }
}
