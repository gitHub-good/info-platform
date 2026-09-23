package com.info.platform.application.ai;

import com.info.platform.domain.ai.BriefType;
import com.info.platform.domain.ai.PlaceholderDescriptor;
import java.util.List;
import java.util.Set;

/**
 * 占位符供给端口（应用层，T46 / ADR-0022）：上下文装配器自述「为哪些场景注入哪些占位符键」。
 *
 * <p>每个装配器实现一份：描述符清单与 {@code ctx.put} 调用同文件同序维护（改键必触碰同一文件，评审可见）， 同源单测断言 {@code build()} 输出 keySet
 * == {@link #provided()} keySet 作机械防漂移闸门。
 *
 * <p>{@link #briefTypes()} 为集合：{@code BriefContextBuilder} 的 17 键清单由场景 1（个股简报）与 2（事件归因）共用（技术方案
 * §4.5），故端口按「一装配器可服务多场景」建模。
 */
public interface PlaceholderProvider {

    /** 本装配器服务的简报场景（其 provided() 清单对这些场景全部生效）。 */
    Set<BriefType> briefTypes();

    /** 实际注入的占位符描述符（键序与装配器 {@code ctx.put} 调用序一致，保序便于对照区展示）。 */
    List<PlaceholderDescriptor> provided();
}
