import { Alert, Button, Empty, Space, Spin, Switch, Table, Tag, message } from 'antd';
import type { TableColumnsType } from 'antd';
import React, { useCallback, useEffect, useMemo, useState } from 'react';
import {
  fetchDataSourceMetadataStatus,
  fetchDataSourceOmResourceDetail,
  fetchDataSourceOmResources,
  updateDataSourceSampleData,
} from '../service';
import type {
  DataSourceOmResource,
  DataSourceOmResourceDetail,
  DataSourceOmResourceField,
} from '../types';
import './GenericDataExplorationDrawer.less';

interface GenericOmMetadataPanelProps {
  dataSourceId?: string;
  /** Name of the catalog entry selected on the left, used to match the OM asset. */
  resourceName?: string;
  /** Full path of the catalog entry; file sources match on its last segment. */
  resourcePath?: string;
}

function lastSegment(value?: string): string {
  const text = String(value ?? '').trim().replace(/\/+$/, '');
  if (!text) return '';
  const parts = text.split('/');
  return parts[parts.length - 1] || text;
}

function matches(resource: DataSourceOmResource, name: string): boolean {
  const candidate = name.toLowerCase();
  if (!candidate) return false;
  return [resource.name, resource.fullyQualifiedName]
    .map((value) => lastSegment(value).toLowerCase())
    .some((value) => value !== '' && value === candidate);
}

/**
 * OpenMetadata view of one non-database asset: the schema the metadata pipeline
 * extracted, any sample payload it collected, and the switch that decides whether
 * this data source may collect sample payload at all.
 *
 * Kafka, object storage and file-transfer assets have no OpenMetadata profiler, so
 * this panel deliberately shows schema and samples instead of metrics.
 */
