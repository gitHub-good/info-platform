// 管道状态类型（M18 T156 概览工作台摘要用，对齐后端 PipelineStatusView——
// GET /api/v1/pipeline/status 契约的裁剪子集：完整字段面见后端，前端仅消费护栏与成本水位）。
// 枚举线格式 = 后端 GuardLevel name()：NORMAL / DEGRADED / FUSED（与行业热度页 pipeline.level 同源）。

/** 护栏等级（NORMAL 正常 / DEGRADED 一级降级 / FUSED 二级熔断）。 */
export type PipelineGuardLevel = 'NORMAL' | 'DEGRADED' | 'FUSED';

/** 管道状态视图（工作台消费子集）。 */
export interface PipelineStatusView {
  jobKey: string;
  level: PipelineGuardLevel;
  /** 当日管道成本（llm_call_log scene 5/6/7 SUCCESS 求和，微元）。 */
  todayCostMicros: number;
  /** 当日预算（微元）。 */
  budgetMicros: number;
  /** 成本口径版本串（cost-v2 族）。 */
  costBasis: string;
}
