import { FileOutlined } from '@ant-design/icons';
import { App, Alert, Button, Input, Select, Typography } from 'antd';
import { useState } from 'react';
import { offlineImportTaskApi } from './import/api';
import FileResourcePicker from './FileResourcePicker';
import type { FileResource } from './types';
import './index.less';

interface DatapackCsvImportStepProps {
  onImported: (selection: {
    fileResource: FileResource;
    schema: Record<string, string>;
    nullValue: string;
  }) => void;
  onBack: () => void;
}

const DatapackCsvImportStep: React.FC<DatapackCsvImportStepProps> = ({ onImported, onBack }) => {
  const { message } = App.useApp();
  const [pickerOpen, setPickerOpen] = useState(false);
  const [packageResource, setPackageResource] = useState<FileResource>();
  const [password, setPassword] = useState('');
  const [inspection, setInspection] = useState<{ packageFingerprint: string; fileNames: string[] }>();
  const [selectedFileName, setSelectedFileName] = useState<string>();
  const [inspecting, setInspecting] = useState(false);
  const [extracting, setExtracting] = useState(false);

  const inspectPackage = async () => {
    if (!packageResource?.id || !password) return;
    setInspecting(true);
    setInspection(undefined);
    setSelectedFileName(undefined);
    try {
      const response = await offlineImportTaskApi.inspectDatapackCsv(packageResource.id, password);
      if (response?.code !== 0 || !response?.data) {
        throw new Error(response?.message || response?.msg || '数据包校验失败');
      }
      const fileNames = Array.isArray(response.data.fileNames)
        ? response.data.fileNames.filter((name) => String(name).toLowerCase().endsWith('.csv'))
        : [];
      if (!fileNames.length) throw new Error('数据包清单中没有 CSV 文件');
      setInspection({ packageFingerprint: response.data.packageFingerprint, fileNames });
      console.info('[数据包 CSV] 清单校验完成', { csvCount: fileNames.length });
    } catch (error: any) {
      message.error(error?.message || error?.msg || '数据包校验失败');
    } finally {
      setInspecting(false);
    }
  };

  const extractSelectedCsv = async () => {
    if (!packageResource?.id || !password || !inspection || !selectedFileName) return;
    setExtracting(true);
    try {
      const response = await offlineImportTaskApi.selectDatapackCsv({
        fileResourceId: packageResource.id,
        password,
        packageFingerprint: inspection.packageFingerprint,
        fileName: selectedFileName,
      });
      if (response?.code !== 0 || !response?.data?.fileResource) {
        throw new Error(response?.message || response?.msg || '提取 CSV 失败');
      }
      const rawResource = response.data.fileResource as any;
      const fileResource: FileResource = {
        ...rawResource,
        id: String(rawResource.id),
        name: String(rawResource.name || selectedFileName.split('/').at(-1) || selectedFileName),
        path: rawResource.path || rawResource.logicalPath || rawResource.objectKey,
        objectKey: rawResource.objectKey || rawResource.path || rawResource.logicalPath,
        kind: 'FILE',
      };
      setPassword('');
      onImported({
        fileResource,
        schema: response.data.schema || {},
        nullValue: response.data.nullValue || '',
      });
    } catch (error: any) {
      message.error(error?.message || error?.msg || '提取 CSV 失败');
    } finally {
      setExtracting(false);
    }
  };

  return (
    <main className="mx-auto w-full max-w-[1080px] px-6 py-8">
      <div className="mb-6 flex items-start justify-between gap-4">
        <div>
          <Typography.Title level={3} className="!mb-2">离线数据包导入</Typography.Title>
          <Typography.Text type="secondary">校验数据包后选择一个 CSV 文件，继续配置常规文件导入任务。</Typography.Text>
        </div>
        <Button onClick={onBack}>返回任务列表</Button>
      </div>

      <section className="space-y-5 rounded-2xl border border-slate-200 bg-white p-6 shadow-sm">
        <div>
          <div className="mb-2 text-sm font-semibold text-slate-900">加密数据包</div>
          <div className="flex flex-wrap items-center gap-3 rounded-xl border border-dashed border-slate-200 bg-slate-50 p-3">
            <FileOutlined className={packageResource ? 'text-emerald-500' : 'text-slate-400'} />
            <div className="min-w-0 flex-1">
              <div className="truncate text-sm font-medium text-slate-800">
                {packageResource?.name || '尚未选择 .datapack 文件'}
              </div>
              {packageResource?.path ? <div className="truncate text-xs text-slate-500">{packageResource.path}</div> : null}
            </div>
            <Button type="primary" ghost onClick={() => setPickerOpen(true)}>
              {packageResource ? '更换数据包' : '选择数据包'}
            </Button>
          </div>
        </div>

        <div>
          <label className="mb-2 block text-sm font-semibold text-slate-900" htmlFor="datapack-password">
            数据包密码
          </label>
          <Input.Password
            id="datapack-password"
            value={password}
            autoComplete="new-password"
            placeholder="请输入数据包密码"
            onChange={(event) => {
              setPassword(event.target.value);
              setInspection(undefined);
              setSelectedFileName(undefined);
            }}
            onPressEnter={() => void inspectPackage()}
          />
        </div>

        <Button
          type="primary"
          loading={inspecting}
          disabled={!packageResource?.id || !password}
          onClick={() => void inspectPackage()}
        >
          校验并读取 CSV 清单
        </Button>

        {inspection ? (
          <div className="space-y-3 rounded-xl border border-blue-100 bg-blue-50/50 p-4">
            <div className="text-sm font-semibold text-slate-900">选择 CSV 文件</div>
            <Select
              className="w-full"
              placeholder="请选择一个 CSV 文件"
              value={selectedFileName}
              options={inspection.fileNames.map((fileName) => ({ label: fileName, value: fileName }))}
              onChange={setSelectedFileName}
            />
            <div className="flex justify-end">
              <Button
                type="primary"
                loading={extracting}
                disabled={!selectedFileName}
                onClick={() => void extractSelectedCsv()}
              >
                提取 CSV 并进入任务配置
              </Button>
            </div>
          </div>
        ) : (
          <Alert
            type="info"
            showIcon
            message="CSV 内容仅在你选择具体文件后提取"
            description="校验阶段只读取数据包清单和文件结构，不会向文件资源库写入 CSV。"
          />
        )}
      </section>

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
          setSelectedFileName(undefined);
          setPickerOpen(false);
        }}
      />
    </main>
  );
};

export default DatapackCsvImportStep;
