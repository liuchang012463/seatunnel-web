import { Alert, Form, Input, Modal } from 'antd';
import React, { useRef, useState } from 'react';

const OdsNameDialog: React.FC<{
  onCancel: () => void;
  onSubmit: (name: string) => Promise<void>;
}> = ({ onCancel, onSubmit }) => {
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
      title="设置 ODS 名称"
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
      <Form name="lake-ods-name" form={form} layout="vertical" onFinish={submit} disabled={submitting}>
        <Form.Item
          name="name"
          label="ODS 名称"
          extra="系统会用此名称自动生成实际库名。"
          rules={[
            { required: true, whitespace: true, message: '请输入 ODS 名称' },
            {
              transform: (value) => value?.trim(),
              pattern: /^[a-z_][a-z0-9_]*$/i,
              message: '请以英文字母或下划线开头，可包含数字',
            },
          ]}
        >
          <Input autoFocus placeholder="例如 orders" />
        </Form.Item>
      </Form>
      {error ? <Alert type="error" showIcon message={error} /> : null}
    </Modal>
  );
};

export default OdsNameDialog;
