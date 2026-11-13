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
        <div className="mt-1 text-[14px] leading-6 text-[color:var(--st-color-text-secondary)]">
          选择配置方式后，进入逻辑关系配置（表/字段映射）与环境参数设置。
        </div>
      </div>

      <Form.Item name="mode" initialValue="GUIDE_SINGLE" className="mb-0">
        <Radio.Group className="w-full">
          <div className="grid grid-cols-1 gap-4 lg:grid-cols-3">
            <ModeCard
              value="GUIDE_SINGLE"
              current={mode}
              title="单表引接"
              desc="一次配置一张表的全量同步，按页面步骤选表与字段即可，适合日常单表引接。"
              tag="推荐"
              onSelect={setMode}
            />

            <ModeCard
              value="GUIDE_SINGLE_INCREMENTAL"
              current={mode}
              title="单表增量引接"
              desc="按调度周期只同步新增或变更数据，适合持续更新的业务表。"
              tag="增量"
              onSelect={setMode}
            />

            <ModeCard
              value="GUIDE_MULTI"
              current={mode}
              title="多表批量引接"
              desc="一次勾选多张表批量配置映射，适合表多、结构相近的库表迁移。"
              tag="批量"
              onSelect={setMode}
            />

            <ModeCard
              value="SCRIPT"
              current={mode}
              title="自定义引接"
              desc="直接编写或调整引接脚本，需熟悉配置语法，适合复杂转换与高级定制。"
              tag="高级"
              onSelect={setMode}
            />

          </div>
        </Radio.Group>
      </Form.Item>
    </div>
  );
};

export default ModeSection;