const GenericOmMetadataPanel: React.FC<GenericOmMetadataPanelProps> = ({
  dataSourceId,
  resourceName,
  resourcePath,
}) => {
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string>();
  const [resources, setResources] = useState<DataSourceOmResource[]>([]);
  const [truncated, setTruncated] = useState(false);
  const [detail, setDetail] = useState<DataSourceOmResourceDetail>();
  const [detailLoading, setDetailLoading] = useState(false);
  const [detailError, setDetailError] = useState<string>();
  const [sampleDataSupported, setSampleDataSupported] = useState(false);
  const [sampleDataEnabled, setSampleDataEnabled] = useState(false);
  const [sampleDataSaving, setSampleDataSaving] = useState(false);

  const loadStatus = useCallback(async () => {
    if (!dataSourceId) return;
    try {
      const response = await fetchDataSourceMetadataStatus(dataSourceId);
      if (response.code === 0) {
        setSampleDataSupported(Boolean(response.data?.sampleDataSupported));
        setSampleDataEnabled(Boolean(response.data?.sampleDataEnabled));
      }
    } catch {
      // The status call only drives the switch; the asset listing still works.
      setSampleDataSupported(false);
    }
  }, [dataSourceId]);

  const loadResources = useCallback(async () => {
    if (!dataSourceId) return;
    setLoading(true);
    setError(undefined);
    try {
      const response = await fetchDataSourceOmResources(dataSourceId);
      if (response.code !== 0) {
        setResources([]);
        setError(response.message || '无法读取 OpenMetadata 资源');
        return;
      }
      setResources(response.data?.resources || []);
      setTruncated(Boolean(response.data?.truncated));
    } catch (requestError: any) {
      setResources([]);
      setError(requestError?.response?.data?.message || requestError?.message || '无法读取 OpenMetadata 资源');
    } finally {
      setLoading(false);
    }
  }, [dataSourceId]);

  useEffect(() => {
    setResources([]);
    setDetail(undefined);
    setError(undefined);
    setDetailError(undefined);
    void loadStatus();
    void loadResources();
  }, [loadResources, loadStatus]);

  const matchName = lastSegment(resourceName || resourcePath);
  const matched = useMemo(
    () => resources.find((resource) => matches(resource, matchName)),
    [matchName, resources],
  );

  useEffect(() => {
    let cancelled = false;
    setDetail(undefined);
    setDetailError(undefined);
    if (!dataSourceId || !matched?.id || !matched.entityType) return () => { cancelled = true; };
    setDetailLoading(true);
    fetchDataSourceOmResourceDetail(dataSourceId, String(matched.id), matched.entityType)
      .then((response) => {
        if (cancelled) return;
        if (response.code !== 0) {
          setDetailError(response.message || '无法读取 OpenMetadata 资源详情');
          return;
        }
        setDetail(response.data);
      })
      .catch((requestError: any) => {
        if (cancelled) return;
        setDetailError(requestError?.response?.data?.message || requestError?.message || '无法读取 OpenMetadata 资源详情');
      })
      .finally(() => {
        if (!cancelled) setDetailLoading(false);
      });
    return () => { cancelled = true; };
  }, [dataSourceId, matched?.id, matched?.entityType]);

  const toggleSampleData = async (next: boolean) => {
    if (!dataSourceId) return;
    setSampleDataSaving(true);
    try {
      const response = await updateDataSourceSampleData(dataSourceId, next);
      if (response.code !== 0) {
        message.error(response.message || '样本数据开关保存失败');
        return;
      }
      setSampleDataEnabled(next);
      message.success(next ? '已开启样本数据采集，下次扫描后生效' : '已关闭样本数据采集，下次扫描后生效');
      void loadStatus();
    } catch (requestError: any) {
      message.error(requestError?.response?.data?.message || requestError?.message || '样本数据开关保存失败');
    } finally {
      setSampleDataSaving(false);
    }
  };

  const fieldColumns: TableColumnsType<DataSourceOmResourceField> = [
    { title: '字段', dataIndex: 'name', key: 'name', ellipsis: true },
    { title: '类型', dataIndex: 'dataType', key: 'dataType', width: 160, ellipsis: true },
    { title: '描述', dataIndex: 'description', key: 'description', ellipsis: true },
  ];

  const sampleColumns = detail?.sampleColumns || [];
  const sampleRows = detail?.sampleRows || [];
  const sampleMessages = detail?.messages || [];
  const sampleTableColumns: TableColumnsType<Record<string, unknown>> = sampleColumns.map((column, index) => ({
    title: column,
    dataIndex: `c${index}`,
    key: `c${index}`,
    ellipsis: true,
  }));
  const sampleTableRows = sampleRows.map((row, rowIndex) => {
    const record: Record<string, unknown> = { key: rowIndex };
    row.forEach((cell, cellIndex) => {
      record[`c${cellIndex}`] = cell;
    });
    return record;
  });

  if (!dataSourceId) {
    return <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="请选择数据源" />;
  }

  return (
    <div className="generic-exploration__tab-pane">
      <div className="generic-exploration__tab-title">
        <div>
          <div className="generic-exploration__eyebrow">OPENMETADATA</div>
          <strong>元数据与样本数据</strong>
        </div>
        <Space size={4}>
          <Tag color={matched ? 'blue' : 'default'}>{matched ? '已收录' : '未收录'}</Tag>
          {matched?.entityLabel && <Tag>{matched.entityLabel}</Tag>}
        </Space>
      </div>

      {error && (
        <Alert
          className="generic-exploration__alert"
          type="warning"
          showIcon
          message="OpenMetadata 读取提示"
          description={error}
        />
      )}

      {sampleDataSupported && (
        <div className="generic-exploration__description">
          <span>样本数据采集</span>
          <Space align="center" size={8}>
            <Switch
              size="small"
              checked={sampleDataEnabled}
              loading={sampleDataSaving}
              onChange={(checked) => void toggleSampleData(checked)}
            />
            <span>{sampleDataEnabled ? '已开启' : '未开启（默认）'}</span>
          </Space>
          <p>
            开启后，采集任务会读取真实内容（Kafka 主题消息、SFTP 文件行）并写入 OpenMetadata，
            拥有查看样本数据权限的账号可读取。保存后需重新扫描该数据源才会采集。
          </p>
        </div>
      )}

      <Spin spinning={loading || detailLoading}>
        {!matched ? (
          <div className="generic-exploration__state">
            <strong>OpenMetadata 中未找到该资源</strong>
            <span>
              {resources.length > 0
                ? `当前数据源在 OpenMetadata 中共有 ${resources.length} 项资源，可在扫描后重新查看。`
                : '该数据源尚未在 OpenMetadata 中产生资源，请先执行扫描。'}
            </span>
            {resources.length > 0 && (
              <div className="generic-exploration__nav-list">
                {resources.slice(0, 20).map((resource) => (
                  <div className="generic-exploration__asset-item" key={resource.id || resource.name}>
                    <span className="generic-exploration__asset-item-copy">
                      <strong title={resource.name}>{resource.name}</strong>
                      <small title={resource.fullyQualifiedName}>
                        {resource.entityLabel || resource.entityType}
                        {typeof resource.fieldCount === 'number' ? ` · ${resource.fieldCount} 个字段` : ''}
                      </small>
                    </span>
                  </div>
                ))}
                {truncated && <div className="generic-exploration__nav-footer">仅显示前 20 项</div>}
              </div>
            )}
          </div>
        ) : (
          <>
            {detailError && (
              <Alert
                className="generic-exploration__alert"
                type="warning"
                showIcon
                message="详情读取提示"
                description={detailError}
              />
            )}
            <dl className="generic-exploration__property-list">
              <div><dt>名称</dt><dd>{matched.name}</dd></div>
              <div><dt>唯一名称</dt><dd><code>{matched.fullyQualifiedName}</code></dd></div>
              <div><dt>类型</dt><dd>{matched.entityLabel || matched.entityType}</dd></div>
              <div><dt>字段数</dt><dd>{typeof matched.fieldCount === 'number' ? matched.fieldCount : '—'}</dd></div>
              {matched.description && <div><dt>描述</dt><dd>{matched.description}</dd></div>}
              {matched.tags && matched.tags.length > 0 && (
                <div>
                  <dt>标签</dt>
                  <dd>
                    <Space size={4} wrap>
                      {matched.tags.map((tag) => <Tag key={tag} color="purple">{tag}</Tag>)}
                    </Space>
                  </dd>
                </div>
              )}
            </dl>

            <div className="generic-exploration__section-title">Schema</div>
            {(detail?.fields || []).length > 0 ? (
              <Table
                size="small"
                rowKey={(row) => String(row.name)}
                columns={fieldColumns}
                dataSource={detail?.fields || []}
                pagination={false}
              />
            ) : (
              <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="OpenMetadata 尚未抽取到字段" />
            )}

            <div className="generic-exploration__section-title">样本数据</div>
            {sampleMessages.length > 0 && (
              <div className="generic-exploration__nav-list">
                {sampleMessages.map((message, index) => (
                  <div className="generic-exploration__asset-item" key={`message-${index}`}>
                    <span className="generic-exploration__asset-item-copy">
                      <small>{message}</small>
                    </span>
                  </div>
                ))}
              </div>
            )}
            {sampleMessages.length === 0 && sampleTableRows.length > 0 && (
              <Table
                size="small"
                rowKey={(row) => String(row.key)}
                columns={sampleTableColumns}
                dataSource={sampleTableRows}
                pagination={false}
                scroll={{ x: 'max-content' }}
              />
            )}
            {sampleMessages.length === 0 && sampleTableRows.length === 0 && (
              <div className="generic-exploration__state">
                <strong>暂无样本数据</strong>
                <span>
                  {sampleDataSupported
                    ? '开启样本数据采集并重新扫描后，这里会显示采集到的内容。'
                    : '该类数据源在 OpenMetadata 中不提供样本数据。'}
                </span>
              </div>
            )}
            {detail?.sampleDataAvailable === false && sampleTableRows.length === 0 && sampleMessages.length === 0 && (
              <div className="generic-exploration__nav-footer">
                OpenMetadata 记录该资源尚无样本数据，或当前账号没有查看权限。
              </div>
            )}
          </>
        )}
      </Spin>

      <Space>
        <Button size="small" onClick={() => void loadResources()} loading={loading}>刷新 OpenMetadata 元数据</Button>
      </Space>
    </div>
  );
};

export default GenericOmMetadataPanel;
