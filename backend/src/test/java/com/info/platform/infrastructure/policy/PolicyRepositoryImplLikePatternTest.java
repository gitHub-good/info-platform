package com.info.platform.infrastructure.policy;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * likePattern 转义工具单元测试（M9 T62）：三通配符 {@code \ % _}、组合、空串——转义后包 % 通配 + {@code ESCAPE '\'}
 * 语义（防用户输入通配符被当 LIKE 语义，ADR-0035）。纯静态方法直测，不起 Spring 上下文。
 */
class PolicyRepositoryImplLikePatternTest {

    @Test
    void likePattern_emptyString_becomesMatchAll() {
        // 空串 → %%（匹配任意非 NULL；实际空关键词在上层已按缺席处理，此为工具函数防御行为）
        assertThat(PolicyRepositoryImpl.likePattern("")).isEqualTo("%%");
    }

    @Test
    void likePattern_plainText_wrappedOnly() {
        // 无通配符：仅包 % 定界，字符原样（中文按字节原样匹配的口径基础）
        assertThat(PolicyRepositoryImpl.likePattern("半导体")).isEqualTo("%半导体%");
        assertThat(PolicyRepositoryImpl.likePattern("chip")).isEqualTo("%chip%");
    }

    @Test
    void likePattern_percentEscaped() {
        // % → \%（字面百分号）
        assertThat(PolicyRepositoryImpl.likePattern("100%")).isEqualTo("%100\\%%");
    }

    @Test
    void likePattern_underscoreEscaped() {
        // _ → \_（字面下划线，不再当单字通配）
        assertThat(PolicyRepositoryImpl.likePattern("a_b")).isEqualTo("%a\\_b%");
    }

    @Test
    void likePattern_backslashEscapedFirst() {
        // \ → \\（先转义反斜杠，防后续转义符被二次转义）
        assertThat(PolicyRepositoryImpl.likePattern("C:\\dir")).isEqualTo("%C:\\\\dir%");
    }

    @Test
    void likePattern_comboAllThreeWildcards_orderLocked() {
        // 组合 a%b_c\d → a\%b\_c\\d（锁定替换顺序：先 \ 再 % 再 _；顺序错则本断言红）
        assertThat(PolicyRepositoryImpl.likePattern("a%b_c\\d")).isEqualTo("%a\\%b\\_c\\\\d%");
        // 相邻 \%（反斜杠后跟百分号）：先 \ 后 % 的顺序特征串
        assertThat(PolicyRepositoryImpl.likePattern("\\%")).isEqualTo("%\\\\\\%%");
    }
}
