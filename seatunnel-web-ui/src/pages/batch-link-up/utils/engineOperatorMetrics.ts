type MetricRecord = Record<string, unknown>;

export interface EngineOperatorMetric {
  key: string;
  name: string;
  receivedBytes?: number;
  writeBytes?: number;
  receivedCount?: number;
  writeCount?: number;
  receivedQps?: number;
  writeQps?: number;
  receivedBytesPerSecond?: number;
  writeBytesPerSecond?: number;
  flushSignalQps?: number;
}

const asRecord = (value: unknown): MetricRecord | undefined =>
  value && typeof value === "object" && !Array.isArray(value)
    ? (value as MetricRecord)
    : undefined;

const asNumber = (value: unknown): number | undefined => {
  if (typeof value !== "number" && typeof value !== "string") return undefined;
  if (String(value).trim() === "") return undefined;

  const number = Number(value);
  return Number.isFinite(number) ? number : undefined;
};

const metricBlocks = (metrics: MetricRecord) => {
  const entries = Object.entries(metrics);
  const pipelineBlocks = entries.filter(([key, value]) => /^\d+$/.test(key) && asRecord(value));

  if (entries.length > 0 && pipelineBlocks.length === entries.length) {
    return pipelineBlocks.map(([pipelineId, value]) => ({
      pipelineId,
      metrics: asRecord(value) || {},
    }));
  }

  return [{ pipelineId: undefined, metrics }];
};

const getPipelineId = (vertex: MetricRecord) => {
  const name = String(vertex.vertexName || vertex.name || "");
  return name.match(/pipeline-(\d+)/i)?.[1];
};

const getOperatorId = (name: string) =>
  name.match(/(?:Source|Sink)\[\d+\]/i)?.[0];

const getVertexType = (vertex: MetricRecord, name: string) => {
  const type = String(vertex.type || "").toLowerCase();
  if (type === "source" || type === "sink") return type;
  if (/Source\[\d+\]/i.test(name)) return "source";
  if (/Sink\[\d+\]/i.test(name)) return "sink";
  return undefined;
};

const getTablePaths = (vertex: MetricRecord) => {
  if (!Array.isArray(vertex.tablePaths)) return [];
  return vertex.tablePaths
    .filter((path): path is string => typeof path === "string")
    .map((path) => path.trim().toLowerCase())
    .filter(Boolean);
};

const getMappedMetric = (
  metrics: MetricRecord,
  names: string[],
  operatorId: string | undefined,
  tablePaths: string[],
  roleVertexCount: number,
) => {
  for (const name of names) {
    const metric = metrics[name] ?? metrics[name[0].toLowerCase() + name.slice(1)];
    const mappedMetric = asRecord(metric);

    if (mappedMetric) {
      const matchingValues = Object.entries(mappedMetric)
        .filter(([key]) => {
          const normalizedKey = key.trim().toLowerCase();
          if (operatorId && normalizedKey.startsWith(`${operatorId.toLowerCase()}.`)) {
            return true;
          }
          return tablePaths.some((tablePath) =>
            normalizedKey === tablePath || normalizedKey.endsWith(`.${tablePath}`),
          );
        })
        .map(([, value]) => asNumber(value))
        .filter((value): value is number => value !== undefined);

      if (matchingValues.length > 0) {
        return matchingValues.reduce((sum, value) => sum + value, 0);
      }

      const allValues = Object.values(mappedMetric)
        .map(asNumber)
        .filter((value): value is number => value !== undefined);
      if (roleVertexCount === 1 && allValues.length === 1) return allValues[0];
    }

    const scalarMetric = asNumber(metric);
    if (scalarMetric !== undefined && roleVertexCount === 1) return scalarMetric;
  }

  return undefined;
};

