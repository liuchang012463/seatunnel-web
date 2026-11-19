import { Button, Select } from 'antd';
import { useState } from 'react';
import DatapackPackageInput, { useDatapackPackage } from './DatapackPackageInput';
import type { DatapackSelection } from './config/datapack';
import './index.less';

interface DatapackCsvImportStepProps {
  preferredFileNames?: string[];
  confirmText?: string;
  onImported: (selection: DatapackSelection) => void;
  onBack: () => void;
}

const DatapackCsvImportStep: React.FC<DatapackCsvImportStepProps> = ({
  preferredFileNames = [],
  confirmText = '提取并继续',
  onImported,
  onBack,
}) => {
  const config = useDatapackPackage();
  const { inspection, action } = config;
  const [fileName, setFileName] = useState<string>();

  const inspect = async () => {
    setFileName(undefined);
    const manifest = await config.inspect();
    setFileName(preferredFileNames.find((name) => manifest?.fileNames.includes(name)));
  };

  const extract = async () => {
    if (!fileName) return;
    const selection = await config.extract([fileName], false);
    if (selection) {
      config.setPassword('');
      onImported(selection);
    }
  };

  return (
    <section className="space-y-4 rounded-2xl border border-slate-200 bg-white p-5">
      <DatapackPackageInput
        config={config}
        onInspect={() => void inspect()}
        onSelect={(resource) => {
          config.setPackage(resource);
          config.setPassword('');
          config.setInspection(undefined);
          setFileName(undefined);
        }}
        onPasswordChange={(value) => {
          config.setPassword(value);
          config.setInspection(undefined);
          setFileName(undefined);
        }}
      />
      {inspection && (
        <label className="block">
          <span className="mb-2 block text-sm">选择 CSV 文件</span>
          <Select
            aria-label="选择 CSV 文件"
            className="w-full"
            placeholder="请选择一个 CSV 文件"
            value={fileName}
            options={inspection.fileNames.map((name) => ({ label: name, value: name }))}
            disabled={!!action}
            showSearch
            onChange={setFileName}
          />
        </label>
      )}
      <div className="flex justify-end gap-2">
        <Button onClick={onBack}>取消</Button>
        <Button
          type="primary"
          loading={action === 'extract'}
          disabled={!inspection || !fileName || action === 'inspect'}
          onClick={() => void extract()}
        >
          {confirmText}
        </Button>
      </div>
    </section>
  );
};

export default DatapackCsvImportStep;
