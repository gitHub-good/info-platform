package com.info.platform.infrastructure.aggregation;

import com.info.platform.domain.aggregation.SourceCode;
import com.info.platform.infrastructure.common.ConfigCenter;
import com.info.platform.infrastructure.common.DataSourceDefaults;
import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * 东方财富 datacenter F10 主财务指标 HTTP 客户端（T04 财务源）。
 *
 * <p>封装 GET {@code datacenter-web.eastmoney.com/api/data/v1/get}：按 {@code
 * reportName=RPT_F10_FINANCE_MAINFINADATA} + {@code filter=(SECURITY_CODE="<6 位代码>")}
 * 取最新报告期一条主财务指标（营收/归母净利/净利率/毛利率/ROE/报告期）。 与 {@link EastMoneyClient}（push2
 * 行情/估值）属<b>不同端点/域名</b>，响应结构亦不同（datacenter 返回 {@code result.data[]} 数组， push2 返回 {@code data}
 * 单对象），故单列一类客户端而非塞进 EastMoneyClient。
 *
 * <p>响应结构（2026-09-21 curl 实测，茅台 600519）：
 *
 * <pre>{@code
 * {"version":"...","result":{"pages":103,"data":[{"SECURITY_CODE":"600519",
 *   "TOTALOPERATEREVE":92278072083.21,"PARENTNETPROFIT":44516880421.86,
 *   "XSJLL":50.75,"XSMLL":89.56,"ROEJQ":16.75,"REPORT_DATE":"2026-06-30 00:00:00", ...}]},
 *  "data":null,"success":true,"message":"ok","code":0}
 * }</pre>
 *
 * <p>导航 {@code root.result.data[0]} 为原始字段 map。字段语义依据 Spike-1 §2.2/§4.2（🟢 实跑确认）。
 *
 * <p>软限频：东财 datacenter 无 token、按 IP 软限，请求需带浏览器 User-Agent + {@code Referer:
 * https://data.eastmoney.com/}（对齐 Spike-1 §6.2；UA 为 ISSUE-A 实测补齐——WAF 对裸 UA 断连）。 响应实测以 {@code
 * text/plain;charset=UTF-8} 声明返回 JSON 体，容错读见 {@link EastMoneyHttpSupport}（ISSUE-B）。 超时不在此设——由
 * {@link com.info.platform.infrastructure.common.ResilienceRunner}（2s 重试 0）兜底。 HTTP 异常直接抛出，由模板层降级。
 *
 * <p>filter 中双引号须 {@code %22} 编码（实测字面双引号返回 HTTP 400）；圆括号/等号字面 eastmoney 接受。 手工拼接 query 串而非 {@code
 * UriComponentsBuilder.build(true)}：后者经 {@code RestClient.uri(String)→URI.create} 解析时对已编码 {@code
 * %22} 行为不稳、 抛 IllegalArgumentException 致 doFetch 降级（MockRestServiceServer 下复现）。{@code
 * reportName}/{@code sortColumns} 等为 API 契约常量 （同 push2 的 fltt/invt）。
 */
@Component
public class EastMoneyFinanceClient {

    private static final Logger log = LoggerFactory.getLogger(EastMoneyFinanceClient.class);

    /** 东财 F10 主财务指标报表名（API 契约常量）。 */
    private static final String REPORT_NAME = "RPT_F10_FINANCE_MAINFINADATA";

    /** 东财软限频要求的来源页（无 token，靠 Referer 标识来源；缺省取 {@code DataSourceDefaults}）。 */
    private static final String DEFAULT_REFERER =
            DataSourceDefaults.paramString(SourceCode.FINANCE, "financeReferer");

    private static final String DEFAULT_FINANCE_URL =
            DataSourceDefaults.paramString(SourceCode.FINANCE, "financeUrl");

    private final RestClient restClient;
    private final String financeUrl;
    private final String referer;

    /** 配置中心（T36 热化）：null（纯构造单测）时回落构造期缺省。 */
    @Autowired(required = false)
    ConfigCenter configCenter;

    /** Spring 装配构造（ADR-0032）：回落值取 {@link DataSourceDefaults} 代码内置缺省（原 yml adapter 段迁移）。 */
    @Autowired
    public EastMoneyFinanceClient(RestClient.Builder restClientBuilder) {
        this(restClientBuilder, DEFAULT_FINANCE_URL, DEFAULT_REFERER);
    }

    /** 全参构造（纯构造单测指定回落值）。 */
    public EastMoneyFinanceClient(
            RestClient.Builder restClientBuilder, String financeUrl, String referer) {
        this.restClient = EastMoneyHttpSupport.withTextPlainJson(restClientBuilder).build();
        this.financeUrl = financeUrl;
        this.referer = referer;
    }

    /**
     * 取某 6 位证券代码最新报告期一条主财务指标。
     *
     * @param securityCode 6 位证券代码，如 {@code 600519}（沪）/ {@code 000001}（深）。非 secid。
     * @return {@code result.data[0]} 原始字段 map；无数据（result/data 为空）时返回 {@link Optional#empty()}
     */
    public Optional<Map<String, Object>> fetchFinance(String securityCode) {
        String financeUrl =
                RuntimeParams.of(configCenter, SourceCode.FINANCE, "financeUrl", this.financeUrl);
        String referer =
                RuntimeParams.of(configCenter, SourceCode.FINANCE, "financeReferer", this.referer);
        String url = buildUrl(financeUrl, securityCode);
        log.debug("东财财务请求 securityCode={}", securityCode);
        Map<String, Object> root =
                restClient
                        .get()
                        .uri(URI.create(url))
                        .accept(MediaType.APPLICATION_JSON)
                        .header("User-Agent", EastMoneyHttpSupport.USER_AGENT)
                        .header("Referer", referer)
                        .retrieve()
                        .body(new ParameterizedTypeReference<Map<String, Object>>() {});
        return extractFirstRow(root);
    }

    /** 导航 {@code root.result.data[0]}；任一层缺失/空数组返回 {@link Optional#empty()}（→ MISSING）。 */
    @SuppressWarnings("unchecked")
    private static Optional<Map<String, Object>> extractFirstRow(Map<String, Object> root) {
        if (root == null) {
            return Optional.empty();
        }
        Object result = root.get("result");
        if (!(result instanceof Map<?, ?> resultMap)) {
            return Optional.empty();
        }
        Object data = resultMap.get("data");
        if (!(data instanceof List<?> list) || list.isEmpty()) {
            return Optional.empty();
        }
        Object first = list.get(0);
        if (!(first instanceof Map<?, ?>)) {
            return Optional.empty();
        }
        return Optional.of((Map<String, Object>) first);
    }

    private String buildUrl(String financeUrl, String securityCode) {
        // 双引号须 %22 编码（实测字面双引号 → HTTP 400）；圆括号/等号字面 eastmoney 接受（与实测可用 curl 一致）。
        // 手工拼接 query 串而非 UriComponentsBuilder.build(true)：后者经 RestClient.uri(String)→URI.create 解析时
        // 对已编码 %22 行为不稳、抛 IllegalArgumentException 致 doFetch 降级（MockRestServiceServer
        // 下复现）。securityCode
        // 仅为 6 位数字，无注入/编码风险。
        String filter = "(SECURITY_CODE=%22" + securityCode + "%22)";
        return financeUrl
                + "?reportName="
                + REPORT_NAME
                + "&columns=ALL"
                + "&filter="
                + filter
                + "&pageNumber=1"
                + "&pageSize=1"
                + "&sortColumns=REPORT_DATE"
                + "&sortTypes=-1";
    }
}
