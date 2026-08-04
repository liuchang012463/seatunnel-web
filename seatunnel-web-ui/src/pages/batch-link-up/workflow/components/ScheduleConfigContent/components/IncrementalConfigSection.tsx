import { Alert, Input, InputNumber } from "antd";

interface Props {
  value?: any;
  onChange?: (patch: Record<string, any>) => void;
}

export default function IncrementalConfigSection({ value, onChange }: Props) {
  const incremental = value?.incremental || {};
  const update = (key: string, nextValue: any) => {
    onChange?.({
      incremental: {
        ...incremental,
        enabled: true,
        [key]: nextValue,
      },
    });
  };

  return (
    <div className="space-y-4">
      <Alert
        type="info"
        showIcon
        message="每次调度生成一个固定的左闭右开窗口 [start, end)，失败重试会复用同一窗口。"
        description="来源建议使用带更新时间字段的 JDBC 单表；目标必须使用 Upsert 和主键。自定义 SQL 时请使用 ${window_start}、${window_end}，需要重叠读取时可使用 ${query_start}。"
      />

      <label className="block">
        <div className="mb-1 text-xs text-slate-500">水位字段</div>
        <Input
          value={incremental.watermarkColumn}
          placeholder="例如：update_time"
          onChange={(event) => update("watermarkColumn", event.target.value)}
        />
      </label>

      <label className="block">
        <div className="mb-1 text-xs text-slate-500">初始水位</div>
        <Input
          value={incremental.initialWatermark}
          placeholder="yyyy-MM-dd HH:mm:ss"
          onChange={(event) => update("initialWatermark", event.target.value)}
        />
      </label>

      <div className="grid grid-cols-1 gap-3 md:grid-cols-3">
        <label className="block">
          <div className="mb-1 text-xs text-slate-500">安全延迟（秒）</div>
          <InputNumber
            className="w-full"
            min={0}
            value={incremental.safetyDelaySeconds}
            onChange={(next) => update("safetyDelaySeconds", next ?? 0)}
          />
        </label>
        <label className="block">
          <div className="mb-1 text-xs text-slate-500">重叠窗口（秒）</div>
          <InputNumber
            className="w-full"
            min={0}
            value={incremental.overlapSeconds}
            onChange={(next) => update("overlapSeconds", next ?? 0)}
          />
        </label>
        <label className="block">
          <div className="mb-1 text-xs text-slate-500">最大批次窗口（秒）</div>
          <InputNumber
            className="w-full"
            min={1}
            value={incremental.maxWindowSeconds}
            onChange={(next) => update("maxWindowSeconds", next ?? 1)}
          />
        </label>
      </div>
    </div>
  );
}
