package com.info.platform.infrastructure.mainline;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.info.platform.application.common.RuntimeConfigEntry;
import com.info.platform.application.common.RuntimeConfigService;
import com.info.platform.application.mainline.IndustryMainlineConfigFacade;
import com.info.platform.application.mainline.IndustryMainlineConfigFacade.ConfigUpdate;
import com.info.platform.application.mainline.IndustryMainlineConfigFacade.ConfigView;
import com.info.platform.application.mainline.IndustryMainlineSettings;
import com.info.platform.domain.common.BusinessException;
import com.info.platform.domain.common.ErrorCode;
import com.info.platform.domain.mainline.MainlineCalculator.Params;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import org.springframework.stereotype.Component;

/**
 * {@link IndustryMainlineConfigFacade} 实现（M27 T243；MarketTopConfigFacadeImpl 先例）。读：runtime_config
 * 快照现读 + {@link IndustryMainlineSettings} 字段级回退解析。写：两键分别拼全量文档（null 不拼入——校验器 30096「必填」拦截；非数值类型原样
 * 透传——校验器字段级拦截）→ {@link RuntimeConfigService#write}（校验 30096 + 乐观防呆 30065 + 换快照热生效），写后回读刷新视图。
 */
@Component
public class IndustryMainlineConfigFacadeImpl implements IndustryMainlineConfigFacade {

    private final RuntimeConfigService configService;

    private final IndustryMainlineSettings settings;

    private final ObjectMapper objectMapper;

    public IndustryMainlineConfigFacadeImpl(
            RuntimeConfigService configService,
            IndustryMainlineSettings settings,
            ObjectMapper objectMapper) {
        this.configService = configService;
        this.settings = settings;
        this.objectMapper = objectMapper;
    }

    @Override
    public ConfigView view() {
        return new ConfigView(mainlineView(), leaderView());
    }

    @Override
    public ConfigView update(ConfigUpdate update) {
        Instant expected = parseExpected(update.expectedUpdatedAt());
        ObjectNode mainline = objectMapper.createObjectNode();
        putIfPresent(mainline, "wp", update.wp());
        putIfPresent(mainline, "wh", update.wh());
        putIfPresent(mainline, "we", update.we());
        putIfPresent(mainline, "priceWinDay", update.priceWinDay());
        putIfPresent(mainline, "priceWinD5", update.priceWinD5());
        putIfPresent(mainline, "heatH24", update.heatH24());
        putIfPresent(mainline, "heatD7", update.heatD7());
        putIfPresent(mainline, "heatDelta", update.heatDelta());
        putIfPresent(mainline, "topN", update.topN());
        putIfPresent(mainline, "persistMinDays", update.persistMinDays());
        putIfPresent(mainline, "persistWindowDays", update.persistWindowDays());
        putIfPresent(mainline, "topThirdRank", update.topThirdRank());
        putIfPresent(mainline, "divergenceHeatRank", update.divergenceHeatRank());
        configService.write(IndustryMainlineSettings.MAINLINE_KEY, mainline.toString(), expected);
        ObjectNode leader = objectMapper.createObjectNode();
        putIfPresent(leader, "wa", update.wa());
        putIfPresent(leader, "wv", update.wv());
        putIfPresent(leader, "wq", update.wq());
        putIfPresent(leader, "mentionDays", update.mentionDays());
        putIfPresent(leader, "topN", update.leaderTopN());
        putIfPresent(leader, "qDay", update.qDay());
        putIfPresent(leader, "qD5", update.qD5());
        configService.write(IndustryMainlineSettings.LEADER_KEY, leader.toString(), expected);
        // write 成功后快照已换新——回读刷新视图（含新 updatedAt 供下次防呆比对）
        return view();
    }

    private MainlineView mainlineView() {
        RuntimeConfigEntry entry =
                configService.read(IndustryMainlineSettings.MAINLINE_KEY).orElse(null);
        Params params = settings.mainlineParams();
        return new MainlineView(
                params.wp(),
                params.wh(),
                params.we(),
                params.priceWinDay(),
                params.priceWinD5(),
                params.heatH24(),
                params.heatD7(),
                params.heatDelta(),
                params.topN(),
                params.persistMinDays(),
                params.persistWindowDays(),
                params.topThirdRank(),
                params.divergenceHeatRank(),
                entry == null ? null : entry.updatedAt().toString());
    }

    private LeaderView leaderView() {
        RuntimeConfigEntry entry =
                configService.read(IndustryMainlineSettings.LEADER_KEY).orElse(null);
        IndustryMainlineSettings.LeaderParams params = settings.leaderParams();
        return new LeaderView(
                params.wa(),
                params.wv(),
                params.wq(),
                params.mentionDays(),
                params.topN(),
                params.qDay(),
                params.qD5(),
                entry == null ? null : entry.updatedAt().toString());
    }

    /** 原样透传节点（D3 先例）：合法值原样落文档；非法类型保留，由写路径校验器严格判定（30096 字段级——单一事实源）。 */
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
