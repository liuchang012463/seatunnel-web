import { Input, Segmented, Select, Switch, Tooltip } from 'antd';
import { Database, FileCode2, Save, Table2, UploadCloud } from 'lucide-react';
import { memo } from 'react';
import PanelShell from '../PanelShell';
import ExtraParamsConfig from './ExtraParamsConfig';
import SinkSqlEditorSection from './SinkSqlEditorSection';
import { useSinkPanelLogic } from './hooks/useSinkPanelLogic';
import './index.less';
import KafkaNodeConfig from '@/pages/common/workflow/KafkaNodeConfig';
import ElasticsearchNodeConfig from '@/pages/common/workflow/ElasticsearchNodeConfig';
import {
  supportsDatabaseScope,
  supportsSchemaScope,
} from '@/pages/data-source/dataSourceRegistry';

interface Props {
  selectedNode: any;
  onClose: () => void;
  onNodeDataChange: (nodeId: string, newData: any) => void;
  sourceDataSourceId?: string;
}

function SinkPanel({ selectedNode, onClose, onNodeDataChange, sourceDataSourceId }: Props) {
  const isKafka = String(selectedNode?.data?.dbType || '').toUpperCase() === 'KAFKA';
  const isElasticsearch = String(selectedNode?.data?.dbType || '').toUpperCase() === 'ELASTICSEARCH';
  const {
    title,
    dbType,
    description,

    dataSourceId,
    database,
    schemaName,
    isSystemManagedTarget,
    autoCreateTable,
    writeMode,
    targetMode,
    table,
    targetTableName,
    sql,
    primaryKey,
    extraParams,

    dataSourceOptions,
    databaseOptions,
    schemaOptions,
    databaseLoading,
    schemaLoading,
    tableOptions,
    tableLoading,

    sqlPopoverOpen,
    setSqlPopoverOpen,
    selectedSqlTable,
    setSelectedSqlTable,
    generateSqlLoading,

    updateNode,
    handleDataSourceChange,
    handleDatabaseChange,
    handleSchemaChange,
    handleAutoCreateTableChange,
    handleWriteModeChange,
    handleTargetModeChange,
    handleGenerateSql,
  } = useSinkPanelLogic({
    selectedNode,
    onNodeDataChange,
    sourceDataSourceId,
  });

  const isDatabaseScoped = supportsDatabaseScope(dbType);
  const isSchemaScoped = supportsSchemaScope(dbType);

  if (isKafka || isElasticsearch) {
    return (
      <PanelShell
        eyebrow="Sink Config"
        title="目标配置"
        badge="输出节点"
        desc={`${isElasticsearch ? 'Elasticsearch' : 'Kafka'} 节点不使用关系型表、SQL 或自动建表配置`}
        heroTitle={title}
        heroDesc={description}
        heroTag="SINK"
        dbType={dbType}
        onClose={onClose}
        footer={<button type="button" className="workflow-panel__btn workflow-panel__btn--ghost" onClick={onClose}>关闭</button>}
      >
        <section className="workflow-panel__section">
          <div className="workflow-panel__group">
            <div className="workflow-panel__group-kicker">目标数据源</div>
            <Select
              value={dataSourceId}
              onChange={handleDataSourceChange}
              options={dataSourceOptions}
              placeholder={`请选择 ${isElasticsearch ? 'Elasticsearch' : 'Kafka'} 数据源`}
              showSearch
              optionFilterProp="label"
              style={{ width: '100%' }}
            />
          </div>
          <div className="workflow-panel__divider" />
          <div className="workflow-panel__group">
            <div className="workflow-panel__group-kicker">{isElasticsearch ? 'Elasticsearch 写入设置' : 'Kafka 写入设置'}</div>
            {isElasticsearch ? (
              <ElasticsearchNodeConfig
                role="sink"
                config={selectedNode?.data?.config || {}}
                indexOptions={tableOptions}
                indexLoading={tableLoading}
                onChange={(patch) => updateNode(patch)}
              />
            ) : (
              <KafkaNodeConfig
                role="sink"
                config={selectedNode?.data?.config || {}}
                topicOptions={tableOptions}
                topicLoading={tableLoading}
                onChange={(patch) => updateNode(patch)}
              />
            )}
          </div>
          {isElasticsearch && (
            <>
              <div className="workflow-panel__divider" />
              <div className="workflow-panel__group">
                <ExtraParamsConfig
                  params={extraParams}
                  onParamsChange={(params) => updateNode({ extraParams: params })}
                  selectedNode={selectedNode}
                  hideHeader
                />
              </div>
            </>
          )}
        </section>
      </PanelShell>
    );
  }

  return (
    <PanelShell
      eyebrow="Sink Config"
      title="目标配置"
      badge="输出节点"
      desc="修改后会实时同步到当前画布节点"
      heroTitle={title}
      heroDesc={description}
      heroTag="SINK"
      dbType={dbType}
      onClose={onClose}
      footer={
        <button type="button" className="workflow-panel__btn workflow-panel__btn--ghost" onClick={onClose}>
          关闭
        </button>
      }
    >
      <section className="workflow-panel__section">
        <div className="workflow-panel__group">
          <div className="workflow-panel__group-head">
            <div className="workflow-panel__group-kicker">目标数据源</div>
          </div>

          <div className="workflow-panel__meta-card workflow-panel__meta-card--compact">
            <div className="workflow-panel__meta-icon">
              <Database size={16} />
            </div>
            <Select
              value={dataSourceId}
              onChange={handleDataSourceChange}
              options={dataSourceOptions}
              placeholder="请选择目标数据源"
              showSearch
              optionFilterProp="label"
              className="workflow-panel__antd-select"
              style={{ width: '100%' }}
              classNames={{ popup: { root: 'workflow-panel__dropdown' } }}
            />
          </div>
        </div>

        <div className="workflow-panel__divider" />

        {isDatabaseScoped && (
          <>
            <div className="workflow-panel__group">
              <div className="workflow-panel__group-head">
                <div className="workflow-panel__group-kicker">
                  目标数据库
                </div>
              </div>
              <Select
                value={database || undefined}
                onChange={handleDatabaseChange}
                options={databaseOptions}
                loading={databaseLoading}
                placeholder={String(dbType).toUpperCase() === 'DORIS'
                  ? '请选择 Doris 目标数据库'
                  : '请选择目标数据库'}
                showSearch
                optionFilterProp="label"
                className="workflow-panel__antd-select"
                style={{ width: '100%' }}
                classNames={{ popup: { root: 'workflow-panel__dropdown' } }}
              />
              {isSchemaScoped && (
                <>
                  <div className="mt-3 mb-1 text-xs text-slate-500">Schema</div>
                  <Select
                    value={schemaName || undefined}
                    onChange={handleSchemaChange}
                    options={schemaOptions}
                    loading={schemaLoading}
                    placeholder="请选择 Schema"
                    showSearch
                    optionFilterProp="label"
                    className="workflow-panel__antd-select"
                    style={{ width: '100%' }}
                    classNames={{ popup: { root: 'workflow-panel__dropdown' } }}
                    disabled={!database}
                  />
                </>
              )}
              <div className="mt-1 text-xs leading-5 text-slate-500">
                {String(dbType).toUpperCase() === 'DORIS' && isSystemManagedTarget
                  ? '系统 Doris 仅允许选择当前来源数据源关联且 READY 的 ODS 数据库。'
                  : String(dbType).toUpperCase() === 'DORIS'
                    ? 'Doris 使用 database 作为唯一命名空间；默认连接库只作为回退值。'
                    : '选择数据库和 Schema 后，再选择对应范围内的目标表。'}
              </div>
            </div>
            <div className="workflow-panel__divider" />
          </>
        )}

        <div className="workflow-panel__group">
          <div className="workflow-panel__group-head" style={{ display: 'flex', justifyContent: 'space-between' }}>
            <div className="workflow-panel__group-kicker">写入设置</div>

            <div
              style={{
                display: 'inline-flex',
                alignItems: 'center',
                gap: 8,
                fontSize: 12,
                color: 'var(--st-color-text-secondary)',
              }}
            >
              <span>自动建表</span>
              <Switch size="small" checked={autoCreateTable} onChange={handleAutoCreateTableChange} />
            </div>
          </div>

          <div className="workflow-panel__field workflow-panel__field--full">
            {autoCreateTable ? (
              <div className="workflow-panel__field workflow-panel__field--full">
                <Input
                  value={targetTableName}
                  onChange={(e) => updateNode({ targetTableName: e.target.value })}
                  placeholder="请输入目标表名"
                  className="workflow-panel__antd-input"
                />
              </div>
            ) : (
              <>
                <div className="workflow-panel__field workflow-panel__field--full">
                  <Segmented
                    block
                    value={targetMode}
                    style={{ marginBottom: 12 }}
                    onChange={(value) => handleTargetModeChange(String(value))}
                    options={[
                      {
                        label: (
                          <div className="workflow-panel__segmented-item">
                            <Table2 size={14} />
                            <span>按表写入</span>
                          </div>
                        ),
                        value: 'table',
                      },
                      {
                        label: (
                          <div className="workflow-panel__segmented-item">
                            <FileCode2 size={14} />
                            <span>自定义 SQL</span>
                          </div>
                        ),
                        value: 'sql',
                      },
                    ]}
                  />
                </div>

                {targetMode === 'table' ? (
                  <div className="workflow-panel__field workflow-panel__field--full">
                    <Select
                      value={table}
                      onChange={(value) => updateNode({ table: value })}
                      options={tableOptions}
                      loading={tableLoading}
                      placeholder="请选择目标表"
                      className="workflow-panel__antd-select"
                      style={{ width: '100%' }}
                      classNames={{ popup: { root: 'workflow-panel__dropdown' } }}
                      showSearch
                      optionFilterProp="rawLabel"
                    />
                  </div>
                ) : (
                  <SinkSqlEditorSection
                    dataSourceId={dataSourceId}
                    dbType={dbType}
                    sql={sql}
                    tableOptions={tableOptions}
                    sqlPopoverOpen={sqlPopoverOpen}
                    setSqlPopoverOpen={setSqlPopoverOpen}
                    selectedSqlTable={selectedSqlTable}
                    setSelectedSqlTable={setSelectedSqlTable}
                    generateSqlLoading={generateSqlLoading}
                    onSqlChange={(value) => updateNode({ sql: value })}
                    onGenerateSql={handleGenerateSql}
                  />
                )}
              </>
            )}

            <div className="workflow-panel__form-grid" style={{ marginTop: autoCreateTable ? 12 : 0 }}>
              <div className="workflow-panel__divider" />
              <Segmented
                block
                value={writeMode}
                onChange={(value) => handleWriteModeChange(String(value))}
                options={[
                  {
                    label: (
                      <div className="workflow-panel__segmented-item">
                        <Table2 size={14} />
                        <span>追加写入</span>
                      </div>
                    ),
                    value: 'append',
                  },
                  {
                    label: (
                      <div className="workflow-panel__segmented-item">
                        <Tooltip title="覆盖写入：会先清空目标表中的已有数据，再执行本次同步。">
                          <div className="workflow-panel__segmented-item">
                            <Save size={14} />
                            <span>覆盖写入</span>
                          </div>
                        </Tooltip>
                      </div>
                    ),
                    value: 'overwrite',
                  },
                  {
                    label: (
                      <div className="workflow-panel__segmented-item">
                        <UploadCloud size={14} />
                        <span>Upsert</span>
                      </div>
                    ),
                    value: 'upsert',
                  },
                ]}
              />

              {writeMode === 'upsert' ? (
                <div className="workflow-panel__field">
                  <div className="workflow-panel__label" style={{ marginTop: 12 }}>
                    主键字段
                  </div>
                  <Input
                    value={primaryKey}
                    onChange={(e) => updateNode({ primaryKey: e.target.value })}
                    placeholder="请输入主键字段，例如：id"
                    className="workflow-panel__antd-input"
                  />
                </div>
              ) : (
                <div />
              )}
            </div>
          </div>
        </div>

        <div className="workflow-panel__divider" />

        <div className="workflow-panel__group">
          <ExtraParamsConfig
            params={extraParams}
            onParamsChange={(params) => updateNode({ extraParams: params })}
            selectedNode={selectedNode}
            hideHeader
          />
        </div>
      </section>
    </PanelShell>
  );
}

export default memo(SinkPanel);
