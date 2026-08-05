import { Form, Input, InputNumber, Modal, Tag } from "antd";
import React, { useEffect } from "react";

export interface BatchCreateTemplate {
  id: string | number;
  jobName?: string;
  mode?: string;
}

export interface BatchCreateValues {
  copiesPerTemplate: number;
  jobNamePrefix?: string;
}

interface BatchCreateJobModalProps {
  open: boolean;
  loading?: boolean;
  templates: BatchCreateTemplate[];
  onCancel: () => void;
  onSubmit: (values: BatchCreateValues) => void;
}

const BatchCreateJobModal: React.FC<BatchCreateJobModalProps> = ({
  open,
  loading = false,
  templates,
  onCancel,
  onSubmit,
}) => {
  const [form] = Form.useForm<BatchCreateValues>();

  useEffect(() => {
    if (open) {
      form.resetFields();
      form.setFieldsValue({ copiesPerTemplate: 1 });
    }
  }, [form, open]);

  const handleOk = async () => {
    const values = await form.validateFields();
    onSubmit({
      copiesPerTemplate: values.copiesPerTemplate,
      jobNamePrefix: values.jobNamePrefix?.trim() || undefined,
    });
  };

  return (
    <Modal
      open={open}
      title="批量创建作业"
      centered
      okText="创建"
      cancelText="取消"
      confirmLoading={loading}
      onCancel={onCancel}
      onOk={handleOk}
      destroyOnClose
    >
      <div className="mb-4 rounded-lg bg-slate-50 p-3 text-sm leading-6 text-slate-600">
        基于选中的任务配置创建副本。新任务默认为下线状态，创建后可在列表中统一审核、上线和启动。
      </div>

      <div className="mb-4 flex flex-wrap items-center gap-2">
        <span className="text-sm text-slate-500">配置模板：</span>
        {templates.map((template) => (
          <Tag key={String(template.id)} color="blue">
            {template.jobName || template.id}
          </Tag>
        ))}
      </div>

      <Form form={form} layout="vertical">
        <Form.Item
          name="copiesPerTemplate"
          label="每个模板创建数量"
          rules={[{ required: true, message: "请输入创建数量" }]}
        >
          <InputNumber min={1} max={20} className="w-full" />
        </Form.Item>

        <Form.Item
          name="jobNamePrefix"
          label="任务名称前缀（可选）"
          extra="不填写时沿用模板名称，系统会自动追加“副本序号”。"
          rules={[{ max: 128, message: "名称前缀不能超过 128 个字符" }]}
        >
          <Input allowClear placeholder="例如：华东区域同步任务" />
        </Form.Item>
      </Form>
    </Modal>
  );
};

export default BatchCreateJobModal;
