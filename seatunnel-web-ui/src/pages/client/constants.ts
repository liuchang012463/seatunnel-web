import React from "react";
import { motion } from "framer-motion";

export const panelStyle: React.CSSProperties = {
  borderRadius: 20,
  padding: 0,
  border: "1px solid var(--st-color-divider)",
  boxShadow: "var(--st-panel-shadow)",
};

export const iconWrapStyle: React.CSSProperties = {
  width: 38,
  height: 38,
  borderRadius: 12,
  display: "inline-flex",
  alignItems: "center",
  justifyContent: "center",
  background: "var(--st-color-selected)",
  color: "var(--st-color-accent)",
  flexShrink: 0,
};



export const MotionDiv = motion.div;

export const BLUE = "var(--st-color-accent)";
export const TEXT_SECONDARY = "var(--st-color-text-secondary)";
export const BORDER_COLOR = "var(--st-color-border)";
export const PAGE_BG = "var(--st-color-bg-page)";
export const CARD_BG = "var(--st-color-bg-panel)";
export const BLUE_LIGHT = "var(--st-color-selected)";

export const contentSwapVariants = {
  hidden: { opacity: 0, y: 10 },
  visible: {
    opacity: 1,
    y: 0,
    transition: {
      duration: 0.24,
      ease: [0.22, 1, 0.36, 1] as [number, number, number, number],
    },
  },
  exit: {
    opacity: 0,
    y: -6,
    transition: {
      duration: 0.16,
      ease: [0.4, 0, 1, 1] as [number, number, number, number],
    },
  },
};
