package com.info.platform.application.jobrun;

import com.fasterxml.jackson.databind.JsonNode;
import com.info.platform.application.common.ConfigFieldRules;
import com.info.platform.application.common.RuntimeConfigValidator;
import com.info.platform.domain.common.BusinessException;
import com.info.platform.domain.common.ErrorCode;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.stereotype.Component;

/**
 * 任务域校验器（T37，键空间 {@code job.{JOB_KEY}}，方案 §4.1）。
 *
 * <p>表驱动单字段规则 + 跨字段规则（按文档内 scheduleType 分支，<b>不依赖</b> JobRegistry——校验器在 {@code
 * RuntimeConfigService} 的构造链上，引注册表会与依赖其的 Job 成环）：
 *
 * <ul>
 *   <li>通用：enabled 布尔、scheduleType ∈ {FIXED_DELAY, CRON}（必填）、userIds 逗号分隔正整数或空
 *   <li>FIXED_DELAY：intervalMillis 必填正整数；不允许携带 cron 字段（间隔型任务不支持 cron）
 *   <li>CRON：cron 必填且可被 {@link CronExpression} 解析（6 段，与调度中心 CronTrigger 同一解析器， 非法值在校验层拦截、原调度不动，方案
 *       §5）；不允许携带 intervalMillis 字段
 * </ul>
 *
 * <p>校验失败抛 2001 PARAM_INVALID，msg 带字段级原因（多个以 "; " 连接），对齐 PRD「前后端双侧校验」红线。
 */
@Component
public class JobRuntimeConfigValidator implements RuntimeConfigValidator {

    /** userIds：空串或「1, 2,3」形逗号分隔正整数字符串（与种子/DailyRecommendationJob 解析口径一致）。 */
    private static final Pattern USER_IDS = Pattern.compile("\\s*\\d+(\\s*,\\s*\\d+)*\\s*");

    @Override
    public boolean supports(String configKey) {
        return configKey != null && configKey.startsWith(JobScheduleSettings.KEY_PREFIX);
    }

    @Override
    public void validate(String configKey, JsonNode document) {
        List<String> problems = new ArrayList<>();
        ConfigFieldRules.enforce(
                document,
                List.of(
                        ConfigFieldRules.bool("enabled"),
                        ConfigFieldRules.oneOf(
                                "scheduleType",
                                ScheduleType.FIXED_DELAY.name(),
                                ScheduleType.CRON.name())));
        // scheduleType 非法值已被上面 oneOf 规则拦截，此处只区分合法两值与缺失
        JsonNode typeNode = document.path("scheduleType");
        boolean fixedDelay = ScheduleType.FIXED_DELAY.name().equals(typeNode.asText());
        boolean cron = ScheduleType.CRON.name().equals(typeNode.asText());
        if (cron) {
            requireCron(document, problems);
        } else if (fixedDelay) {
            requireInterval(document, problems);
        } else {
            problems.add("scheduleType: 必填");
        }
        validateUserIds(document, problems);
        if (!problems.isEmpty()) {
            throw new BusinessException(ErrorCode.PARAM_INVALID, String.join("; ", problems));
        }
    }

    private static void requireInterval(JsonNode document, List<String> problems) {
        JsonNode interval = document.get("intervalMillis");
        if (interval == null || interval.isNull()) {
            problems.add("intervalMillis: 必填（间隔型任务）");
        } else if (!interval.isIntegralNumber() || interval.asLong() <= 0) {
            problems.add("intervalMillis: 须为正整数");
        }
        if (document.hasNonNull("cron")) {
            problems.add("cron: 间隔型任务不支持 cron 字段");
        }
    }

    private static void requireCron(JsonNode document, List<String> problems) {
        JsonNode cronNode = document.get("cron");
        if (cronNode == null || cronNode.isNull() || cronNode.asText().isBlank()) {
            problems.add("cron: 必填（cron 型任务）");
            return;
        }
        if (!cronNode.isTextual()) {
            problems.add("cron: 须为字符串表达式");
            return;
        }
        try {
            CronExpression.parse(cronNode.asText());
        } catch (IllegalArgumentException e) {
            problems.add("cron: 格式不正确（6 段 Spring cron，如 0 0 9 * * ?；" + e.getMessage() + "）");
        }
        if (document.hasNonNull("intervalMillis")) {
            problems.add("intervalMillis: cron 型任务不支持 intervalMillis 字段");
        }
    }

    private static void validateUserIds(JsonNode document, List<String> problems) {
        JsonNode userIds = document.get("userIds");
        if (userIds == null || userIds.isNull()) {
            return;
        }
        String raw = userIds.isTextual() ? userIds.asText() : null;
        boolean valid = raw != null && (raw.isBlank() || USER_IDS.matcher(raw).matches());
        if (!valid) {
            problems.add("userIds: 须为逗号分隔的用户 id（如 \"1,2\"），可为空串");
        }
    }
}
