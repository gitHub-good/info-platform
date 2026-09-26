package com.info.platform.application.recommendation;

/**
 * 推荐卡片详情视图（M16 T133，方案 §4.8 {@code GET /api/v1/recommendations/{id}}）：卡片视图 + logicInputs （生成输入快照
 * JSON——结构化事实 + 四类允许集，抽检对账与复现面）。gen_method/prompt_version 留痕不对用户展示（REQ 拍板三）。
 *
 * @param card 卡片视图（同卡片流字段面）
 * @param logicInputs 生成输入快照 JSON（可空——快照序列化失败回落 null）
 */
public record RecommendationCardDetailView(
        RecommendationCardListView.CardView card, String logicInputs) {}
