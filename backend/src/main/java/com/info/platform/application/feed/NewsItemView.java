package com.info.platform.application.feed;

/**
 * 资讯流条目视图（GET /api/v1/news-items 每条，T104；T160 增量追加 analysis join 字段—— 追加式扩展，既有字段零变化）。
 * publishedAt/fetchedAt 为 ISO-8601 整秒文本。
 *
 * <p>T160 增量字段（join news_analysis，无 analysis 行兜底）：l0Result 滞留条目（无 analysis 行）兜底 PASS、 l1Main null =
 * 未分类（前端灰态）、lowConfidence 低置信角标（confidence&lt;floor 或非法枚举兜底——阈值运行时可配， 前端不可复算故直读库内旗标）、
 * nearDupMasterUrl 主条原文 url（主条被清理为 null——前端隐藏「主条」链接）。
 *
 * @param l0Result L0 状态（PASS / NOISE / NEAR_DUP）
 * @param l0Detail 诊断（NOISE 命中规则名 / NEAR_DUP 海明距离+编辑距离）
 * @param l1Main 主分类（35 枚举；null = 未分类）
 * @param l1Confidence L1 置信度 0~1
 * @param lowConfidence 低置信兜底旗标
 * @param nearDupMasterId 近重复主条 news_id
 * @param nearDupMasterUrl 主条原文 url
 */
public record NewsItemView(
        Long id,
        Long sourceId,
        String sourceCode,
        String sourceName,
        String title,
        String summary,
        String url,
        String author,
        String publishedAt,
        String fetchedAt,
        String l0Result,
        String l0Detail,
        String l1Main,
        Double l1Confidence,
        boolean lowConfidence,
        Long nearDupMasterId,
        String nearDupMasterUrl) {}
