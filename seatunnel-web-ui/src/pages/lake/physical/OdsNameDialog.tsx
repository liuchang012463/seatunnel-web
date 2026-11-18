import { Alert, Form, Input, Modal } from 'antd';
import React, { useRef, useState } from 'react';

const OdsNameDialog: React.FC<{
  defaultName?: string;
  onCancel: () => void;
  onSubmit: (name: string) => Promise<void>;
}> = ({ defaultName, onCancel, onSubmit }) => {
  const [form] = Form.useForm<{ name: string }>();
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState<string>();
  const inFlight = useRef(false);

  const submit = async ({ name }: { name: string }) => {
    if (inFlight.current) return;
    inFlight.current = true;
    setSubmitting(true);
    setError(undefined);
    try {
      await onSubmit(name.trim());
    } catch (reason) {
      setError(reason instanceof Error ? reason.message : '入湖库准备失败');
    } finally {
      inFlight.current = false;
      setSubmitting(false);
    }
  };

  return (
    <Modal
      open
      title="设置 ODS 库名称"
      okText="开始入湖"
      cancelText="取消"
      confirmLoading={submitting}
      cancelButtonProps={{ disabled: submitting }}
      closable={!submitting}
      maskClosable={!submitting}
      keyboard={!submitting}
      onCancel={onCancel}
      onOk={() => form.submit()}
    >
      <Form name="lake-ods-name" form={form} layout="vertical" initialValues={{ name: defaultName }} onFinish={submit} disabled={submitting}>
        <Form.Item
          name="name"
          label="ODS 库名称"
          extra="系统会用此名称自动生成实际库名。"
          rules={[
            { required: true, whitespace: true, message: '请输入 ODS 库名称' },
            {
              transform: (value) => value?.trim(),
              max: 64,
              message: 'ODS 库名称不能超过 64 个字符',
            },
          ]}
        >
          <Input autoFocus placeholder="输入 ODS 库名称" />
        </Form.Item>
      </Form>
      {error ? <Alert type="error" showIcon message={error} /> : null}
    </Modal>
  );
};

export default OdsNameDialog;
