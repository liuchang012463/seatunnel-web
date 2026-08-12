import { Button, DatePicker, Divider, Segmented, Select, Tooltip } from 'antd';
import { BarChart3, Database, Eye, FileCode2, Table2 } from 'lucide-react';
import dayjs from 'dayjs';
import customParseFormat from 'dayjs/plugin/customParseFormat';
import { memo, useRef } from 'react';
import PanelShell from '../PanelShell';
import ExtraParamsConfig from './ExtraParamsConfig';

import QualityDetail from '@/pages/batch-link-up/DataViewSQL';
import { useSourcePanelLogic } from './hooks/useSourcePanelLogic';
import './index.less';
import SqlEditorSection from './SqlEditorSection';
import KafkaNodeConfig from '@/pages/common/workflow/KafkaNodeConfig';
import HttpNodeConfig from '@/pages/common/workflow/HttpNodeConfig';
import ElasticsearchNodeConfig from '@/pages/common/workflow/ElasticsearchNodeConfig';

dayjs.extend(customParseFormat);

interface Props {
  selectedNode: any;
  onClose: () => void;
  onNodeDataChange: (nodeId: string, newData: any) => void;
  scheduleConfig: any;
  isIncremental?: boolean;
}

function SourcePanel({ selectedNode, onClose, onNodeDataChange, scheduleConfig, isIncremental = false }: Props) {
  const qualityDetailRef = useRef<any>(null);
  const isKafka = String(selectedNode?.data?.dbType || '').toUpperCase() === 'KAFKA';
  const isHttp = String(selectedNode?.data?.dbType || '').toUpperCase() === 'HTTP';
  const isElasticsearch = String(selectedNode?.data?.dbType || '').toUpperCase() === 'ELASTICSEARCH';

  const {
    title,
    dbType,
    description,
    dataSourceId,
    readMode,
    table,
    sql,
    extraParams,
    incrementalConfig,
    meta,

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

  if (isKafka || isHttp || isElasticsearch) {
    return (
      <PanelShell
        eyebrow="Source Config"
        title="来源配置"
        badge="输入节点"
        desc={`${isHttp ? 'HTTP' : isElasticsearch ? 'Elasticsearch' : 'Kafka'} 节点不支持关系型表、SQL、列解析或数据预览${isHttp && isIncremental ? '；增量窗口请在请求参数或 Body 中手动使用 ${window_start}/${window_end}' : ''}`}
        heroTitle={title}
        heroDesc={description}
        heroTag="SOURCE"
        dbType={dbType}
        onClose={onClose}
        footer={<button type="button" className="workflow-panel__btn workflow-panel__btn--ghost" onClick={onClose}>关闭</button>}
      >
        <section className="workflow-panel__section">
          <div className="workflow-panel__group">
            <div className="workflow-panel__group-kicker">数据源</div>
            <Select
              value={dataSourceId}
              onChange={handleDataSourceChange}
              options={dataSourceOptions}
              placeholder={`请选择 ${isHttp ? 'HTTP' : isElasticsearch ? 'Elasticsearch' : 'Kafka'} 数据源`}
              showSearch
              optionFilterProp="label"
              style={{ width: '100%' }}
            />
          </div>
          <div className="workflow-panel__divider" />
          <div className="workflow-panel__group">
            <div className="workflow-panel__group-kicker">
              {isHttp ? 'HTTP 请求与响应设置' : isElasticsearch ? 'Elasticsearch 查询设置' : 'Kafka 消费设置'}
            </div>
            {isHttp ? (
              <HttpNodeConfig
                config={selectedNode?.data?.config || {}}
                isIncremental={isIncremental}
                onChange={(patch) => updateNode(patch)}
              />
            ) : isKafka ? (
              <KafkaNodeConfig
                role="source"
                config={selectedNode?.data?.config || {}}
                topicOptions={tableOptions}
                topicLoading={tableLoading}
                onChange={(patch) => updateNode(patch)}
              />
            ) : (
              <ElasticsearchNodeConfig
                role="source"
                config={selectedNode?.data?.config || {}}
                indexOptions={tableOptions}
                indexLoading={tableLoading}
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
              <div className="workflow-panel__group-kicker">读取方式</div>

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
            </div>

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
                    updateNode({ table: value, incrementalConfig: undefined }, undefined, {
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
                  updateNode({ sql: value, incrementalConfig: undefined }, undefined, {
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
          </div>

          {isIncremental && !isKafka && !isHttp && (
            <>
              <div className="workflow-panel__divider" />
              <div className="workflow-panel__group">
                <div className="workflow-panel__group-head">
                  <div className="workflow-panel__group-kicker">增量配置</div>
                </div>
                <div className="mb-3 text-xs leading-5 text-slate-500">
                  先完成表或 SQL 的字段解析，再选择时间类型字段作为增量识别字段。
                </div>
                <div className="workflow-panel__field workflow-panel__field--full">
                  <div className="mb-1 text-xs text-slate-500">增量识别字段</div>
                  <Select
                    value={incrementalConfig?.fieldName || undefined}
                    onChange={(value) =>
                      updateNode({
                        incrementalConfig: {
                          enabled: true,
                          fieldName: value,
                          startValue: incrementalConfig?.startValue || '1970-01-01 00:00:00',
                        },
                      })
                    }
                    options={(meta?.outputSchema || [])
                      .filter((column: any) =>
                        /date|time|timestamp/i.test(
                          String(column?.type || column?.fieldType || column?.dataType || ''),
                        ),
                      )
                      .map((column: any) => {
                        const fieldName = column?.originFieldName || column?.fieldName || column?.name;
                        return {
                          label: fieldName,
                          value: fieldName,
                        };
                      })}
                    disabled={!Array.isArray(meta?.outputSchema) || meta.outputSchema.length === 0}
                    placeholder="请选择时间类型字段"
                    className="workflow-panel__antd-select"
                    style={{ width: '100%' }}
                    popupClassName="workflow-panel__dropdown"
                    showSearch
                    optionFilterProp="label"
                  />
                  {(!Array.isArray(meta?.outputSchema) || meta.outputSchema.length === 0) && (
                    <div className="mt-1 text-xs text-slate-400">请先点击“字段解析”获取可选字段。</div>
                  )}
                </div>
                <div className="workflow-panel__field workflow-panel__field--full mt-3">
                  <div className="mb-1 text-xs text-slate-500">增量起始值</div>
                  <DatePicker
                    value={
                      incrementalConfig?.startValue
                        ? dayjs(incrementalConfig.startValue, 'YYYY-MM-DD HH:mm:ss')
                        : null
                    }
                    showTime
                    format="YYYY-MM-DD HH:mm:ss"
                    placeholder="请选择增量起始时间"
                    style={{ width: '100%' }}
                    onChange={(value) =>
                      updateNode({
                        incrementalConfig: {
                          enabled: true,
                          fieldName: incrementalConfig?.fieldName || '',
                          startValue: value ? value.format('YYYY-MM-DD HH:mm:ss') : '',
                        },
                      })
                    }
                  />
                </div>
              </div>
            </>
          )}

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
