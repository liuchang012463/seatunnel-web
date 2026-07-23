import { Input, Segmented, Select, Switch, Tooltip } from 'antd';
import { Database, FileCode2, FolderOpen, Save, Table2, UploadCloud } from 'lucide-react';
import { memo } from 'react';
import PanelShell from '../PanelShell';
import ExtraParamsConfig from './ExtraParamsConfig';
import SinkSqlEditorSection from './SinkSqlEditorSection';
import { useSinkPanelLogic } from './hooks/useSinkPanelLogic';
import './index.less';

interface Props {
  selectedNode: any;
  onClose: () => void;
  onNodeDataChange: (nodeId: string, newData: any) => void;
}

function SinkPanel({ selectedNode, onClose, onNodeDataChange }: Props) {
  const {
    title,
    dbType,
    isSftp,
    description,

    dataSourceId,
    autoCreateTable,
    writeMode,
    targetMode,
    table,
    targetTableName,
    filePath,
    format,
    delimiter,
    fileNameExpression,
    behavior,
    sql,
    primaryKey,
    extraParams,

    dataSourceOptions,
    tableOptions,
    tableLoading,

    sqlPopoverOpen,
    setSqlPopoverOpen,
    selectedSqlTable,
    setSelectedSqlTable,
    generateSqlLoading,

    updateNode,
    handleDataSourceChange,
    handleAutoCreateTableChange,
    handleWriteModeChange,
    handleTargetModeChange,
    handleGenerateSql,
  } = useSinkPanelLogic({
    selectedNode,
    onNodeDataChange,
  });

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
              popupClassName="workflow-panel__dropdown"
            />
          </div>
        </div>

        <div className="workflow-panel__divider" />

        <div className="workflow-panel__group">
          <div className="workflow-panel__group-head" style={{ display: 'flex', justifyContent: 'space-between' }}>
            <div className="workflow-panel__group-kicker">
              {isSftp ? '文件写入' : '写入设置'}
            </div>

            {!isSftp && (
              <div
                style={{
                  display: 'inline-flex',
                  alignItems: 'center',
                  gap: 8,
                  fontSize: 12,
                  color: '#667085',
                }}
              >
                <span>自动建表</span>
                <Switch size="small" checked={autoCreateTable} onChange={handleAutoCreateTableChange} />
              </div>
            )}
          </div>

          <div className="workflow-panel__field workflow-panel__field--full">
            {isSftp ? (
              <>
                <div className="workflow-panel__field workflow-panel__field--full">
                  <div className="workflow-panel__label" style={{ marginBottom: 8 }}>
                    输出目录/路径
                  </div>
                  <Input
                    value={filePath}
                    prefix={<FolderOpen size={14} />}
                    onChange={(e) =>
                      updateNode({
                        filePath: e.target.value,
                        path: e.target.value,
                        table: e.target.value,
                        targetTableName: e.target.value,
                        targetMode: 'file',
                      })
                    }
                    placeholder="例如：/data/import/orders"
                    className="workflow-panel__antd-input"
                  />
                </div>
                <div className="workflow-panel__form-grid" style={{ marginTop: 12 }}>
                  <div className="workflow-panel__field">
                    <div className="workflow-panel__label" style={{ marginBottom: 8 }}>
                      文件格式
                    </div>
                    <Select
                      value={format}
                      onChange={(value) => updateNode({ format: value })}
                      options={[
                        { label: 'csv', value: 'csv' },
                        { label: 'text', value: 'text' },
                        { label: 'json', value: 'json' },
                        { label: 'parquet', value: 'parquet' },
                        { label: 'excel', value: 'excel' },
                      ]}
                      className="workflow-panel__antd-select"
                      style={{ width: '100%' }}
                    />
                  </div>
                  {(format === 'csv' || format === 'text') && (
                    <div className="workflow-panel__field">
                      <div className="workflow-panel__label" style={{ marginBottom: 8 }}>
                        字段分隔符
                      </div>
                      <Input
                        value={delimiter}
                        onChange={(e) => updateNode({ delimiter: e.target.value })}
                        placeholder=","
                        className="workflow-panel__antd-input"
                      />
                    </div>
                  )}
                </div>
                <div className="workflow-panel__field workflow-panel__field--full" style={{ marginTop: 12 }}>
                  <div className="workflow-panel__label" style={{ marginBottom: 8 }}>
                    文件名表达式
                  </div>
                  <Input
                    value={fileNameExpression}
                    onChange={(e) => updateNode({ fileNameExpression: e.target.value })}
                    placeholder="${transactionId}_${now}"
                    className="workflow-panel__antd-input"
                  />
                </div>
                <div className="workflow-panel__field workflow-panel__field--full" style={{ marginTop: 12 }}>
                  <div className="workflow-panel__label" style={{ marginBottom: 8 }}>
                    文件已存在时
                  </div>
                  <Select
                    value={behavior}
                    onChange={(value) => updateNode({ behavior: value })}
                    options={[
                      { label: 'DEFAULT', value: 'DEFAULT' },
                      { label: 'OVERWRITE', value: 'OVERWRITE' },
                      { label: 'APPEND', value: 'APPEND' },
                      { label: 'ERROR', value: 'ERROR' },
                    ]}
                    className="workflow-panel__antd-select"
                    style={{ width: '100%' }}
                  />
                </div>
              </>
            ) : autoCreateTable ? (
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
                      popupClassName="workflow-panel__dropdown"
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

            {!isSftp && (
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
            )}
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
