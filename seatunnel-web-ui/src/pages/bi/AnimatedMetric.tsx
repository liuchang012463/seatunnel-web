import React, { useEffect, useRef, useState } from 'react';

const formatter = new Intl.NumberFormat('zh-CN', { maximumFractionDigits: 0 });

/** 只对真实指标值做过渡，数据未就绪时保留占位符。 */
const AnimatedMetric: React.FC<{ value: string }> = ({ value }) => {
  const target = value === '—' ? undefined : Number(value.replace(/,/g, ''));
  const current = useRef(0);
  const [display, setDisplay] = useState(0);

  useEffect(() => {
    if (target === undefined || !Number.isFinite(target)) {
      current.current = 0;
      setDisplay(0);
      return;
    }
    if (window.matchMedia('(prefers-reduced-motion: reduce)').matches) {
      current.current = target;
      setDisplay(target);
      return;
    }
    const from = current.current;
    const duration = 800 + Math.random() * 1000;
    let frame: number | undefined;
    let start: number;
    const update = (now: number) => {
      const progress = Math.max(0, Math.min(1, (now - start) / duration));
      current.current = Math.round(from + (target - from) * (1 - (1 - progress) ** 3));
      setDisplay(current.current);
      if (progress < 1) frame = requestAnimationFrame(update);
    };
    const timer = window.setTimeout(() => {
      start = performance.now();
      frame = requestAnimationFrame(update);
    }, Math.random() * 1800);
    return () => {
      clearTimeout(timer);
      if (frame !== undefined) cancelAnimationFrame(frame);
    };
  }, [target]);

  return <span aria-label={value}>{target === undefined || !Number.isFinite(target) ? value : formatter.format(display)}</span>;
};

export default AnimatedMetric;
