import { ArrowLeftOutlined, FolderOpenOutlined, SwapOutlined } from '@ant-design/icons';
import { history, useParams } from '@umijs/max';
import {
  Alert,
  Button,
  Card,
  Form,
  Input,
  InputNumber,
  List,
  Modal,
  message,
  Radio,
  Select,
  Space,
  Switch,
  Tag,
} from 'antd';
import React, { useEffect, useMemo, useState } from 'react';
import { dataSourceCatalogApi, fetchDataSourceAll } from '@/pages/data-source/service';
import { seatunnelJobDefinitionApi } from '../../api';
import {
  canUseIncrementalFileSync,
  connectorForFileType,
  FILE_DATASOURCE_TYPES,
} from './support';
import type { FileDataSourceType } from './support';

type RemoteEntry = { name: string; path: string; type: 'DIRECTORY' | 'FILE' | 'LINK'; size?: number };
type DataSourceOption = { id: string; name: string; dbType: FileDataSourceType };

const FileSyncPage: React.FC = () => {
  const { id } = useParams<{ id: string }>();
  const [form] = Form.useForm();
  const [sources, setSources] = useState<DataSourceOption[]>([]);
  const [preview, setPreview] = useState('');
  const [loading, setLoading] = useState(false);
  const [picker, setPicker] = useState<{ field: 'sourcePath' | 'targetPath'; datasourceId: string; path?: string }>();
  const [entries, setEntries] = useState<RemoteEntry[]>([]);
  const [browseLoading, setBrowseLoading] = useState(false);
  const syncType = Form.useWatch('syncType', form) || 'FULL';
  const sourceDatasourceId = Form.useWatch('sourceDatasourceId', form);
  const targetDatasourceId = Form.useWatch('targetDatasourceId', form);
  const source = useMemo(
    () => sources.find((item) => String(item.id) === String(sourceDatasourceId)),
    [sources, sourceDatasourceId],
  );
  const target = useMemo(
    () => sources.find((item) => String(item.id) === String(targetDatasourceId)),
    [sources, targetDatasourceId],
  );

  useEffect(() => {
    fetchDataSourceAll().then((res: any) => {
      const raw = res?.data?.bizData || res?.data || [];
      setSources(
        (Array.isArray(raw) ? raw : []).filter((item: any) =>
          FILE_DATASOURCE_TYPES.includes(item.dbType),
        ),
      );
    });
    const scene = new URLSearchParams(location.search).get('scene');
    if (scene === 'edit' && id) {
      seatunnelJobDefinitionApi.selectEditDetail(id).then((res: any) => {
        const data = res?.data;
        const nodes = data?.workflow?.nodes || [];
        const sourceNode = nodes.find((node: any) => node?.data?.nodeType === 'source')?.data?.config || {};
        const sinkNode = nodes.find((node: any) => node?.data?.nodeType === 'sink')?.data?.config || {};
        form.setFieldsValue({
          jobName: data?.basic?.jobName || data?.jobName,
          jobDesc: data?.basic?.jobDesc || data?.jobDesc,
          clientId: data?.basic?.clientId || data?.clientId,
          sourceDatasourceId: sourceNode.dataSourceId,
          targetDatasourceId: sinkNode.dataSourceId,
          sourcePath: sourceNode.path,
          targetPath: sinkNode.targetPath || sinkNode.path,
          fileFilterPattern: sourceNode.fileFilterPattern || '.*',
          filenameExtension: sourceNode.filenameExtension,
          binaryChunkSize: sourceNode.binaryChunkSize || 1048576,
          binaryCompleteFileMode: sourceNode.binaryCompleteFileMode !== false,
          syncType: sourceNode.syncType || 'FULL',
          cronExpression: data?.schedule?.cronExpression,
        });
      });
    }
  }, [id, form]);

  const incrementalSupported = canUseIncrementalFileSync(source?.dbType, target?.dbType);

  useEffect(() => {
    if (!incrementalSupported && syncType === 'INCREMENTAL') {
      form.setFieldValue('syncType', 'FULL');
      message.info('SeaTunnel 2.3.13 的 S3File 不支持增量 update，已切换为全量复制');
    }
  }, [form, incrementalSupported, syncType]);

  const browse = async (next: typeof picker) => {
    if (!next?.datasourceId) {
      message.warning('请先选择数据源');
      return;
    }
    setPicker(next);
    setBrowseLoading(true);
    try {
      const res = await dataSourceCatalogApi.listFiles(next.datasourceId, next.path);
      setEntries(res?.data || []);
    } finally {
      setBrowseLoading(false);
    }
  };

  const payload = async () => {
    const values = await form.validateFields();
    const sourceMeta = sources.find((item) => String(item.id) === String(values.sourceDatasourceId))!;
    const targetMeta = sources.find((item) => String(item.id) === String(values.targetDatasourceId))!;
    if (!canUseIncrementalFileSync(sourceMeta.dbType, targetMeta.dbType)
      && values.syncType === 'INCREMENTAL') {
      throw new Error('SeaTunnel 2.3.13 的 S3File 不支持增量 update');
    }
    if (values.syncType === 'INCREMENTAL' && String(values.sourceDatasourceId) !== String(values.targetDatasourceId)) {
      throw new Error('增量 update 模式只支持同一数据源内的目录同步');
    }
    const sourceConfig = {
      dataSourceId: values.sourceDatasourceId,
      dbType: sourceMeta.dbType,
      pluginName: connectorForFileType(sourceMeta.dbType),
      connectorType: connectorForFileType(sourceMeta.dbType),
      path: values.sourcePath,
      targetPath: values.targetPath,
      syncType: values.syncType,
      fileFilterPattern: values.fileFilterPattern,
      filenameExtension: values.filenameExtension,
      binaryChunkSize: values.binaryChunkSize,
      binaryCompleteFileMode: values.binaryCompleteFileMode,
      updateStrategy: 'only_add',
      compareMode: 'len_mtime',
    };
    const sinkConfig = {
      dataSourceId: values.targetDatasourceId,
      dbType: targetMeta.dbType,
      pluginName: connectorForFileType(targetMeta.dbType),
      connectorType: connectorForFileType(targetMeta.dbType),
      targetPath: values.targetPath,
    };
    return {
      id,
      basic: {
        mode: 'FILE_SYNC',
        runtimeType: 'BATCH',
        jobName: values.jobName,
        jobDesc: values.jobDesc,
        clientId: values.clientId,
      },
      workflow: {
        nodes: [
          { id: 'file-source', type: 'source', data: { nodeType: 'source', config: sourceConfig } },
          { id: 'file-sink', type: 'sink', data: { nodeType: 'sink', config: sinkConfig } },
        ],
        edges: [{ id: 'file-transfer', source: 'file-source', target: 'file-sink' }],
      },
      schedule: {
        paramsList: [],
        scheduleRunType: values.cronExpression ? 'normal' : 'pause',
        scheduleType: 'day',
        cronExpression: values.cronExpression,
      },
      env: { jobMode: 'BATCH', parallelism: values.parallelism || 1, priority: 'MEDIUM' },
    };
  };

  const runAction = async (action: 'preview' | 'save') => {
    setLoading(true);
    try {
      const data = await payload();
      const res: any =
        action === 'preview'
          ? await seatunnelJobDefinitionApi.buildFileSyncConfig(data)
          : await seatunnelJobDefinitionApi.saveOrUpdateFileSync(data);
      if (res?.code !== 0) throw new Error(res?.message || '操作失败');
      if (action === 'preview') setPreview(String(res?.data || ''));
      else message.success('文件同步任务已保存');
    } catch (error: any) {
      message.error(error?.message || '操作失败');
    } finally {
      setLoading(false);
    }
  };

  const endpointCard = (role: 'source' | 'target') => {
    const isSource = role === 'source';
    const datasourceId = isSource ? sourceDatasourceId : targetDatasourceId;
    const field = isSource ? 'sourcePath' : 'targetPath';
    const meta = isSource ? source : target;
    return (
      <Card
        className="flex-1 rounded-2xl border-slate-200"
        title={
          <Space>
            <Tag color={isSource ? 'blue' : 'green'}>{isSource ? '来源' : '去向'}</Tag>
            {isSource ? '远程读取端' : '远程写入端'}
          </Space>
        }
      >
        <Form.Item name={`${role}DatasourceId`} label="数据源" rules={[{ required: true }]}>
          <Select
            options={sources.map((item) => ({ label: `${item.name} · ${item.dbType}`, value: item.id }))}
            placeholder="选择 FTP/SFTP/S3/MinIO 数据源"
          />
        </Form.Item>
        <Form.Item name={field} label={isSource ? '同步目录' : '目标目录'} rules={[{ required: true }]}>
          <Input
            addonAfter={
              <Button type="text" icon={<FolderOpenOutlined />} onClick={() => browse({ field, datasourceId })}>
                浏览
              </Button>
            }
            placeholder="/incoming/files 或 /bucket-prefix"
          />
        </Form.Item>
        <div className="rounded-xl bg-slate-50 p-3 text-xs text-slate-500">
          协议：{meta?.dbType || '待选择'} · 数据格式固定为 binary
        </div>
      </Card>
    );
  };

  return (
    <div className="min-h-screen bg-slate-50 p-6">
      <div className="mx-auto max-w-6xl">
        <div className="mb-5 flex items-center justify-between">
          <div>
            <h1 className="m-0 text-2xl font-bold text-slate-900">文件与对象存储同步</h1>
            <p className="mt-1 text-slate-500">按目录或 Prefix 传输二进制流，不涉及表、字段或 SQL 映射。</p>
          </div>
          <Button icon={<ArrowLeftOutlined />} onClick={() => history.push('/sync/batch-link-up')}>
            返回任务列表
          </Button>
        </div>
        <Alert
          className="mb-5 rounded-xl"
          showIcon
          type="info"
          message="复制策略：仅复制新增或内容变化的文件，不删除目标端已有文件。"
        />
        <Form
          form={form}
          layout="vertical"
          initialValues={{
            syncType: 'FULL',
            fileFilterPattern: '.*',
            binaryChunkSize: 1048576,
            binaryCompleteFileMode: true,
            parallelism: 1,
          }}
        >
          <Card className="mb-5 rounded-2xl" title="任务信息">
            <div className="grid grid-cols-1 gap-4 md:grid-cols-3">
              <Form.Item name="jobName" label="任务名称" rules={[{ required: true }]}>
                <Input />
              </Form.Item>
              <Form.Item name="clientId" label="执行客户端 ID" rules={[{ required: true }]}>
                <InputNumber className="w-full" min={1} />
              </Form.Item>
              <Form.Item name="parallelism" label="并行度">
                <InputNumber className="w-full" min={1} />
              </Form.Item>
            </div>
            <Form.Item name="jobDesc" label="任务描述">
              <Input.TextArea rows={2} />
            </Form.Item>
          </Card>
          <div className="mb-5 flex flex-col items-stretch gap-4 lg:flex-row lg:items-center">
            {endpointCard('source')}
            <div className="flex justify-center text-indigo-500">
              <SwapOutlined className="text-2xl" />
            </div>
            {endpointCard('target')}
          </div>
          <Card className="mb-5 rounded-2xl" title="文件与执行策略">
            <div className="grid grid-cols-1 gap-4 md:grid-cols-3">
              <Form.Item name="syncType" label="同步方式">
                <Radio.Group
                  optionType="button"
                  options={[
                    { label: '全量复制', value: 'FULL' },
                    { label: '增量 update', value: 'INCREMENTAL', disabled: !incrementalSupported },
                  ]}
                />
              </Form.Item>
              <Form.Item name="fileFilterPattern" label="文件名正则">
                <Input />
              </Form.Item>
              <Form.Item name="filenameExtension" label="扩展名过滤">
                <Input placeholder="zip,bin" />
              </Form.Item>
              <Form.Item name="binaryChunkSize" label="分块字节数">
                <InputNumber className="w-full" min={1024} />
              </Form.Item>
              <Form.Item name="binaryCompleteFileMode" label="完整文件模式" valuePropName="checked">
                <Switch />
              </Form.Item>
              <Form.Item name="cronExpression" label="Cron（留空为手动）">
                <Input placeholder="0 0 2 * * ?" />
              </Form.Item>
            </div>
            {syncType === 'INCREMENTAL' && (
              <Alert
                type="warning"
                showIcon
                message="增量模式要求来源与去向选择同一个数据源；比较方式固定为文件长度 + 修改时间。"
              />
            )}
          </Card>
          <div className="flex justify-end gap-3">
            <Button onClick={() => runAction('preview')} loading={loading}>
              预览 HOCON
            </Button>
            <Button type="primary" onClick={() => runAction('save')} loading={loading}>
              保存任务
            </Button>
          </div>
        </Form>
      </div>
      <Modal
        open={!!picker}
        title={`选择远程目录 · ${picker?.path || '根目录'}`}
        footer={null}
        onCancel={() => setPicker(undefined)}
      >
        <List
          loading={browseLoading}
          dataSource={entries}
          locale={{ emptyText: '目录为空' }}
          renderItem={(entry) => (
            <List.Item
              actions={
                entry.type === 'DIRECTORY'
                  ? [
                      <Button type="link" onClick={() => browse({ ...picker!, path: entry.path })}>
                        进入
                      </Button>,
                      <Button
                        type="link"
                        onClick={() => {
                          form.setFieldValue(picker!.field, entry.path);
                          setPicker(undefined);
                        }}
                      >
                        选择
                      </Button>,
                    ]
                  : []
              }
            >
              <List.Item.Meta
                avatar={
                  <FolderOpenOutlined className={entry.type === 'DIRECTORY' ? 'text-amber-500' : 'text-slate-400'} />
                }
                title={entry.name}
                description={entry.path}
              />
            </List.Item>
          )}
        />
      </Modal>
      <Modal
        width={840}
        open={!!preview}
        title="SeaTunnel 2.3.13 HOCON 预览"
        footer={null}
        onCancel={() => setPreview('')}
      >
        <pre className="max-h-[65vh] overflow-auto rounded-xl bg-slate-950 p-4 text-xs text-slate-100">{preview}</pre>
      </Modal>
    </div>
  );
};

export default FileSyncPage;
