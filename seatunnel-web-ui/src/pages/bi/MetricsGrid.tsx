import AnimatedMetric from './AnimatedMetric';

interface Metric { label: string; value: string; unit?: string; tone: string }

/** Pure presentation: the page owns API aggregation and filtering. */
export default function MetricsGrid({ metrics }: { metrics: Metric[] }) {
  return (
    <div className="metrics-grid" aria-label="引接指标汇总">
      {metrics.map((metric) => (
        <article key={metric.label} className={'metric-card ' + metric.tone}>
          <span className="metric-name">{metric.label}</span>
          <span className="metric-value">
            <AnimatedMetric value={metric.value} />
            {metric.unit ? <small>{metric.unit}</small> : null}
          </span>
        </article>
      ))}
    </div>
  );
}
