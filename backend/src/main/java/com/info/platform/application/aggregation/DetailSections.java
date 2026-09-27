package com.info.platform.application.aggregation;

import com.info.platform.domain.aggregation.SourceCode;
import java.util.EnumSet;
import java.util.Set;

/**
 * 标的详情分区请求（{@code sections} 查询参数的解析产物，V2.3-M23 T203）。
 *
 * <p>轨 A POLICY 源退役（{@code SourceCode.POLICY} 枚举删除，ADR-0062 裁决二）后，政策分区不再是外呼源， 但 {@code
 * ?sections=policy} 仍是接口契约合法令牌（分区面 ≠ 源面）——分区请求与外呼源集在此解耦： {@code sources} = 六外呼源子集（fan-out 面），{@code
 * policy} = 政策分区请求标志（库内查询分区，恒 ok）。
 *
 * <p>语义对齐退役前：参数缺省 = 全部分区（六源 + 政策）；显式列举 = 只取所列分区。
 */
public record DetailSections(Set<SourceCode> sources, boolean policy) {

    /** 全部分区（sections 参数缺省形态：六外呼源 + 政策分区）。 */
    public static DetailSections all() {
        return new DetailSections(EnumSet.allOf(SourceCode.class), true);
    }

    /** 指定外呼源子集 + 政策分区标志。 */
    public static DetailSections of(Set<SourceCode> sources, boolean policy) {
        return new DetailSections(sources, policy);
    }
}
