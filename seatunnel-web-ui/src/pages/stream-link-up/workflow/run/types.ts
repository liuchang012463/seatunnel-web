export interface PipelineMetrics {
  readRowCount: number;
  writeRowCount: number;
  readQps: number;
  writeQps: number;
  status: string;
}

export interface MetricsData {
  type: string;
  instanceId: number;
  engineId: string;
  vertexCount: number;
  metrics?: Record<string, PipelineMetrics>;
  vertices?: Record<string, PipelineMetrics>;
  timestamp?: number;
}

export interface LogEntry {
  id: number;
  content: string;
  timestamp: string;
  type: "log" | "metric";
  data?: MetricsData;
  sortKey: number;
}
