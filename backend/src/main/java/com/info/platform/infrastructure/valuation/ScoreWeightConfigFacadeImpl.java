package com.info.platform.infrastructure.valuation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.info.platform.application.common.RuntimeConfigEntry;
import com.info.platform.application.common.RuntimeConfigService;
import com.info.platform.application.valuation.ScoreWeightConfigFacade;
import com.info.platform.application.valuation.ValuationConfigValidator;
import com.info.platform.application.valuation.ValuationSettings;
import com.info.platform.domain.common.BusinessException;
import com.info.platform.domain.common.ErrorCode;
import com.info.platform.domain.valuation.ValuationParams;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import org.springframework.stereotype.Component;

/**
 * {@link ScoreWeightConfigFacade} 实现（M20 T172，方案 §4.7.3；RetentionConfigFacadeImpl
 * 先例）。读：runtime_config 快照现读 + {@link ValuationSettings} 字段级回退解析 + basis 由参数派生。写：13 字段拼全量文档（null
 * 不拼入——校验器 30087「必填」 拦截；非数值类型原样透传——校验器字段级拦截）→ {@link RuntimeConfigService#write}（校验 30087 + 乐观防呆
 * 30065 + 换快照热生效）， 写后回读刷新视图（含新 updatedAt 供下次防呆比对）。
 */
@Component
public class ScoreWeightConfigFacadeImpl implements ScoreWeightConfigFacade {

    private final RuntimeConfigService configService;
    private final ValuationSettings valuationSettings;
    private final ObjectMapper objectMapper;

    public ScoreWeightConfigFacadeImpl(
            RuntimeConfigService configService,
            ValuationSettings valuationSettings,
            ObjectMapper objectMapper) {
        this.configService = configService;
        this.valuationSettings = valuationSettings;
        this.objectMapper = objectMapper;
    }

    @Override
    public WeightsView view() {
        RuntimeConfigEntry entry =
                configService.read(ValuationConfigValidator.CONFIG_KEY).orElse(null);
        ValuationParams params = valuationSettings.params();
        return new WeightsView(
                params.wCatalyst(),
                params.wConduction(),
                params.wFundamental(),
                params.wRisk(),
                params.wValuation(),
                params.catalystWindowDays(),
                params.assocWindowDays(),
                params.halfLifeDays(),
                params.k1Saturation(),
                params.k3Saturation(),
                params.btCatalystMin(),
                params.btConductionMin(),
                params.btRiskMin(),
                params.basis(),
                entry == null ? null : entry.updatedAt().toString());
    }

    @Override
    public WeightsView update(WeightsUpdate update) {
        ObjectNode doc = objectMapper.createObjectNode();
        putIfPresent(doc, "wCatalyst", update.wCatalyst());
        putIfPresent(doc, "wConduction", update.wConduction());
        putIfPresent(doc, "wFundamental", update.wFundamental());
        putIfPresent(doc, "wRisk", update.wRisk());
        putIfPresent(doc, "wValuation", update.wValuation());
        putIfPresent(doc, "catalystWindowDays", update.catalystWindowDays());
        putIfPresent(doc, "assocWindowDays", update.assocWindowDays());
        putIfPresent(doc, "halfLifeDays", update.halfLifeDays());
        putIfPresent(doc, "k1Saturation", update.k1Saturation());
        putIfPresent(doc, "k3Saturation", update.k3Saturation());
        putIfPresent(doc, "btCatalystMin", update.btCatalystMin());
        putIfPresent(doc, "btConductionMin", update.btConductionMin());
        putIfPresent(doc, "btRiskMin", update.btRiskMin());
        configService.write(
                ValuationConfigValidator.CONFIG_KEY,
                doc.toString(),
                parseExpected(update.expectedUpdatedAt()));
        // write 成功后快照已换新——回读刷新视图（含新 updatedAt 供下次防呆比对）
        return view();
    }

    /**
     * 原样透传节点（RetentionConfigFacade D3 先例）：合法数值原样落文档；字符串/布尔等非数值类型也保留， 由写路径校验器按「JSON 数值/整数」严格判定（30087
     * 字段级——单一事实源，不在门面重复类型判定）。缺失/JSON null 不拼入（校验器「必填」拦截）。
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
