import { Button, Divider, Input, Segmented, Select, Switch, Tooltip } from 'antd';
import { BarChart3, Database, Eye, FileCode2, FolderOpen, Table2 } from 'lucide-react';
import { memo, useRef } from 'react';
import PanelShell from '../PanelShell';
import ExtraParamsConfig from './ExtraParamsConfig';

import QualityDetail from '@/pages/batch-link-up/DataViewSQL';
import { useSourcePanelLogic } from './hooks/useSourcePanelLogic';
import './index.less';
import SqlEditorSection from './SqlEditorSection';

interface Props {
  selectedNode: any;
  onClose: () => void;
  onNodeDataChange: (nodeId: string, newData: any) => void;
  scheduleConfig: any;
}

function SourcePanel({ selectedNode, onClose, onNodeDataChange, scheduleConfig }: Props) {
  const qualityDetailRef = useRef<any>(null);

  const {
    title,
    dbType,
    isSftp,
    description,
    dataSourceId,
    readMode,
    table,
    filePath,
    format,
    delimiter,
    hasHeader,
    sql,
    extraParams,

    dataSourceOptions,
    tableOptions,
    tableLoading,

    sqlPopoverOpen,
    setSqlPopoverOpen,
    resolvePopoverOpen,
    selectedSqlTable,
    setSelectedSqlTable,
    generateSqlLoading,
    resolveSqlLoading,
    resolvedSqlPreview,

    updateNode,
    handleDataSourceChange,
    handleReadModeChange,
    handlePreview,
    handleGenerateSql,
    handleResolveSqlPreview,
    handleOpenResolvePopover,
    viewLoading,
    handleResolveColumns,
  } = useSourcePanelLogic({
    selectedNode,
    onNodeDataChange,
    qualityDetailRef,
    scheduleConfig,
  });

  return (
    <>
      <PanelShell
        eyebrow="Source Config"
        title="来源配置"
        badge="输入节点"
        desc="修改后会实时同步到当前画布节点"
        heroTitle={title}
        heroDesc={description}
        heroTag="SOURCE"
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
              <div className="workflow-panel__group-kicker">数据源</div>
            </div>

            <div className="workflow-panel__meta-card workflow-panel__meta-card--compact">
              <div className="workflow-panel__meta-icon">
                <Database size={16} />
              </div>
              <Select
                value={dataSourceId}
                onChange={handleDataSourceChange}
                options={dataSourceOptions}
                placeholder="请选择来源数据源"
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
                {isSftp ? '文件读取' : '读取方式'}
              </div>

              {!isSftp && (
                <div style={{ display: 'flex', alignItems: 'center' }}>
                  <Tooltip title="预览读取结果样例数据">
                    <Button
                      size="small"
                      type="text"
                      icon={<Eye size={14} />}
                      onClick={handlePreview}
                      loading={viewLoading}
                    >
                      预览
                    </Button>
                  </Tooltip>

                  <Divider type="vertical" style={{ padding: 0, margin: '0 4px' }} />

                  <Tooltip title="解析当前读取配置下的字段信息">
                    <Button onClick={handleResolveColumns} size="small" type="text" icon={<BarChart3 size={14} />}>
                      字段解析
                    </Button>
                  </Tooltip>
                </div>
              )}
            </div>

            {isSftp ? (
              <>
                <div className="workflow-panel__field workflow-panel__field--full">
                  <div className="workflow-panel__label" style={{ marginBottom: 8 }}>
                    文件路径
                  </div>
                  <Input
                    value={filePath}
                    prefix={<FolderOpen size={14} />}
                    onChange={(e) =>
                      updateNode(
                        {
                          filePath: e.target.value,
                          path: e.target.value,
                          table: e.target.value,
                          readMode: 'file',
                        },
                        undefined,
                        {
                          outputSchema: [],
                          schemaStatus: 'idle',
                          schemaError: '',
                        }
                      )
                    }
                    placeholder="例如：/data/export/orders.csv"
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
                {format === 'csv' && (
                  <div
                    style={{
                      marginTop: 12,
                      display: 'inline-flex',
                      alignItems: 'center',
                      gap: 8,
                      fontSize: 12,
                      color: '#667085',
                    }}
                  >
                    <span>包含表头</span>
                    <Switch
                      size="small"
                      checked={hasHeader}
                      onChange={(checked) => updateNode({ hasHeader: checked })}
                    />
                  </div>
                )}
              </>
            ) : (
              <>
                <Segmented
                  block
                  value={readMode}
                  onChange={(value) => handleReadModeChange(String(value))}
                  options={[
                    {
                      label: (
                        <div className="workflow-panel__segmented-item">
                          <Table2 size={14} />
                          <span>按表读取</span>
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

                {readMode === 'table' ? (
                  <div className="workflow-panel__field workflow-panel__field--full">
                    <Select
                      value={table}
                      onChange={(value) =>
                        updateNode({ table: value }, undefined, {
                          outputSchema: [],
                          schemaStatus: 'idle',
                          schemaError: '',
                        })
                      }
                      options={tableOptions}
                      loading={tableLoading}
                      placeholder="请选择来源表"
                      className="workflow-panel__antd-select"
                      style={{ width: '100%' }}
                      popupClassName="workflow-panel__dropdown"
                      showSearch
                      optionFilterProp="rawLabel"
                    />
                  </div>
                ) : (
                  <SqlEditorSection
                    sourceDataSourceId={dataSourceId}
                    dbType={dbType}
                    sql={sql}
                    tableOptions={tableOptions}
                    sqlPopoverOpen={sqlPopoverOpen}
                    setSqlPopoverOpen={setSqlPopoverOpen}
                    resolvePopoverOpen={resolvePopoverOpen}
                    selectedSqlTable={selectedSqlTable}
                    setSelectedSqlTable={setSelectedSqlTable}
                    generateSqlLoading={generateSqlLoading}
                    resolveSqlLoading={resolveSqlLoading}
                    resolvedSqlPreview={resolvedSqlPreview}
                    onSqlChange={(value: any) =>
                      updateNode({ sql: value }, undefined, {
                        outputSchema: [],
                        schemaStatus: 'idle',
                        schemaError: '',
                      })
                    }
                    onGenerateSql={handleGenerateSql}
                    onResolveSqlPreview={handleResolveSqlPreview}
                    onOpenResolvePopover={handleOpenResolvePopover}
                  />
                )}
              </>
            )}
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
      <QualityDetail ref={qualityDetailRef} />
    </>
  );
}

export default memo(SourcePanel);
