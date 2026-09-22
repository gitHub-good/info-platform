package com.info.platform.infrastructure.common;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.info.platform.application.common.RuntimeConfigSeed;
import com.info.platform.application.common.RuntimeConfigSeeder;
import com.info.platform.domain.aggregation.SourceCode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 数据源域种子（{@code datasource.{SOURCE_CODE}} 7 键，T36）。
 *
 * <p>首启从 yml / 代码缺省导入（DB 已有键不动，页面改过即权威）：超时/重试/缓存 TTL 取 {@link DataSourceDefaults}（原各
 * adapter/SourceCache 硬编码提取）；params（URL/条数/referer）取 {@code adapter.*} yml 绑定值（原各 client
 * 构造期 @Value）；mode 取全局 {@code adapter.mock.enabled}（true → 各源初始 MOCK，ADR-0017 冲突解法 1 的语义平移）；enabled
 * 恒 true。
 */
@Component
public class DataSourceRuntimeConfigSeeder implements RuntimeConfigSeeder {

    private final ObjectMapper objectMapper;

    @Value("${adapter.mock.enabled:true}")
    private boolean mockEnabled;

    @Value("${adapter.eastmoney.quote-url:https://push2.eastmoney.com/api/qt/stock/get}")
    private String quoteUrl;

    @Value("${adapter.eastmoney.fields:f43,f44,f45,f46,f47,f48,f57,f58,f60,f168,f169,f170,f171}")
    private String quoteFields;

    @Value("${adapter.eastmoney.valuation-fields:f57,f162,f167}")
    private String valuationFields;

    @Value("${adapter.eastmoney.finance-url:https://datacenter-web.eastmoney.com/api/data/v1/get}")
    private String financeUrl;

    @Value("${adapter.eastmoney.finance-referer:https://data.eastmoney.com/}")
    private String financeReferer;

    @Value(
            "${adapter.eastmoney.announce-url:https://np-anotice-stock.eastmoney.com/api/security/ann}")
    private String announceUrl;

    @Value("${adapter.eastmoney.announce-page-size:3}")
    private int announcePageSize;

    @Value(
            "${adapter.eastmoney.announce-detail-url-template:https://pdf.dfcfw.com/pdf/H2_{art_code}_1.pdf}")
    private String announceDetailUrlTemplate;

    @Value("${adapter.sina.news-url:https://feed.mix.sina.com.cn/api/roll/get}")
    private String newsUrl;

    @Value("${adapter.sina.news-page-id:153}")
    private int newsPageId;

    @Value("${adapter.sina.news-lid:2510}")
    private int newsLid;

    @Value("${adapter.sina.news-page-size:20}")
    private int newsPageSize;

    @Value("${adapter.sina.news-referer:https://finance.sina.com.cn}")
    private String newsReferer;

    @Value("${adapter.gov.policy-url:https://www.gov.cn/zhengce/}")
    private String policyUrl;

    @Value("${adapter.gov.policy-referer:https://www.gov.cn/}")
    private String policyReferer;

    public DataSourceRuntimeConfigSeeder(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public List<RuntimeConfigSeed> seeds() {
        List<RuntimeConfigSeed> seeds = new ArrayList<>();
        for (SourceCode code : SourceCode.values()) {
            seeds.add(seedOf(code));
        }
        return seeds;
    }

    private RuntimeConfigSeed seedOf(SourceCode code) {
        Map<String, Object> doc = new LinkedHashMap<>();
        doc.put("enabled", true);
        doc.put(
                "mode",
                mockEnabled
                        ? RuntimeDataSource.Mode.MOCK.name()
                        : RuntimeDataSource.Mode.REAL.name());
        doc.put("timeoutMillis", DataSourceDefaults.timeoutMillis(code));
        doc.put("retries", DataSourceDefaults.RETRIES_NONE);
        doc.put("cacheTtlSeconds", DataSourceDefaults.cacheTtlSeconds(code));
        doc.put("params", paramsOf(code));
        return new RuntimeConfigSeed(
                ConfigCenter.KEY_DATASOURCE_PREFIX + code.name(),
                write(doc),
                "数据源 " + code.name() + "（开关/模式/超时/重试/缓存 TTL/外呼参数）");
    }

    private Map<String, Object> paramsOf(SourceCode code) {
        Map<String, Object> params = new LinkedHashMap<>();
        switch (code) {
            case QUOTE -> {
                params.put("quoteUrl", quoteUrl);
                params.put("fields", quoteFields);
            }
            case FINANCE -> {
                params.put("financeUrl", financeUrl);
                params.put("financeReferer", financeReferer);
            }
            case VALUATION -> {
                params.put("quoteUrl", quoteUrl);
                params.put("valuationFields", valuationFields);
            }
            case ANNOUNCE -> {
                params.put("announceUrl", announceUrl);
                params.put("announcePageSize", announcePageSize);
                params.put("announceDetailUrlTemplate", announceDetailUrlTemplate);
            }
            case NEWS -> {
                params.put("newsUrl", newsUrl);
                params.put("newsPageId", newsPageId);
                params.put("newsLid", newsLid);
                params.put("newsPageSize", newsPageSize);
                params.put("newsReferer", newsReferer);
            }
            case POLICY -> {
                params.put("policyUrl", policyUrl);
                params.put("policyReferer", policyReferer);
            }
            case EVENT -> {
                // 事件源读本地 anomaly_event 表（ADR-0013），无外呼参数
            }
        }
        return params;
    }

    private String write(Map<String, Object> doc) {
        try {
            return objectMapper.writeValueAsString(doc);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("数据源配置种子序列化失败: " + e.getOriginalMessage(), e);
        }
    }
}
