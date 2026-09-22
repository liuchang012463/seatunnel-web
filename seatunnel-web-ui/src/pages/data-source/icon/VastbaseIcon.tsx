import React from 'react';

type VastbaseIconProps = {
  width?: string | number;
  height?: string | number;
};

/** Compact vendor-neutral mark for Vastbase in datasource selectors. */
const VastbaseIcon: React.FC<VastbaseIconProps> = ({ width = 24, height = 24 }) => (
  <svg width={width} height={height} viewBox="0 0 24 24" role="img" aria-label="Vastbase">
    <rect x="1" y="1" width="22" height="22" rx="5" fill="#1f6feb" />
    <path d="M5 7h2.4l1.8 6.5L11 7h2l1.8 6.5L16.6 7H19l-3.3 10h-2l-1.7-6-1.7 6h-2L5 7Z" fill="white" />
  </svg>
);

export default VastbaseIcon;
