import * as echarts from 'echarts';
import React, { useEffect, useRef } from 'react';

interface LineChartProps {
  data: number[];
  xAxisData: string[];
  title: string;
  unit: string;
  loading: boolean;
}

const LineChart: React.FC<LineChartProps> = ({ data, xAxisData, title, unit, loading }) => {
  const chartRef = useRef<HTMLDivElement>(null);
  useEffect(() => {
    if (!chartRef.current) return;

    const chart = echarts.init(chartRef.current);

    const option: echarts.EChartsOption = {
      // title: {
      //   text: title,
      //   left: 'left',
      //   textStyle: {
         
      //     color: '#333',
      //     fontSize: 16, 
      //     fontWeight: 'bold', 
      //     fontFamily: 'Arial, sans-serif', 
      //   },
      // },
      tooltip: {
        trigger: 'axis',
        backgroundColor: '#002e41',
        borderColor: '#2187a8',
        borderWidth: 1,
        padding: [8, 10],
        textStyle: {
          color: '#d5d5d5',
          fontSize: 12,
        },
        axisPointer: {
          type: 'line',
          lineStyle: {
            color: 'rgba(77, 210, 255, 0.55)',
            width: 1,
          },
        },
        formatter: (params: any) => {
          const items = Array.isArray(params) ? params : [params];
          const firstItem = items[0];

          return [
            firstItem?.name || '',
            ...items.map(
              (item: any) => `${item.marker}${item.seriesName}: ${item.value} ${unit}`
            ),
          ].join('<br/>');
        },
      },
      xAxis: {
        type: 'category',
        data: xAxisData,
        axisLabel: {
          color: '#d5d5d5',
        },
        axisLine: {
          lineStyle: {
            color: '#2187a8',
          },
        },
        axisTick: {
          lineStyle: {
            color: 'rgba(213, 213, 213, 0.45)',
          },
        },
      },
      yAxis: {
        type: 'value',
        name: '',
        axisLabel: {
          color: '#d5d5d5',
          formatter: '{value} ' + unit,
        },
        axisLine: {
          lineStyle: {
            color: '#2187a8',
          },
        },
        axisTick: {
          lineStyle: {
            color: 'rgba(213, 213, 213, 0.45)',
          },
        },
        splitLine: {
          lineStyle: {
            color: 'rgba(213, 213, 213, 0.24)',
          },
        },
      },
      series: [
        {
          name: title,
          type: 'line',
          data: data,
          symbol: 'none',
        },
      ],
      grid: {
        containLabel: true,
        left: '3%',
        right: '4%',
        bottom: '2%',
        top: '15%',
      },
    };

    chart.setOption(option);

    // 响应式调整
    const handleResize = () => chart.resize();
    window.addEventListener('resize', handleResize);

    // 清理函数
    return () => {
      window.removeEventListener('resize', handleResize);
      chart.dispose();
    };
  }, [data, xAxisData]);

  return (
    <div
      ref={chartRef}
      style={{
        width: '100%',
        maxWidth: '100%',
        height: '330px',
      }}
    />
  );
};

export default LineChart;
