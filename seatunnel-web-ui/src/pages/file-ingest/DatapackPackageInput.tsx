import { FileOutlined } from '@ant-design/icons';
import { App, Button, Input } from 'antd';
import { useEffect, useId, useRef, useState } from 'react';
import type { DatapackSelection } from './config/datapack';
import { offlineImportTaskApi, type OfflineDatapackCsvInspect } from './import/api';
import FileResourcePicker from './FileResourcePicker';
import type { FileResource } from './types';

export function useDatapackPackage(initial?: { resource: FileResource; inspection: OfflineDatapackCsvInspect }) {
  const { message } = App.useApp();
  const [resource, setResource] = useState(initial?.resource);
  const [inspection, setInspection] = useState(initial?.inspection);
  const [password, setPassword] = useState('');
  const [action, setAction] = useState<'inspect' | 'extract'>();
  const activeRequest = useRef<AbortController>();
  useEffect(() => () => activeRequest.current?.abort(), []);

  const setPackage = (next: FileResource) => {
    if (String(next.id) === String(resource?.id)) return;
    setResource(next);
    setPassword('');
    setInspection(undefined);
  };

  const inspect = async (): Promise<OfflineDatapackCsvInspect | undefined> => {
    if (!resource?.id || !password || activeRequest.current) return undefined;
    const request = new AbortController();
    activeRequest.current = request;
    setAction('inspect');
    try {
      const response = await offlineImportTaskApi.inspectDatapackCsv(resource.id, password, { signal: request.signal });
      if (request.signal.aborted) return undefined;
      if (response?.code !== 0 || !response?.data) {
        throw new Error(response?.message || response?.msg || '数据包校验失败');
      }
      if (!response.data.fileNames?.length) throw new Error('数据包清单中没有 CSV 文件');
      setInspection(response.data);
      console.info('[数据包导入] 已校验 CSV 清单', { fileCount: response.data.fileNames.length });
      return response.data;
    } catch (error: any) {
      if (!request.signal.aborted) message.error(error?.message || '数据包校验失败');
      return undefined;
    } finally {
      activeRequest.current = undefined;
      if (!request.signal.aborted) setAction(undefined);
    }
  };

  const extract = async (fileNames: string[], multiple: boolean): Promise<DatapackSelection | undefined> => {
    if (!resource?.id || !inspection || !fileNames.length || activeRequest.current) return undefined;
    if (!password) {
      message.warning('请输入数据包密码以提取新选中的 CSV');
      return undefined;
    }
    const request = new AbortController();
    activeRequest.current = request;
    setAction('extract');
    try {
      const payload = {
        fileResourceId: resource.id,
        password,
        packageFingerprint: inspection.packageFingerprint,
      };
      const options = { signal: request.signal };
      const response = multiple
        ? await offlineImportTaskApi.selectDatapackCsvBatch({ ...payload, fileNames }, options)
        : await offlineImportTaskApi.selectDatapackCsv({ ...payload, fileName: fileNames[0] }, options);
      if (request.signal.aborted) return undefined;
      const data = Array.isArray(response?.data) ? response.data : response?.data ? [response.data] : [];
      if (response?.code !== 0 || data.length !== fileNames.length || !data.every(
        (item, index) => item.fileResource?.id && (!item.fileName || item.fileName === fileNames[index]),
      )) {
        throw new Error(response?.message || response?.msg || '提取 CSV 失败');
      }
      return {
        packageResourceId: String(resource.id),
        packageName: resource.name,
        packageFingerprint: inspection.packageFingerprint,
        files: data.map((item, index) => {
          const fileName = item.fileName || fileNames[index];
          const file = item.fileResource;
          return {
            ...item,
            fileName,
            fileResource: {
              ...file,
              id: String(file.id),
              name: file.name || fileName.split('/').at(-1) || fileName,
              kind: 'FILE',
            },
          };
        }),
      };
    } catch (error: any) {
      if (!request.signal.aborted) message.error(error?.message || '提取 CSV 失败');
      return undefined;
    } finally {
      activeRequest.current = undefined;
      if (!request.signal.aborted) setAction(undefined);
    }
  };

  return { resource, setPackage, password, setPassword, inspection, setInspection, action, inspect, extract };
}

interface Props {
  config: ReturnType<typeof useDatapackPackage>;
  onSelect: (resource: FileResource) => void;
  onInspect: () => void;
  onPasswordChange?: (password: string) => void;
  disabled?: boolean;
}

export default function DatapackPackageInput({
  config, onSelect, onInspect, onPasswordChange = config.setPassword, disabled,
}: Props) {
  const passwordId = useId();
  const [pickerOpen, setPickerOpen] = useState(false);
  const { resource, action, password } = config;
  const locked = disabled || !!action;

  return (
    <div className="space-y-3">
      <div>
        <div className="mb-2 text-sm">来源数据包</div>
        <div className="flex min-h-8 items-center gap-2">
          <Button icon={<FileOutlined aria-hidden />} onClick={() => setPickerOpen(true)} disabled={locked}>
            {resource ? '更换数据包' : '选择数据包'}
          </Button>
          <span className="min-w-0 truncate text-sm" title={resource?.name}>
            {resource?.name || '请选择 .datapack 文件'}
          </span>
        </div>
      </div>
      <div>
        <label className="mb-2 block text-sm" htmlFor={passwordId}>数据包密码</label>
        <div className="flex min-w-0 gap-2">
          <Input.Password
            id={passwordId}
            value={password}
            autoComplete="new-password"
            placeholder="请输入数据包密码"
            disabled={locked}
            onChange={(event) => onPasswordChange(event.target.value)}
            onPressEnter={() => { if (!locked && resource && password) onInspect(); }}
          />
          <Button
            loading={action === 'inspect'}
            disabled={disabled || !resource?.id || !password || action === 'extract'}
            onClick={onInspect}
          >
            校验并读取 CSV 清单
          </Button>
        </div>
      </div>
      <FileResourcePicker
        open={pickerOpen}
        allowedFormats={['datapack']}
        selectionMode="file"
        title="选择加密数据包"
        onCancel={() => setPickerOpen(false)}
        onSelect={(next) => {
          onSelect(next);
          setPickerOpen(false);
        }}
      />
    </div>
  );
}
