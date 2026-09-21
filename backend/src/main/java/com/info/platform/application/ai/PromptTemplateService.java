package com.info.platform.application.ai;

import com.info.platform.domain.ai.BriefType;
import com.info.platform.domain.ai.ChatMessage;
import com.info.platform.domain.ai.PromptTemplate;
import com.info.platform.domain.ai.PromptTemplateRepository;
import com.info.platform.domain.common.BusinessException;
import com.info.platform.domain.common.ErrorCode;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 提示词模板服务（应用层，T20）：加载启用模板 + 渲染占位符为 {@link ChatMessage} 列表。
 *
 * <p>T21 {@code AIBriefService} 生成简报前调用本服务： {@link #loadActiveTemplate} 按 {@code briefType}
 * 取当前启用模板， {@link #render} 用聚合上下文（行情/财务/事件/新闻）替换 {@code {{key}}} 占位符，组装 {@code [system, user]} 两条
 * {@link ChatMessage}，再以 {@link com.info.platform.domain.ai.LlmRequest#json
 * LlmRequest.json(messages, briefTypeKey)} 传入 {@link com.info.platform.domain.ai.LlmGateway}。对齐技术方案
 * §4.3 流程 2 + §4.4 + Spike-2 §7。
 *
 * <h2>模板分段约定</h2>
 *
 * 单 {@code template} 列用独占行的 {@code ---SYSTEM---} / {@code ---USER---} 标记分段，system 在前 user 在后（V8
 * 播种即此格式）。 {@link #render} 按标记切分两段，分别替换占位符后 trim 去除标记行的残余换行（保留正文内换行）。
 *
 * <h2>占位符约定</h2>
 *
 * 双花括号 {@code {{key}}}（仅 {@code \\w+} 键名，允许前后空白）。 渲染只替换 {@code {{key}}}，不触碰 user 段中 JSON
 * 输出格式约束的单花括号 {@code {summary, keyEvents[], ...}}（Spike-2 §7 各模板保留单花括号 JSON 形状）。 context 缺某键时保留原
 * {@code {{key}}} 不替换——上下文缺失可见而非静默置空（对齐"不吞异常"，T21 调用前应备齐全部键）。
 *
 * <p>system 含 "json" 字样是 V8 播种的不变量（满足 DeepSeek JSON mode 前提，Spike-2 §5.2），本服务不重复校验。
 */
@Service
public class PromptTemplateService {

    private static final Logger log = LoggerFactory.getLogger(PromptTemplateService.class);

    /** 模板分段标记（独占行）。 */
    private static final String SYSTEM_MARKER = "---SYSTEM---";

    private static final String USER_MARKER = "---USER---";

    /** 占位符 {@code {{key}}}（键名仅字母数字下划线，允许前后空白）。 */
    private static final Pattern PLACEHOLDER = Pattern.compile("\\{\\{\\s*(\\w+)\\s*\\}\\}");

    private final PromptTemplateRepository repository;

    public PromptTemplateService(PromptTemplateRepository repository) {
        this.repository = repository;
    }

    /**
     * 加载指定简报类型的当前启用模板。
     *
     * @param briefType 简报类型
     * @return 启用模板（{@code status=1} 的最新版本）
     * @throws BusinessException 无启用模板时抛 {@link ErrorCode#PROMPT_TEMPLATE_NOT_FOUND}（配置缺失，属服务端错误）
     */
    public PromptTemplate loadActiveTemplate(BriefType briefType) {
        Objects.requireNonNull(briefType, "briefType 必填");
        PromptTemplate template =
                repository
                        .findActiveByBriefType(briefType)
                        .orElseThrow(
                                () ->
                                        new BusinessException(
                                                ErrorCode.PROMPT_TEMPLATE_NOT_FOUND,
                                                "未配置启用提示词模板: briefType=" + briefType));
        log.info(
                "加载提示词模板: briefType={}, version={}, id={}",
                briefType,
                template.getVersion(),
                template.getId());
        return template;
    }

    /**
     * 渲染模板：切分 system/user 两段 + 替换 {@code {{key}}} 占位符，组装为 {@code [system, user]} 消息列表。
     *
     * @param template 已加载的模板
     * @param context 聚合上下文键值（如 {@code subjectName}/{@code price}/{@code newsList}）；null 视为空
     * @return 两条 {@link ChatMessage}（system 在前、user 在后）
     * @throws IllegalStateException 模板缺少分段标记（数据损坏）
     */
    public List<ChatMessage> render(PromptTemplate template, Map<String, String> context) {
        Objects.requireNonNull(template, "template 必填");
        Map<String, String> ctx = context == null ? Map.of() : context;
        String[] sections = splitSections(template.getTemplate(), template.getVersion());
        String system = replacePlaceholders(sections[0], ctx).strip();
        String user = replacePlaceholders(sections[1], ctx).strip();
        return List.of(new ChatMessage("system", system), new ChatMessage("user", user));
    }

    /** 切分 system/user 两段；标记缺失或顺序错视为数据损坏抛 {@link IllegalStateException}。 */
    private static String[] splitSections(String raw, String version) {
        int sysIdx = raw.indexOf(SYSTEM_MARKER);
        int userIdx = raw.indexOf(USER_MARKER);
        if (sysIdx < 0 || userIdx < 0 || userIdx <= sysIdx) {
            throw new IllegalStateException(
                    "提示词模板缺少 "
                            + SYSTEM_MARKER
                            + "/"
                            + USER_MARKER
                            + " 分段标记或顺序错误: version="
                            + version);
        }
        String system = raw.substring(sysIdx + SYSTEM_MARKER.length(), userIdx);
        String user = raw.substring(userIdx + USER_MARKER.length());
        return new String[] {system, user};
    }

    /** 替换 {@code {{key}}}：context 有值则替换、无值保留原占位符（缺失可见）。 */
    private static String replacePlaceholders(String text, Map<String, String> context) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        Matcher m = PLACEHOLDER.matcher(text);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String key = m.group(1);
            String value = context.get(key);
            String replacement = value != null ? value : m.group();
            m.appendReplacement(sb, Matcher.quoteReplacement(replacement));
        }
        m.appendTail(sb);
        return sb.toString();
    }
}
