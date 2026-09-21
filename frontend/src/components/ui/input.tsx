// 文本输入框（shadcn 风格，暗色 token，无 base-ui 依赖）。
// 与 T10 暗色主题一致：边框/底色/聚焦环全走 CSS 变量，无散落魔法值。

import type { ComponentProps } from 'react';
import { cn } from 'cn';

function Input({ className, ...props }: ComponentProps<'input'>) {
  return (
    <input
      data-slot="input"
      className={cn(
        'h-9 w-full rounded-lg border border-input bg-input/30 px-3 text-sm text-foreground shadow-sm transition-colors placeholder:text-muted-foreground focus-visible:border-ring focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring/50 disabled:cursor-not-allowed disabled:opacity-50',
        className,
      )}
      {...props}
    />
  );
}

export { Input };
