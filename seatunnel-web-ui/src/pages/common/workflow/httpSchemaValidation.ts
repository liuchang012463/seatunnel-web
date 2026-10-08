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
  const fields = schema?.fields;
  if (!fields || typeof fields !== "object" || Array.isArray(fields)) {
    return [];
  }

  return Object.entries(fields)
    .filter(
      ([name, type]) => String(name || "").trim() && !String(type ?? "").trim(),
    )
    .map(([name]) => String(name));
};
