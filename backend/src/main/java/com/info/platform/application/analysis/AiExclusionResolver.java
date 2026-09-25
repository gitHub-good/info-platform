package com.info.platform.application.analysis;

import com.info.platform.domain.feed.AiExclusion;
import com.info.platform.domain.feed.InfoSource;
import com.info.platform.domain.feed.InfoSourceRepository;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * 源级 AI 排除解析器（应用层，M15 T125，REQ 拍板五-1）：读 {@code info_source.config.aiExclusion} 档位，供 L0/L1（{@code
 * ALL} 不建行不归类）与 L2（{@code L2}/{@code ALL} 不产事件）查询下传排除源 id 清单。
 *
 * <p>档位语义（方案 §4.4）：{@code L2} = 照常归类、照常计热度资讯量、不产事件不进事件流；{@code ALL} = 全管道排除（L0 即不建 analysis 行）。13
 * 源量级全表扫描 + 内存过滤，每 tick 一次。
 */
@Component
public class AiExclusionResolver {

    private final InfoSourceRepository infoSourceRepository;

    public AiExclusionResolver(InfoSourceRepository infoSourceRepository) {
        this.infoSourceRepository = infoSourceRepository;
    }

    /**
     * 取指定档位（及以上）的排除源 id 清单。
     *
     * @param level {@code L2} 返回 L2+ALL（L2 查询排除面——ALL 源本无 analysis 行，防御性一并排除）；{@code ALL} 仅返回
     *     ALL（L0/L1 只排除全管道档，L2 档源照常归类）
     */
    public List<Long> excludedSourceIds(AiExclusion level) {
        List<Long> excluded = new ArrayList<>();
        for (InfoSource source : infoSourceRepository.findAll()) {
            AiExclusion current = source.getConfig().effectiveAiExclusion();
            if (current == AiExclusion.ALL
                    || (level == AiExclusion.L2 && current == AiExclusion.L2)) {
                excluded.add(source.getId());
            }
        }
        return List.copyOf(excluded);
    }
}