const getVertexMetric = (
  blocks: ReturnType<typeof metricBlocks>,
  pipelineId: string | undefined,
  names: string[],
  operatorId: string | undefined,
  tablePaths: string[],
  roleVertexCount: number,
) => {
  const pipelineBlock = pipelineId
    ? blocks.find((block) => block.pipelineId === pipelineId)
    : undefined;
  const selectedBlocks = pipelineBlock
    ? [pipelineBlock]
    : blocks.length === 1
      ? blocks
      : [];

  for (const block of selectedBlocks) {
    const value = getMappedMetric(
      block.metrics,
      names,
      operatorId,
      tablePaths,
      roleVertexCount,
    );
    if (value !== undefined) return value;
  }

  return undefined;
};

export const parseEngineOperatorMetrics = (jobInfo: unknown): EngineOperatorMetric[] => {
  const info = asRecord(jobInfo);
  const jobDag = asRecord(info?.jobDag);
  const metrics = asRecord(info?.metrics);
  if (!jobDag || !metrics) return [];

  const rawVertices = Array.isArray(jobDag.vertexInfoMap)
    ? jobDag.vertexInfoMap
    : Object.values(asRecord(jobDag.vertexInfoMap) || {});
  const vertices = rawVertices.map(asRecord).filter((vertex): vertex is MetricRecord => !!vertex);
  const sourceCount = vertices.filter((vertex) => {
    const name = String(vertex.vertexName || vertex.name || "");
    return getVertexType(vertex, name) === "source";
  }).length;
  const sinkCount = vertices.filter((vertex) => {
    const name = String(vertex.vertexName || vertex.name || "");
    return getVertexType(vertex, name) === "sink";
  }).length;
  const blocks = metricBlocks(metrics);

  return vertices.flatMap((vertex, index) => {
    const name = String(vertex.vertexName || vertex.name || `Vertex ${vertex.vertexId ?? index + 1}`);
    const type = getVertexType(vertex, name);
    if (!type) return [];

    const operatorId = getOperatorId(name);
    const tablePaths = getTablePaths(vertex);
    const pipelineId = getPipelineId(vertex);
    const roleVertexCount = type === "source" ? sourceCount : sinkCount;
    const countMetrics = type === "source"
      ? ["TableSourceReceivedCount"]
      : ["TableSinkWriteCount"];
    const bytesMetrics = type === "source"
      ? ["TableSourceReceivedBytes"]
      : ["TableSinkWriteBytes"];
    const qpsMetrics = type === "source"
      ? ["TableSourceReceivedQPS"]
      : ["TableSinkWriteQPS"];
    const bpsMetrics = type === "source"
      ? ["TableSourceReceivedBytesPerSeconds"]
      : ["TableSinkWriteBytesPerSeconds"];

    const read = (names: string[]) =>
      getVertexMetric(blocks, pipelineId, names, operatorId, tablePaths, roleVertexCount);

    return [{
      key: String(vertex.vertexId ?? name),
      name,
      receivedBytes: type === "source"
        ? read([...bytesMetrics, "SourceReceivedBytes"])
        : undefined,
      writeBytes: type === "sink"
        ? read([...bytesMetrics, "SinkWriteBytes"])
        : undefined,
      receivedCount: type === "source"
        ? read([...countMetrics, "SourceReceivedCount"])
        : undefined,
      writeCount: type === "sink"
        ? read([...countMetrics, "SinkWriteCount"])
        : undefined,
      receivedQps: type === "source"
        ? read([...qpsMetrics, "SourceReceivedQPS", "SourceReceivedQps"])
        : undefined,
      writeQps: type === "sink"
        ? read([...qpsMetrics, "SinkWriteQPS", "SinkWriteQps"])
        : undefined,
      receivedBytesPerSecond: type === "source"
        ? read([...bpsMetrics, "SourceReceivedBytesPerSeconds"])
        : undefined,
      writeBytesPerSecond: type === "sink"
        ? read([...bpsMetrics, "SinkWriteBytesPerSeconds"])
        : undefined,
      flushSignalQps: type === "sink"
        ? read([
            "TableSinkFlushSignalQPS",
            "SinkFlushSignalQPS",
            "FlushSignalQPS",
            "SinkFlushSignalQps",
          ])
        : undefined,
    }];
  });
};
