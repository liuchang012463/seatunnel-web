export const isHttpSourceNode = (node: any): boolean => {
  const config = node?.data?.config || {};
  return [
    node?.data?.dbType,
    node?.data?.pluginName,
    node?.data?.connectorType,
    config.pluginName,
    config.connectorType,
  ].some((marker) => String(marker || "").toUpperCase() === "HTTP");
};

export const getHttpSchemaFieldNamesWithoutType = (schema: any): string[] => {
  // The schema editor falls back to the legacy flat shape when the "fields" wrapper is absent,
  // so the validation has to read the same map.
  const rawFields = schema?.fields ?? schema;
  if (!rawFields || typeof rawFields !== "object" || Array.isArray(rawFields)) {
    return [];
  }
  const fields = rawFields as Record<string, unknown>;

  return Object.entries(fields)
    .filter(
      ([name, type]) => String(name || "").trim() && !String(type ?? "").trim(),
    )
    .map(([name]) => String(name));
};

/**
 * The engine only consumes the HTTP schema for the json format and the panel only renders the
 * schema editor there, so a leftover schema of another format must not block the save.
 */
export const isHttpJsonSourceNode = (node: any): boolean => {
  if (!isHttpSourceNode(node)) {
    return false;
  }
  const config = node?.data?.config || {};
  return String(config.format || "text").toLowerCase() === "json";
};
