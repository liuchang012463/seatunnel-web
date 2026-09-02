import { FolderOpenOutlined } from '@ant-design/icons';
import { Button, Input, Select, message } from 'antd';
import { useMemo, useState } from 'react';
import PanelShell from '../../workflow/panel/components/PanelShell';
import { fileDataSourceLabel } from './support';
import DirectoryPickerModal from './DirectoryPickerModal';
import '@/pages/batch-link-up/workflow/panel/components/PanelShell/index.less';
import '@/pages/batch-link-up/workflow/panel/components/SinkPanel/index.less';

interface FileSyncSinkPanelProps {
  selectedNode: any;
  onClose: () => void;
  onNodeDataChange: (nodeId: string, newData: any) => void;
  datasourceOptions: Array<{ label: string; value: string; dbType: string; connectorType?: string }>;
}

const FileSyncSinkPanel: React.FC<FileSyncSinkPanelProps> = ({
  selectedNode,
  onClose,
  onNodeDataChange,
  datasourceOptions,
}) => {
  const config = selectedNode?.data?.config || {};
  const [pickerOpen, setPickerOpen] = useState(false);

  const filteredOptions = useMemo(
    () => datasourceOptions.filter((item) => item.dbType !== 'LOCAL_FILE'),
    [datasourceOptions],
  );

  const updateConfig = (patch: Record<string, any>) => {
    onNodeDataChange(selectedNode.id, {
      ...selectedNode.data,
      config: { ...config, ...patch },
    });
  };

  const handleDataSourceChange = (value: string) => {
    const meta = datasourceOptions.find((item) => String(item.value) === String(value));
    updateConfig({
      dataSourceId: value,
      dbType: meta?.dbType,
      pluginName: meta?.connectorType,
      connectorType: meta?.connectorType,
      targetPath: undefined,
    });
  };

  return (
    <PanelShell
      eyebrow="Sink Config"
      title="去向配置（文件）"
      badge="输出节点"
      desc="文件将被写入所选数据源的目标目录"
      heroTitle={fileDataSourceLabel(config.dbType) || '去向'}
      heroDesc={config.targetPath || '未选择目标目录'}
      heroTag="SINK"
      dbType={config.dbType}
      onClose={onClose}
      footer={
        <button
          type="button"
          className="workflow-panel__btn workflow-panel__btn--ghost"
          onClick={onClose}
        >
          关闭
        </button>
      }
    >
      <section className="workflow-panel__section">
        <div className="workflow-panel__group">
          <div className="workflow-panel__group-head">
            <div className="workflow-panel__group-kicker">远程数据源</div>
          </div>
          <div className="workflow-panel__field workflow-panel__field--full">
            <Select
              value={config.dataSourceId || undefined}
              onChange={handleDataSourceChange}
              options={filteredOptions.map((item) => ({
                label: `${item.label} · ${fileDataSourceLabel(item.dbType)}`,
                value: item.value,
              }))}
              placeholder="请选择 FTP/SFTP/S3/MinIO 数据源"
              showSearch
              optionFilterProp="label"
              className="workflow-panel__antd-select"
              style={{ width: '100%' }}
              popupClassName="workflow-panel__dropdown"
            />
          </div>
          <div className="workflow-panel__field workflow-panel__field--full mt-3">
            <div className="mb-1 text-xs text-slate-500">目标目录</div>
            <Input
              value={config.targetPath}
              placeholder="/archive/files 或 /bucket-prefix"
              onChange={(event) => updateConfig({ targetPath: event.target.value })}
              addonAfter={
                <Button
                  type="text"
                  icon={<FolderOpenOutlined />}
                  onClick={() => {
                    if (!config.dataSourceId) {
                      message.warning('请先选择数据源');
                      return;
                    }
                    setPickerOpen(true);
                  }}
                >
                  浏览
                </Button>
              }
            />
          </div>
          <div className="mt-3 rounded-xl bg-slate-50 px-3 py-2 text-xs leading-5 text-slate-500">
            复制策略：仅复制新增或内容变化的文件，不删除目标端已有文件；数据格式固定为 binary。
          </div>
        </div>
      </section>

      <DirectoryPickerModal
        open={pickerOpen}
        datasourceId={config.dataSourceId}
        title="选择目标目录"
        onCancel={() => setPickerOpen(false)}
        onSelect={(path) => updateConfig({ targetPath: path })}
      />
    </PanelShell>
  );
};

export default FileSyncSinkPanel;
