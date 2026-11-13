import { Radio } from 'antd';
import type { SyncMode } from '../types';

interface Props {
  value: SyncMode;
  current?: SyncMode;
  title: string;
  desc: string;
  tag?: string;
  onSelect: (value: SyncMode) => void;
}

/** 与批量引接 ModeCard 一致：无叠字，选中用青蓝描边。 */
const ModeCard: React.FC<Props> = ({
  value,
  current,
  title,
  desc,
  tag,
  onSelect,
}) => {
  const active = current === value;

  return (
    <div
      onClick={() => onSelect(value)}
      className={active ? 'mode-card mode-card--active' : 'mode-card'}
      style={{
        position: 'relative',
        height: '100%',
        cursor: 'pointer',
        borderRadius: 4,
        border: `1px solid ${
          active
            ? 'var(--st-color-accent, #00c4e5)'
            : 'var(--st-color-border, rgba(17, 125, 160, 0.35))'
        }`,
        background: active
          ? 'var(--st-color-selected, rgba(33, 135, 168, 0.28))'
          : 'var(--st-color-bg-control, rgba(0, 25, 34, 0.72))',
        padding: 16,
        outline: 'none',
        boxShadow: 'none',
      }}
    >
      {tag ? (
        <span
          style={{
            position: 'absolute',
            right: 12,
            top: 12,
            borderRadius: 2,
            padding: '2px 6px',
            fontSize: 12,
            fontWeight: 500,
            color: 'var(--st-color-accent, #00c4e5)',
            background: 'rgba(0, 196, 229, 0.12)',
            lineHeight: 1.4,
          }}
        >
          {tag}
        </span>
      ) : null}

      <div
        style={{
          display: 'flex',
          alignItems: 'flex-start',
          gap: 10,
          paddingRight: 48,
        }}
      >
        <Radio value={value} style={{ margin: '2px 0 0', flexShrink: 0 }} />
        <div style={{ minWidth: 0, flex: 1 }}>
          <div
            style={{
              fontSize: 14,
              fontWeight: 600,
              lineHeight: '22px',
              color: 'var(--st-color-text-primary, #fff)',
            }}
          >
            {title}
          </div>
          <div
            style={{
              marginTop: 4,
              fontSize: 13,
              lineHeight: '20px',
              color: 'var(--st-color-text-secondary, #d5d5d5)',
            }}
          >
            {desc}
          </div>
        </div>
      </div>
    </div>
  );
};

export default ModeCard;
