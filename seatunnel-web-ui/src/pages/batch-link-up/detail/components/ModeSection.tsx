import { Form, Radio } from 'antd';
import type { SyncMode } from '../types';
import ModeCard from './ModeCard';

interface Props {
  mode?: any;
  setMode: (value: SyncMode) => void;
}

const ModeSection: React.FC<Props> = ({ mode, setMode }) => {
  return (
    <div className="px-6 py-6">
      <div className="mb-5">
        <div className="text-[18px] font-semibold text-[#101828]">配置模式</div>
        <div className="mt-1 text-[13px] leading-6 text-[#667085]">
          选择配置方式后，进入逻辑关系配置（表/字段映射）与环境参数设置。
        </div>
      </div>

      <Form.Item name="mode" initialValue="GUIDE_SINGLE" className="mb-0">
        <Radio.Group className="w-full">
          <div className="grid grid-cols-1 gap-4 lg:grid-cols-4">
            <ModeCard
              value="FILE_SYNC"
              current={mode}
              title="文件夹同步"
              desc="面向 FTP/SFTP 的二进制文件夹同步，不使用表映射。"
              tag="文件"
              onSelect={setMode}
            />
            <ModeCard
              value="GUIDE_SINGLE"
              current={mode}
              title="单表向导模式"
              desc="适合快速创建单表同步任务，配置路径更清晰。"
              tag="推荐"
              onSelect={setMode}
            />

            <ModeCard
              value="GUIDE_MULTI"
              current={mode}
              title="多表向导模式"
              desc="适合批量配置多张表，统一管理同步关系。"
              tag="批量配置"
              onSelect={setMode}
            />

            <ModeCard
              value="SCRIPT"
              current={mode}
              title="脚本模式"
              desc="适合更复杂的同步场景，灵活度更高。"
              tag="高级模式"
              onSelect={setMode}
            />
          </div>
        </Radio.Group>
      </Form.Item>
    </div>
  );
};

export default ModeSection;
