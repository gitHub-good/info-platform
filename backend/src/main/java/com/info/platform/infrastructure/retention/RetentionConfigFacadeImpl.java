package com.info.platform.infrastructure.retention;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.info.platform.application.common.RuntimeConfigEntry;
import com.info.platform.application.common.RuntimeConfigService;
import com.info.platform.application.retention.RetentionConfigFacade;
import com.info.platform.application.retention.RetentionConfigValidator;
import com.info.platform.application.retention.RetentionWindows;
import com.info.platform.domain.common.BusinessException;
import com.info.platform.domain.common.ErrorCode;
import com.info.platform.domain.retention.RetentionLogTable;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * {@link RetentionConfigFacade} 实现（T72，方案 §4.3；T113 扩 newsItemDays 五字段）。读：runtime_config 快照现读 +
 * {@link RetentionWindows} 字段级回退解析 + 枚举常量拼 limits。写：五字段拼全量文档（null 不拼入——校验器 2001 必填拦截； 非整数类型原样透传——
 * 校验器 2001 须为整数拦截，D3）→ {@link RuntimeConfigService#write}（校验 + 乐观防呆 + 换快照热生效），写后回读刷新视图。
 */
@Component
public class RetentionConfigFacadeImpl implements RetentionConfigFacade {

    private final RuntimeConfigService configService;
    private final ObjectMapper objectMapper;

    public RetentionConfigFacadeImpl(
            RuntimeConfigService configService, ObjectMapper objectMapper) {
        this.configService = configService;
        this.objectMapper = objectMapper;
    }

    @Override
    public WindowsView view() {
        RuntimeConfigEntry entry =
                configService.read(RetentionConfigValidator.CONFIG_KEY).orElse(null);
        RetentionWindows windows =
                RetentionWindows.resolve(entry == null ? null : entry.document());
        Map<String, FieldLimits> limits = new LinkedHashMap<>();
        for (RetentionLogTable table : RetentionLogTable.values()) {
            limits.put(table.jsonField(), new FieldLimits(table.minDays(), table.defaultDays()));
        }
        return new WindowsView(
                new Windows(
                        windows.jobExecutionLogDays(),
                        windows.dataSourceEventDays(),
                        windows.llmCallLogDays(),
                        windows.readingEventDays(),
                        windows.newsItemDays()),
                limits,
                entry == null ? null : entry.updatedAt().toString());
    }

    @Override
    public WindowsView update(WindowsUpdate update) {
        ObjectNode doc = objectMapper.createObjectNode();
        putIfPresent(doc, RetentionLogTable.JOB_EXECUTION_LOG, update.jobExecutionLogDays());
        putIfPresent(doc, RetentionLogTable.DATA_SOURCE_EVENT, update.dataSourceEventDays());
        putIfPresent(doc, RetentionLogTable.LLM_CALL_LOG, update.llmCallLogDays());
        putIfPresent(doc, RetentionLogTable.READING_EVENT, update.readingEventDays());
        putIfPresent(doc, RetentionLogTable.NEWS_ITEM, update.newsItemDays());
        configService.write(
                RetentionConfigValidator.CONFIG_KEY,
                doc.toString(),
                parseExpected(update.expectedUpdatedAt()));
        // write 成功后快照已换新——回读刷新视图（含新 updatedAt 供下次防呆比对）
        return view();
    }

    /**
     * 原样透传节点（D3）：合法整数原样落文档；非整数类型（字符串/布尔/浮点）也原样保留，由写路径校验器按「JSON 整数」 严格判定 （2001
     * 字段级「须为整数」——单一事实源，不在门面重复类型判定）。缺失/JSON null 不拼入（校验器 2001「必填」拦截）。
     */
    private void putIfPresent(ObjectNode doc, RetentionLogTable table, JsonNode value) {
        if (value != null && !value.isNull()) {
            doc.set(table.jsonField(), value);
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
