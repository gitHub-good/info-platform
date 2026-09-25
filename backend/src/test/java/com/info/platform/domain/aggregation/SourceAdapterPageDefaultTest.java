package com.info.platform.domain.aggregation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/**
 * SourceAdapter.fetchPage 默认方法契约单测（M12 T90，ADR-0037 决策 2）：不支持分页的源调用即抛 {@link
 * UnsupportedOperationException}——fail-fast 契约锁定（不静默退化为第一页、不吞异常）。
 */
class SourceAdapterPageDefaultTest {

    /** 仅实现既有两个抽象方法的匿名实现（默认 fetchPage 未覆写）。 */
    private final SourceAdapter nonPageable =
            new SourceAdapter() {
                @Override
                public SourceResult fetch(Subject subject) {
                    return SourceResult.missing(SourceCode.POLICY, subject.getId(), "test");
                }

                @Override
                public SourceCode sourceCode() {
                    return SourceCode.POLICY;
                }
            };

    private static Subject subject() {
        return Subject.reconstruct(
                1L,
                SubjectCode.of("SH600519"),
                Market.A_SHARE,
                SubjectType.STOCK,
                "贵州茅台",
                null,
                "白酒",
                SubjectStatus.ENABLED,
                1L,
                null,
                null);
    }

    @Test
    void fetchPage_default_throwsUnsupportedWithSourceCode() {
        Subject subject = subject();

        assertThatThrownBy(() -> nonPageable.fetchPage(subject, 2, 10))
                .isInstanceOf(UnsupportedOperationException.class)
                .hasMessageContaining("POLICY");
    }

    @Test
    void fetch_defaultImpl_stillWorks() {
        // 既有 fetch 契约不受默认方法影响（政策/行情/财务/估值源不覆写 fetchPage，聚合路径零改动）
        Subject subject = subject();

        SourceResult result = nonPageable.fetch(subject);

        assertThat(result.getStatus()).isEqualTo(SourceStatus.MISSING);
        assertThat(result.getSourceCode()).isEqualTo(SourceCode.POLICY);
    }
}
