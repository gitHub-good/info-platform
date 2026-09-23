// 只读模板渲染（M5 T47，UI 方案 §5.2/§3.2）：按 ---SYSTEM---/---USER--- 分段 + {{key}} 占位符着色。
// 详情展开与编辑预览共用；分段标记缺失/顺序错降级整段 pre + rose 提示（§3.2 交互 4）。

import { splitSections } from '@/lib/promptTemplate';
import { cn } from 'cn';

/** 占位符着色切分段（placeholder = 命中 {{key}} 段；registered = 是否在注册表内）。 */
interface TemplateTextPart {
  value: string;
  placeholder: boolean;
  registered: boolean;
}

/** 按 {{key}} 切分文本（单花括号 JSON 形状不参与；注册表未加载时全部按已注册配色）。 */
function splitByPlaceholders(text: string, registeredKeys: Set<string> | null): TemplateTextPart[] {
  const parts: TemplateTextPart[] = [];
  let lastIndex = 0;
  for (const match of text.matchAll(/\{\{\s*(\w+)\s*\}\}/g)) {
    const index = match.index ?? 0;
    if (index > lastIndex) {
      parts.push({ value: text.slice(lastIndex, index), placeholder: false, registered: false });
    }
    parts.push({
      value: match[0],
      placeholder: true,
      registered: registeredKeys === null || registeredKeys.has(match[1]),
    });
    lastIndex = index + match[0].length;
  }
  if (lastIndex < text.length) {
    parts.push({ value: text.slice(lastIndex), placeholder: false, registered: false });
  }
  return parts;
}

/** 标记行后的首个换行剔除（展示层切分不改数据：DB 存储格式保持原文）。 */
function stripLeadingNewline(text: string): string {
  return text.replace(/^\r?\n/, '');
}

interface SectionProps {
  label: string;
  body: string;
  registeredKeys: Set<string> | null;
}

function TemplateSection({ label, body, registeredKeys }: SectionProps) {
  return (
    <div className="flex flex-col gap-1">
      <span className="text-xs text-muted-foreground">{label}</span>
      <pre className="whitespace-pre-wrap rounded bg-muted p-2 font-mono text-xs leading-relaxed">
        {splitByPlaceholders(stripLeadingNewline(body), registeredKeys).map((part, index) =>
          part.placeholder ? (
            <span
              key={index}
              className={cn(
                'rounded-sm',
                part.registered
                  ? 'bg-sky-500/20 text-sky-300'
                  : 'bg-amber-500/15 text-amber-400',
              )}
            >
              {part.value}
            </span>
          ) : (
            part.value
          ),
        )}
      </pre>
    </div>
  );
}

interface TemplateTextViewProps {
  template: string;
  /** 注册表键集（null = 未加载：占位符全部按已注册配色，不做未知 amber 标注）。 */
  registeredKeys: Set<string> | null;
}

/** 只读模板渲染：SYSTEM/USER 分段 + 占位符着色（注册表内 sky / 未注册 amber，§5.1）。 */
export function TemplateTextView({ template, registeredKeys }: TemplateTextViewProps) {
  const sections = splitSections(template);
  if (sections === null) {
    return (
      <div className="flex flex-col gap-2">
        <p className="text-xs text-rose-400" role="alert">
          分段标记缺失或顺序错误，渲染将失败——请编辑修复
        </p>
        <pre className="whitespace-pre-wrap rounded bg-muted p-2 font-mono text-xs leading-relaxed">
          {template}
        </pre>
      </div>
    );
  }
  return (
    <div className="flex flex-col gap-3">
      <TemplateSection label="SYSTEM" body={sections.system} registeredKeys={registeredKeys} />
      <TemplateSection label="USER" body={sections.user} registeredKeys={registeredKeys} />
    </div>
  );
}
