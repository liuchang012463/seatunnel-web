import { FileOutlined } from '@ant-design/icons';
import { App, Button, Input, Select } from 'antd';
import { useEffect, useRef, useState } from 'react';
import TableTransferPanel from '@/pages/batch-link-up/config/multi/components/TableTransferPanel';
import type { DatapackSelection } from './config/datapack';
import { offlineImportTaskApi, type OfflineDatapackCsvInspect, type OfflineDatapackCsvSelection } from './import/api';
import FileResourcePicker from './FileResourcePicker';
import type { FileResource } from './types';
import './index.less';

interface DatapackCsvImportStepProps {
  multiple?: boolean;
  initialPackage?: FileResource;
  preferredFileNames?: string[];
  confirmText?: string;
  onImported: (selection: DatapackSelection) => void;
  onBack: () => void;
}

const DatapackCsvImportStep: React.FC<DatapackCsvImportStepProps> = ({
  multiple = false,
  initialPackage,
  preferredFileNames = [],
  confirmText = '提取并继续',
  onImported,
  onBack,
}) => {
  const { message } = App.useApp();
  const [pickerOpen, setPickerOpen] = useState(false);
  const [packageResource, setPackageResource] = useState(initialPackage);
  const [password, setPassword] = useState('');
  const [inspection, setInspection] = useState<OfflineDatapackCsvInspect>();
  const [selected, setSelected] = useState<string[]>([]);
  const [action, setAction] = useState<'inspect' | 'extract'>();
  const activeRequest = useRef<AbortController>();
  useEffect(() => () => activeRequest.current?.abort(), []);

  const inspectPackage = async () => {
    if (!packageResource?.id || !password || activeRequest.current) return;
    const request = new AbortController();
    activeRequest.current = request;
    setAction('inspect');
    setInspection(undefined);
    setSelected([]);
    try {
      const response = await offlineImportTaskApi.inspectDatapackCsv(
        packageResource.id, password, { signal: request.signal },
      );
      if (request.signal.aborted) return;
      if (response?.code !== 0 || !response?.data) {
        throw new Error(response?.message || response?.msg || '数据包校验失败');
      }
      const { packageFingerprint, fileNames } = response.data;
      if (!fileNames?.length) throw new Error('数据包清单中没有 CSV 文件');
      setInspection({ packageFingerprint, fileNames });
      const preferred = preferredFileNames.filter((name) => fileNames.includes(name));
      setSelected(multiple ? preferred : preferred.slice(0, 1));
      console.info('[数据包导入] 已校验 CSV 清单', { fileCount: fileNames.length });
    } catch (error: any) {
      if (!request.signal.aborted) message.error(error?.message || '数据包校验失败');
    } finally {
      activeRequest.current = undefined;
      if (!request.signal.aborted) setAction(undefined);
    }
  };

  const extractSelectedCsv = async () => {
    if (!packageResource?.id || !password || !inspection || !selected.length || activeRequest.current) return;
    const request = new AbortController();
    activeRequest.current = request;
    setAction('extract');
    try {
      const payload = {
        fileResourceId: packageResource.id,
        password,
        packageFingerprint: inspection.packageFingerprint,
      };
      const options = { signal: request.signal };
      const response = multiple
        ? await offlineImportTaskApi.selectDatapackCsvBatch({ ...payload, fileNames: selected }, options)
        : await offlineImportTaskApi.selectDatapackCsv({ ...payload, fileName: selected[0] }, options);
      if (request.signal.aborted) return;
      const data = Array.isArray(response?.data) ? response.data : [response?.data];
      if (response?.code !== 0 || data.length !== selected.length || !data.every((item) => item?.fileResource?.id)) {
        throw new Error(response?.message || response?.msg || '提取 CSV 失败');
      }
      const files = (data as OfflineDatapackCsvSelection[]).map((item, index) => {
        const fileName = item.fileName || selected[index];
        const resource = item.fileResource;
        return {
          ...item,
          fileName,
          fileResource: {
            ...resource,
            id: String(resource.id),
            name: resource.name || fileName.split('/').at(-1) || fileName,
            kind: 'FILE' as const,
          },
        };
      });
      setPassword('');
      onImported({
        packageResourceId: String(packageResource.id),
        packageName: packageResource.name,
        packageFingerprint: inspection.packageFingerprint,
        files,
      });
    } catch (error: any) {
      if (!request.signal.aborted) message.error(error?.message || '提取 CSV 失败');
    } finally {
      activeRequest.current = undefined;
      if (!request.signal.aborted) setAction(undefined);
    }
  };

  return (
    <section className="space-y-4 rounded-2xl border border-slate-200 bg-white p-5">
      <div className="flex items-center gap-3 rounded-xl border border-dashed border-slate-200 p-3">
        <FileOutlined />
        <div className="min-w-0 flex-1 truncate text-sm">
          {packageResource?.name || '尚未选择 .datapack 文件'}
        </div>
        <Button disabled={!!action} onClick={() => setPickerOpen(true)}>
          {packageResource ? '选择其他数据包' : '选择数据包'}
        </Button>
      </div>
      <div>
        <label className="mb-2 block text-sm" htmlFor="datapack-password">数据包密码</label>
        <Input.Password
          id="datapack-password"
          value={password}
          autoComplete="new-password"
          placeholder="请输入数据包密码"
          disabled={!!action}
          onChange={(event) => {
            setPassword(event.target.value);
            setInspection(undefined);
            setSelected([]);
          }}
          onPressEnter={() => void inspectPackage()}
        />
      </div>
      <Button
        loading={action === 'inspect'}
        disabled={!packageResource?.id || !password || action === 'extract'}
        onClick={() => void inspectPackage()}
      >
        校验并读取 CSV 清单
      </Button>
      {inspection && (multiple ? (
        <TableTransferPanel
          files
          matchMode="1"
          loading={action === 'extract'}
          data={inspection.fileNames.map((fileName) => ({ key: fileName, rawTitle: fileName, title: '' }))}
          targetKeys={selected}
          onChange={setSelected}
        />
      ) : (
        <label className="block">
          <span className="mb-2 block text-sm">选择 CSV 文件</span>
          <Select
            aria-label="选择 CSV 文件"
            className="w-full"
            placeholder="请选择一个 CSV 文件"
            value={selected[0]}
            options={inspection.fileNames.map((fileName) => ({ label: fileName, value: fileName }))}
            disabled={!!action}
            showSearch
            onChange={(fileName) => setSelected([fileName])}
          />
        </label>
      ))}
      <div className="flex justify-end gap-2">
        <Button onClick={onBack}>取消</Button>
        <Button
          type="primary"
          loading={action === 'extract'}
          disabled={!inspection || !selected.length || action === 'inspect'}
          onClick={() => void extractSelectedCsv()}
        >
          {confirmText}
        </Button>
      </div>
      <FileResourcePicker
        open={pickerOpen}
        allowedFormats={['datapack']}
        selectionMode="file"
        title="选择加密数据包"
        onCancel={() => setPickerOpen(false)}
        onSelect={(resource) => {
          setPackageResource(resource);
          setPassword('');
          setInspection(undefined);
          setSelected([]);
          setPickerOpen(false);
        }}
      />
    </section>
  );
};

export default DatapackCsvImportStep;
