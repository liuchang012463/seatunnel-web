import { fetchDataExplorationDatabases, fetchDataExplorationSchemas } from '@/pages/data-source/service';
import type { DataExplorationTable } from '@/pages/data-source/types';
import { useMemo } from 'react';
import { fetchSchemaTables, readMetadata, type CatalogSource, type LineageAsset, type Topology } from './service';
import useLineageRead from './useLineageRead';

export interface CatalogScope {
  sourceId?: string;
  databaseFqn?: string;
  schemaFqn?: string;
}

export default function useTopologyCatalog(scope: CatalogScope, source: CatalogSource, topology: Topology | undefined, revision: number) {
  const { id: sourceId, tableCatalog } = source;
  const prefer = topology?.assets.find((asset) => asset.type === 'table' && asset.dataSourceId === sourceId);
  const readDatabases = useMemo(() => tableCatalog
    ? () => readMetadata(fetchDataExplorationDatabases(sourceId)) : undefined, [sourceId, tableCatalog]);
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
    if (!tableCatalog) return topology?.assets.filter(({ dataSourceId }) => dataSourceId === sourceId) ?? [];
    if (!schema || !database || !tables.data) return [];
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
  }, [database, schema, sourceId, tableCatalog, tables.data, topology]);
  const assetDirectory = useMemo<DataExplorationTable[]>(() => roots.map(({ id, name, fullyQualifiedName }) => ({
    id, name, fullyQualifiedName,
  })), [roots]);

  return {
    source, databases, database, schemas, schema, tables, roots,
    directory: tableCatalog ? tables.data ?? [] : assetDirectory,
    loading: databases.loading || schemas.loading || tables.loading,
    failed: databases.failed || schemas.failed || tables.failed,
  };
}
