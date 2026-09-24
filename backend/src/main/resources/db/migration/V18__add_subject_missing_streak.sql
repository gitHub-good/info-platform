-- T51/T52 · 标的池同步：外部消失连续缺失计数（REQ-20260924-04 裁决点「连续 N 轮确认停用」，ADR-0028）
-- 语义：该标的在其所属市场桶的连续「本轮拉取成功但全量结果中未出现」轮数；
--       市场本轮拉取失败 → 整桶跳过，计数不动（防假消失，PRD 故事 3 场景 4）；
--       重新出现 → 同轮 UPDATE 清零（PRD 故事 3 场景 5）；停用后不再计数。
-- 默认 0：种子 45 只与既有行无观察态；status=1 且 streak 达阈值（默认 3，可配）才置 status=0（单向 1→0）。
ALTER TABLE subject_master ADD COLUMN missing_streak INTEGER NOT NULL DEFAULT 0;
