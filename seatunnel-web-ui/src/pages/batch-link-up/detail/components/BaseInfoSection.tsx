import { Form, Input, Radio } from 'antd';
import DataSourceSelect, {
  generateOfflineSourceDataSourceOptions,
  generateSourceDataSourceOptions,
  generateTaskTargetDataSourceOptions,
} from '../../DataSourceSelect';
import IconRightArrow from '../../IconRightArrow';
import type { SyncMode } from '../types';
import ModeCard from './ModeCard';
import { canUseOfflineMode, isWebUploadSource } from '../modeUtils';

const { TextArea } = Input;

interface Props {
  sourceType: any;
  targetType: any;
  handleSourceChange: (value: string, option: any) => void;
  handleTargetChange: (value: string, option: any) => void;
  mode?: SyncMode;
  setMode: (value: SyncMode) => void;
}

const sectionTitleClass =
  'mb-3 text-[14px] font-medium text-[color:var(--st-color-text-primary)]';

const BaseConfigSection: React.FC<Props> = ({
  sourceType,
  targetType,
  handleSourceChange,
  handleTargetChange,
  mode,
  setMode,
}) => {
  const localFileSource = isWebUploadSource(sourceType);
  const sourceOptions = localFileSource
    ? generateOfflineSourceDataSourceOptions()
    : generateSourceDataSourceOptions();

  return (
    <div className="p-6">
      <div className="space-y-5 rounded-[4px] border border-[color:var(--st-color-border)] bg-[color:var(--st-color-bg-panel)] p-5 shadow-none">
        <section className="rounded-[4px] border border-[color:var(--st-color-border)] bg-[color:var(--st-color-bg-control)] p-4">
          <div className={sectionTitleClass}>数据同步方式</div>
          <div className="flex items-center gap-3">
            <DataSourceSelect
              value={sourceType}
              onChange={handleSourceChange}
              dataSourceOptions={sourceOptions}
              placeholder="请选择来源"
              prefix="来源"
              width="48%"
            />
            <div className="shrink-0 text-[color:var(--st-color-text-muted)]">
              <IconRightArrow />
            </div>
            <DataSourceSelect
              value={targetType}
              onChange={handleTargetChange}
              dataSourceOptions={generateTaskTargetDataSourceOptions()}
              placeholder="请选择去向"
              prefix="去向"
              width="48%"
            />
          </div>
        </section>

        <section className="rounded-[4px] border border-[color:var(--st-color-border)] bg-[color:var(--st-color-bg-control)] p-4">
          <div className={sectionTitleClass}>任务信息</div>
          <div className="grid grid-cols-1 gap-4">
            <Form.Item
              label="任务名称"
              name="jobName"
              rules={[{ required: true, message: '请输入任务名称' }]}
              className="mb-0"
              extra="默认按来源 → 去向自动填充，可自行修改"
            >
              <Input
                placeholder="例如：达梦 → 人大金仓"
                className="!h-[36px] !rounded-[4px]"
              />
            </Form.Item>
            <Form.Item label="任务描述" name="jobDesc" className="mb-0">
              <TextArea
                placeholder="描述同步范围、用途、注意事项等"
                rows={3}
                className="!rounded-[4px]"
              />
            </Form.Item>
          </div>
        </section>

        <section className="rounded-[4px] border border-[color:var(--st-color-border)] bg-[color:var(--st-color-bg-control)] p-4">
          <div className={sectionTitleClass}>配置模式</div>
          <Form.Item name="mode" initialValue="GUIDE_SINGLE" className="mb-0">
            <Radio.Group className="w-full">
              <div className="grid grid-cols-1 gap-3 sm:grid-cols-2">
                <ModeCard
                  value="GUIDE_SINGLE"
                  current={mode}
                  title="单表引接"
                  desc="一次配置一张表的全量同步，按页面步骤选表与字段即可，适合日常单表引接。"
                  tag="推荐"
                  onSelect={setMode}
                />
                {!localFileSource && canUseOfflineMode(sourceType, 'GUIDE_SINGLE_INCREMENTAL') && (
                  <ModeCard
                    value="GUIDE_SINGLE_INCREMENTAL"
                    current={mode}
                    title="单表增量引接"
                    desc="按调度周期只同步新增或变更数据，适合持续更新的业务表。"
                    tag="增量"
                    onSelect={setMode}
                  />
                )}
                {!localFileSource && canUseOfflineMode(sourceType, 'GUIDE_MULTI') && (
                  <ModeCard
                    value="GUIDE_MULTI"
                    current={mode}
                    title="多表批量引接"
                    desc="一次勾选多张表批量配置映射，适合表多、结构相近的库表迁移。"
                    tag="批量"
                    onSelect={setMode}
                  />
                )}
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
        </section>
      </div>
    </div>
  );
};

export default BaseConfigSection;
