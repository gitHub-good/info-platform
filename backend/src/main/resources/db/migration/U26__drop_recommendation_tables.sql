-- U26 · 回滚 V26（反序 DROP；先停用 PIPELINE_EXPRESS / RECOMMENDATION_FEED 两 Job 键后执行，既有表无伤）
DROP TABLE IF EXISTS recommendation_mute;
DROP TABLE IF EXISTS recommendation_feedback;
DROP TABLE IF EXISTS recommendation_card;
