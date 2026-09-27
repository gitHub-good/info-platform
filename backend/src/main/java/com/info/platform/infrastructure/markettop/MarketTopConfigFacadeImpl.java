package com.info.platform.infrastructure.markettop;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.info.platform.application.common.RuntimeConfigEntry;
import com.info.platform.application.common.RuntimeConfigService;
import com.info.platform.application.markettop.MarketTopConfig;
import com.info.platform.application.markettop.MarketTopConfigFacade;
import com.info.platform.application.markettop.MarketTopConfigSettings;
import com.info.platform.application.markettop.MarketTopConfigValidator;
import com.info.platform.domain.common.BusinessException;
import com.info.platform.domain.common.ErrorCode;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import org.springframework.stereotype.Component;

/**
 * {@link MarketTopConfigFacade} 实现（M21 T181，方案 §4.7.3；ScoreWeightConfigFacadeImpl
 * 先例）。读：runtime_config 快照现读 + {@link MarketTopConfigSettings} 字段级回退解析。写：5 字段拼全量文档（null 不拼入——校验器
 * 30091「必填」拦截；非数值类型原样透传 ——校验器字段级拦截）→ {@link RuntimeConfigService#write}（校验 30091 + 乐观防呆 30065 +
 * 换快照热生效），写后回读刷新视图。
 */
@Component
public class MarketTopConfigFacadeImpl implements MarketTopConfigFacade {

    private final RuntimeConfigService configService;
    private final MarketTopConfigSettings settings;
    private final ObjectMapper objectMapper;

    public MarketTopConfigFacadeImpl(
            RuntimeConfigService configService,
            MarketTopConfigSettings settings,
            ObjectMapper objectMapper) {
        this.configService = configService;
        this.settings = settings;
        this.objectMapper = objectMapper;
    }

    @Override
    public ConfigView view() {
        RuntimeConfigEntry entry =
                configService.read(MarketTopConfigValidator.CONFIG_KEY).orElse(null);
        MarketTopConfig config = settings.current();
        return new ConfigView(
                config.poolSize(),
                config.deepDiveLimit(),
                config.deepDiveCostCapRatio(),
                config.diveCostEstimateMicros(),
                config.memberCoverageFloor(),
                entry == null ? null : entry.updatedAt().toString());
    }

    @Override
    public ConfigView update(ConfigUpdate update) {
        ObjectNode doc = objectMapper.createObjectNode();
        putIfPresent(doc, "poolSize", update.poolSize());
        putIfPresent(doc, "deepDiveLimit", update.deepDiveLimit());
        putIfPresent(doc, "deepDiveCostCapRatio", update.deepDiveCostCapRatio());
        putIfPresent(doc, "diveCostEstimateMicros", update.diveCostEstimateMicros());
        putIfPresent(doc, "memberCoverageFloor", update.memberCoverageFloor());
        configService.write(
                MarketTopConfigValidator.CONFIG_KEY,
                doc.toString(),
                parseExpected(update.expectedUpdatedAt()));
        // write 成功后快照已换新——回读刷新视图（含新 updatedAt 供下次防呆比对）
        return view();
    }

    /**
     * 原样透传节点（D3 先例）：合法数值原样落文档；非数值类型也保留，由写路径校验器严格判定（30091 字段级——单一事实源）。 缺失/JSON null 不拼入（校验器「必填」拦截）。
     */
    private void putIfPresent(ObjectNode doc, String field, JsonNode value) {
        if (value != null && !value.isNull()) {
            doc.set(field, value);
        }
    }

    private Instant parseExpected(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return Instant.parse(raw);
        } catch (DateTimeParseException e) {
            throw new BusinessException(
                    ErrorCode.PARAM_INVALID,
                    "expectedUpdatedAt: 须为 ISO-8601 时刻（如 2026-09-22T01:00:00Z）");
        }
    }
}
