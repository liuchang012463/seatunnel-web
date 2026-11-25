import { Tooltip } from 'antd';
import { useLayoutEffect, useRef, useState, type ReactNode } from 'react';

interface TaskPlanTooltipProps {
  className: string;
  title: ReactNode;
  children: ReactNode;
}

/** 一个同步方案可能省略两个端点。共用其原有详情浮层，只增加一个必要的 Tab 停靠点。 */
export default function TaskPlanTooltip({ className, title, children }: TaskPlanTooltipProps) {
  const plan = useRef<HTMLDivElement>(null);
  const [truncated, setTruncated] = useState(false);

  useLayoutEffect(() => {
    const labels = Array.from(plan.current!.querySelectorAll<HTMLElement>('[data-plan-label]'));
    const measure = () => setTruncated(labels.some((label) =>
      label.clientWidth > 0 && label.scrollWidth > label.clientWidth,
    ));
    measure();
    const observer = new ResizeObserver(measure);
    labels.forEach((label) => observer.observe(label));
    return () => observer.disconnect();
  }, [children]);

  return (
    <Tooltip title={title} trigger={['hover', 'focus']} styles={{ body: { overflowWrap: 'anywhere' } }}>
      <div ref={plan} className={className} tabIndex={truncated ? 0 : undefined}>
        {children}
      </div>
    </Tooltip>
  );
}
