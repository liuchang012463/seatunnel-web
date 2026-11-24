import { fetchDataExplorationDatabases, fetchDataExplorationSchemas } from '@/pages/data-source/service';
import { useMemo } from 'react';
import { fetchCatalogSources, fetchSchemaTables, readMetadata, type LineageAsset, type Topology } from './service';
import useLineageRead from './useLineageRead';

export interface CatalogScope {
  sourceId?: string;
  databaseFqn?: string;
  schemaFqn?: string;
}

export default function useTopologyCatalog(scope: CatalogScope, topology: Topology | undefined, revision: number) {
  const sourcesResult = useLineageRead(fetchCatalogSources, revision);
  const sources = useMemo(() => {
    const withTables = new Set(topology?.assets.filter(({ type }) => type === 'table').map(({ dataSourceId }) => dataSourceId));
    const choices = (topology?.sources ?? []).filter(({ id }) => withTables.has(id));
    const byId = new Map(choices.map((source) => [source.id, source]));
    for (const source of sourcesResult.data ?? []) {
      if (!byId.has(source.id)) byId.set(source.id, source);
    }
    return [...byId.values()];
  }, [sourcesResult.data, topology]);
  const source = sources.find(({ id }) => id === scope.sourceId) ?? sources[0];
  const sourceId = source?.id;
  const prefer = topology?.assets.find((asset) => asset.type === 'table' && asset.dataSourceId === sourceId);
  const readDatabases = useMemo(() => sourceId
    ? () => readMetadata(fetchDataExplorationDatabases(sourceId)) : undefined, [sourceId]);
  const databases = useLineageRead(readDatabases, revision);
  const database = databases.data?.find(({ fullyQualifiedName }) => fullyQualifiedName === scope.databaseFqn)
    ?? databases.data?.find(({ name }) => name === prefer?.database) ?? databases.data?.[0];
  const databaseFqn = database?.fullyQualifiedName;

  const readSchemas = useMemo(() => sourceId && databaseFqn
    ? () => readMetadata(fetchDataExplorationSchemas(sourceId, databaseFqn)) : undefined, [databaseFqn, sourceId]);
  const schemas = useLineageRead(readSchemas, revision);
  const schema = schemas.data?.find(({ fullyQualifiedName }) => fullyQualifiedName === scope.schemaFqn)
    ?? schemas.data?.find(({ name }) => name === database?.name)
    ?? schemas.data?.find(({ name }) => name === 'public')
    ?? schemas.data?.find(({ name }) => name === prefer?.schema)
    ?? schemas.data?.[0];
  const schemaFqn = schema?.fullyQualifiedName;

  const readTables = useMemo(() => sourceId && databaseFqn && schemaFqn
    ? (signal: AbortSignal) => fetchSchemaTables(sourceId, databaseFqn, schemaFqn, signal) : undefined,
  [databaseFqn, schemaFqn, sourceId]);
  const tables = useLineageRead(readTables, revision);
  const roots = useMemo<LineageAsset[]>(() => {
    if (!sourceId || !schema || !database || !tables.data) return [];
    const known = new Map(topology?.assets.map((asset) => [asset.id, asset]));
    return tables.data.map((table) => known.get(table.id) ?? {
      id: table.id,
      dataSourceId: sourceId,
      type: 'table',
      name: table.name,
      database: database.name,
      schema: schema.name,
      fullyQualifiedName: table.fullyQualifiedName,
    });
  }, [database, schema, sourceId, tables.data, topology]);

  return {
    sources, source, databases, database, schemas, schema, tables, roots,
    loading: sourcesResult.loading || databases.loading || schemas.loading || tables.loading,
    failed: (sourcesResult.failed && !sources.length) || databases.failed || schemas.failed || tables.failed,
  };
}
