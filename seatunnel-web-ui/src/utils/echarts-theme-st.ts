import * as echarts from 'echarts';

/**
 * 青色单族图表主题（审计 G7 / DESIGN.md §4.7）：
 * 杜绝 ECharts 默认紫蓝与跨色装饰，强调色收敛为青色一族。
 * 注册一次（import 即注册），init(dom, 'st') 生效。
 */
export const ST_CHART_ACCENT = '#4dd2ff';
export const ST_CHART_ACCENT_STRONG = '#7fd8ff';

const stTheme = {
  color: [ST_CHART_ACCENT, ST_CHART_ACCENT_STRONG, '#2187a8', '#117da0', '#9aa5ab'],
  backgroundColor: 'transparent',
  categoryAxis: {
    axisLine: { lineStyle: { color: '#2187a8' } },
    axisTick: { lineStyle: { color: 'rgba(213, 213, 213, 0.45)' } },
    axisLabel: { color: '#d5d5d5' },
    splitLine: { show: false },
  },
  valueAxis: {
    axisLine: { lineStyle: { color: '#2187a8' } },
    axisTick: { lineStyle: { color: 'rgba(213, 213, 213, 0.45)' } },
    axisLabel: { color: '#d5d5d5' },
    splitLine: { lineStyle: { color: 'rgba(213, 213, 213, 0.24)' } },
  },
};

echarts.registerTheme('st', stTheme);
