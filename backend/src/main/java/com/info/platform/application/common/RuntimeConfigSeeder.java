package com.info.platform.application.common;

import java.util.List;

/**
 * 运行时配置种子提供方（应用层端口，T34）。
 *
 * <p>各域把「yml/代码缺省值 → 键空间条目」的映射实现为本接口的 Spring bean，由 {@code ConfigCenter} 在启动就绪时 汇总执行 seed-if-absent
 * 导入（DB 已有键不动）。 本批提供 LLM / 任务 / 聚合三个种子源；数据源域种子随 T36 落地（其 超时/重试/缓存 TTL 当前硬编码在各
 * adapter，热化时一并提取，避免先行种子与权威值漂移）。
 */
public interface RuntimeConfigSeeder {

    /** 该域的种子条目（键 + JSON 值 + 人读说明）。 */
    List<RuntimeConfigSeed> seeds();
}
