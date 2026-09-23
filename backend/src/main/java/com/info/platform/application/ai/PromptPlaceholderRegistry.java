package com.info.platform.application.ai;

import com.info.platform.domain.ai.BriefType;
import com.info.platform.domain.ai.PlaceholderDescriptor;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

/**
 * 占位符注册表（应用层，T46 / ADR-0022）：聚合全部 {@link PlaceholderProvider} 为 {@code Map<BriefType,
 * List<PlaceholderDescriptor>>} 的单一事实源。
 *
 * <p>两个消费方注入同一 Registry 实例——「编辑器对照区清单」与「保存校验 V5 的 unknown 基准」物理上是一份数据：
 *
 * <ul>
 *   <li>{@code PromptTemplateController}：GET /api/v1/prompt-placeholders 只读接口（编辑器对照区）。
 *   <li>{@code PromptTemplateValidator}：unknown 占位符差集基准（警告级，不拦截）。
 * </ul>
 *
 * <p>聚合时机在构造期（Spring 单例启动一次），运行期只读；装配器日后加/删键由同源单测闸门兜住（build() keySet == provided() keySet）。
 */
@Service
public class PromptPlaceholderRegistry {

    private final Map<BriefType, List<PlaceholderDescriptor>> placeholdersByBriefType;

    public PromptPlaceholderRegistry(List<PlaceholderProvider> providers) {
        Map<BriefType, List<PlaceholderDescriptor>> aggregated = new EnumMap<>(BriefType.class);
        for (PlaceholderProvider provider : providers) {
            List<PlaceholderDescriptor> provided = List.copyOf(provider.provided());
            for (BriefType briefType : provider.briefTypes()) {
                aggregated.put(briefType, provided);
            }
        }
        this.placeholdersByBriefType = Collections.unmodifiableMap(aggregated);
    }

    /**
     * 按场景取占位符描述符清单（保序，与装配器 {@code ctx.put} 序一致）。
     *
     * @param briefType 简报场景
     * @return 该场景注册的全部占位符；无供给方时为空列表（差集基准为空集 = 全部 unknown，防御）
     */
    public List<PlaceholderDescriptor> byBriefType(BriefType briefType) {
        return placeholdersByBriefType.getOrDefault(briefType, List.of());
    }

    /** 全量注册表（只读视图；只读接口遍历 {@code BriefType.values()} 组装 4 场景分组）。 */
    public Map<BriefType, List<PlaceholderDescriptor>> all() {
        return placeholdersByBriefType;
    }
}
