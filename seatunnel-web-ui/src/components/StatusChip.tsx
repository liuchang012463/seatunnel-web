import { Tooltip } from 'antd';
import React from 'react';

export type StatusChipTone = 'success' | 'warning' | 'error' | 'processing' | 'neutral';

/**
 * 状态语义五色（DESIGN.md §3.3）。StatusChip 是全局唯一的状态实现处：
 * 「● 文本」双通道，禁止只靠颜色或只靠纯彩字传达状态。
 */
const TONE_RGB: Record<StatusChipTone, string> = {
  success: '61, 214, 140',
  warning: '245, 184, 61',
  error: '255, 107, 94',
  processing: '63, 198, 255',
  neutral: '108, 135, 146',
};

export interface StatusChipProps {
  tone: StatusChipTone;
  /** 状态文本。 */
  label: string;
  /** 可选等宽附加值（计数/时长等），跟在文本后。 */
  extra?: string;
  /** Tooltip 内容：判定时间/原因。有值才包裹 Tooltip。 */
  detail?: React.ReactNode;
  className?: string;
  style?: React.CSSProperties;
}

const StatusChip: React.FC<StatusChipProps> = ({ tone, label, extra, detail, className, style }) => {
  const rgb = TONE_RGB[tone] ?? TONE_RGB.neutral;
  const chip = (
    <span
      className={className ? `st-status-chip ${className}` : 'st-status-chip'}
      style={{
        ...style,
        display: 'inline-flex',
        alignItems: 'center',
        gap: 6,
        height: 22,
        padding: '0 8px',
        borderRadius: 2,
        whiteSpace: 'nowrap',
        fontSize: 12,
        lineHeight: '20px',
        color: `rgb(${rgb})`,
        background: `rgba(${rgb}, 0.12)`,
        border: `1px solid rgba(${rgb}, 0.28)`,
      }}
    >
      <i
        aria-hidden
        style={{
          flexShrink: 0,
          width: 8,
          height: 8,
          borderRadius: '50%',
          background: `rgb(${rgb})`,
        }}
      />
      <span>{label}</span>
      {extra ? (
        <span
          style={{
            fontFamily: '"JetBrains Mono", "SFMono-Regular", Consolas, monospace',
            fontVariantNumeric: 'tabular-nums',
            opacity: 0.85,
          }}
        >
          {extra}
        </span>
      ) : null}
    </span>
  );

  return detail ? (
    <Tooltip title={detail} destroyOnHidden>
      {chip}
    </Tooltip>
  ) : (
    chip
  );
};

export default StatusChip;
