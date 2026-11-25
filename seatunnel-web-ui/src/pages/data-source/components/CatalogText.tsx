import { Tooltip } from 'antd';
import React, { useCallback, useLayoutEffect, useRef, useState } from 'react';
import './CatalogText.less';

type CatalogTextProps = {
  text: string;
  className?: string;
  onClick?: () => void;
  disabled?: boolean;
  multiline?: boolean;
};

/** 字段实际被截断才提供提示，不给能完整阅读的普通单元格添加 Tab 停靠点。 */
export default function CatalogText({ text, className, onClick, disabled, multiline }: CatalogTextProps) {
  const elementRef = useRef<HTMLElement | null>(null);
  const [truncated, setTruncated] = useState(false);
  const setElement = useCallback((element: HTMLElement | null) => {
    elementRef.current = element;
  }, []);
  const actionable = Boolean(onClick && !disabled);
  const classes = `datasource-catalog-text${multiline ? ' datasource-catalog-text--multiline' : ''}${className ? ` ${className}` : ''}`;

  useLayoutEffect(() => {
    const element = elementRef.current;
    if (!element) return;
    const measure = () => setTruncated(element.clientWidth > 0 && (
      element.scrollWidth > element.clientWidth || (Boolean(multiline) && element.scrollHeight > element.clientHeight)
    ));
    measure();
    if (typeof ResizeObserver === 'undefined') return;
    const observer = new ResizeObserver(measure);
    observer.observe(element);
    return () => observer.disconnect();
  }, [text, actionable, multiline]);

  return (
    <Tooltip
      title={truncated ? text : null}
      trigger={['hover', 'focus']}
      destroyOnHidden
      styles={{ body: { overflowWrap: 'anywhere' } }}
    >
      {actionable ? (
        <button ref={setElement} type="button" className={classes} onClick={onClick}>
          {text}
        </button>
      ) : (
        <span
          ref={setElement}
          className={classes}
          tabIndex={truncated ? 0 : undefined}
          role={disabled ? 'button' : undefined}
          aria-disabled={disabled || undefined}
        >
          {text}
        </span>
      )}
    </Tooltip>
  );
}
