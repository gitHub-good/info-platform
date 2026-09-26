package com.info.platform.application.valuation;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 行情批量取数端口（M20 T170，方案 §4.6 阶段 0）：应用层编排依赖端口、基础设施层以腾讯实现适配 （{@code
 * infrastructure.valuation.TencentQuoteBatchClient} 委托既有 {@code TencentQuoteClient}， ADR-0031
 * 备选源复用——分层依赖方向守护 LayeredArchitectureTest）。返回行 = 东财 f 键中间结构 （f43 现价 / f170 涨跌幅 / f168 换手 / f171 振幅
 * / f47 量 / f162 PE / f167 PB / f30 源时间戳）。
 */
public interface QuoteBatchClient {

    /**
     * 批量取数（实现自控分块与节流；无效符号行缺席即无键，不视为错误）。
     *
     * @param tencentSymbols 腾讯符号列表（如 {@code sh600519}）
     * @return 键 = 响应行自带符号 → f 键映射
     */
    Map<String, Map<String, Object>> fetchQuotes(List<String> tencentSymbols);

    /** 内部标的码（SH600519 型）→ 腾讯符号（sh600519 型）；前缀不支持返回 empty。 */
    Optional<String> toTencentSymbol(String subjectCode);
}
