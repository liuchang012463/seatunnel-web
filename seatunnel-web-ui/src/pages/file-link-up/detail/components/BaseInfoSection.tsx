import { Form, Input, Radio } from 'antd';
import DataSourceSelect from '@/pages/batch-link-up/DataSourceSelect';
import IconRightArrow from '@/pages/batch-link-up/IconRightArrow';
import ModeCard from '@/pages/batch-link-up/detail/components/ModeCard';
import type { SyncMode } from '@/pages/batch-link-up/detail/types';
import { generateFileTypeSourceOptions, generateFileTypeTargetOptions } from '../options';

const { TextArea } = Input;

interface Props {
  sourceType: any;
  targetType: any;
  handleSourceChange: (value: string, option: any) => void;
  handleTargetChange: (value: string, option: any) => void;
  mode?: SyncMode;
  setMode?: (value: SyncMode) => void;
}

const FileTypeBaseInfoSection: React.FC<Props> = ({
  sourceType,
  targetType,
  handleSourceChange,
  handleTargetChange,
  mode = 'FILE_SYNC',
  setMode,
}) => {
  return (
    <div className="p-6">
      <div className="rounded-[24px] bg-white shadow-sm space-y-6">
        {/* ① 数据同步方式 */}
        <div className="rounded-2xl border border-[#E4E7EC] bg-[#FAFBFC] p-5">
          <div className="mb-3 text-[14px] font-medium text-[#344054]">数据同步方式</div>

          <div className="flex items-center gap-3">
            <DataSourceSelect
              value={sourceType}
              onChange={handleSourceChange}
              dataSourceOptions={generateFileTypeSourceOptions()}
              placeholder="本地文件或远程文件"
              prefix="来源"
              width="48%"
            />

            <div className="text-[#98A2B3]">
              <IconRightArrow />
            </div>

            <DataSourceSelect
              value={targetType}
              onChange={handleTargetChange}
              dataSourceOptions={generateFileTypeTargetOptions()}
              placeholder="请选择去向"
              prefix="去向"
              width="48%"
            />
          </div>

          <div className="mt-3 text-[12px] leading-5 text-[#667085]">
            来源支持选择本地文件/文件夹与 FTP/SFTP/S3/MinIO 远程文件，按目录或 Prefix
            传输二进制流，不涉及表、字段或 SQL 映射。
          </div>
        </div>

        {/* ② 任务信息 */}
        <div className="space-y-4">
          <div className="text-[14px] font-medium text-[#344054]">任务信息</div>

          <div className="grid grid-cols-1 gap-4">
            <Form.Item
              label="任务名称"
              name="jobName"
              rules={[{ required: true, message: '请输入任务名称' }]}
              className="mb-0"
            >
              <Input placeholder="例如：本地上传 → S3 影像归档" className="!h-[36px] !rounded-[12px]" />
            </Form.Item>

            <Form.Item label="任务描述" name="jobDesc" className="mb-0">
              <TextArea placeholder="描述同步范围、用途、注意事项等" rows={3} className="!rounded-xl" />
            </Form.Item>
          </div>
        </div>

        {/* ③ 配置模式：文件引接固定为文件向导（二进制流） */}
        <div>
          <div className="mb-3 text-[14px] font-medium text-[#344054]">配置模式</div>

          <Form.Item name="mode" initialValue="FILE_SYNC" className="mb-0">
            <Radio.Group className="w-full">
              <div className="grid grid-cols-1 gap-4 lg:grid-cols-3">
                <ModeCard
                  value="FILE_SYNC"
                  current={mode}
                  title="文件向导"
                  desc="按目录或 Prefix 同步二进制文件，来源支持本地上传与远程文件。"
                  tag="二进制流"
                  onSelect={(value) => setMode?.(value)}
                />
              </div>
            </Radio.Group>
          </Form.Item>
        </div>
      </div>
    </div>
  );
};

export default FileTypeBaseInfoSection;
