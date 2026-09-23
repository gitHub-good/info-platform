// 提示词模板治理页类型（M5 T47/T48，对齐后端 PromptTemplateController 契约，方案 §4.4）。
// 列表为轻列表（不带 template 全文），编辑底稿与 diff 基准一律走详情单查（UI 方案 §6.2 联判 1 落实形态）。

/** 版本状态（status TINYINT 1/0 的字符串视图：ACTIVE 启用 / RETIRED 置废）。 */
export type PromptTemplateStatus = 'ACTIVE' | 'RETIRED';

/** 版本升级策略（D9：用户不填版本号，仅选递增策略；缺省 MINOR 由后端处理）。 */
export type PromptVersionStrategy = 'MINOR' | 'MAJOR';

/** 列表接口版本行（placeholderCount 为去重键数，服务端正则统计）。 */
export interface PromptVersionView {
  id: number;
  version: string;
  status: PromptTemplateStatus;
  placeholderCount: number;
  createdAt: string;
  updatedAt: string;
}

/** 列表接口场景分组（activeCount 0/≥2 属数据异常，前端警示不静默）。 */
export interface PromptGroupView {
  briefType: number;
  name: string;
  activeVersionId: number | null;
  activeCount: number;
  versions: PromptVersionView[];
}

/** GET /api/v1/prompt-templates 响应（恒 4 场景分组）。 */
export interface PromptListView {
  groups: PromptGroupView[];
}

/** GET /api/v1/prompt-templates/{id} 响应（全文 + sections 服务端预分 + 占位符清单）。 */
export interface PromptDetailView {
  id: number;
  briefType: number;
  name: string;
  version: string;
  status: PromptTemplateStatus;
  template: string;
  sections: { system: string; user: string };
  placeholders: string[];
}

/** POST /api/v1/prompt-templates 请求体（保存即激活）。 */
export interface PromptCreateRequest {
  briefType: number;
  /** 编辑底稿版本行 id（后端 diff 基准；缺省回落当前激活版）。 */
  baseVersionId?: number;
  template: string;
  versionStrategy?: PromptVersionStrategy;
  /** 确认移除键名数组（30068 确认后重提交才需要）。 */
  confirmedRemovedKeys?: string[];
}

/** POST /api/v1/prompt-templates 201 响应。 */
export interface PromptCreateResult {
  id: number;
  briefType: number;
  version: string;
  status: PromptTemplateStatus;
  deactivatedVersion: string | null;
  placeholderCount: number;
  warnings: string[];
}

/** POST /api/v1/prompt-templates/{id}/activate 响应（幂等命中 deactivatedVersion=null）。 */
export interface PromptActivateResult {
  id: number;
  briefType: number;
  version: string;
  status: PromptTemplateStatus;
  deactivatedVersion: string | null;
}

/** DELETE /api/v1/prompt-templates/{id} 响应。 */
export interface PromptDeleteResult {
  id: number;
  version: string;
  deleted: boolean;
}

/** 占位符描述符（T46 注册表；unknown 项 description 恒 null——不在注册表即无说明）。 */
export interface PromptPlaceholderItem {
  key: string;
  description: string | null;
}

/** 注册表单场景（场景 2 dormant=true 携带 note 如实披露休眠）。 */
export interface PromptScenarioView {
  briefType: number;
  name: string;
  dormant: boolean;
  note?: string | null;
  placeholders: PromptPlaceholderItem[];
}

/** GET /api/v1/prompt-placeholders 响应。 */
export interface PromptRegistryView {
  scenarios: PromptScenarioView[];
}

/** 409/30068 结构化错误 data（服务端每次重算的待确认清单，ApiError.data 携带）。 */
export interface PromptRemovalConfirmation {
  removed: PromptPlaceholderItem[];
  unknown: PromptPlaceholderItem[];
}
