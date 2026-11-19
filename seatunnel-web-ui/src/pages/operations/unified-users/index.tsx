import { LockOutlined, ReloadOutlined, SaveOutlined, SafetyCertificateOutlined } from '@ant-design/icons';
import { PageContainer } from '@ant-design/pro-components';
import { useModel } from '@umijs/max';
import { Alert, Button, Card, Form, Input, message, Select, Space, Switch, Table, Tag, Typography } from 'antd';
import React, { useCallback, useEffect, useState } from 'react';
import {
  bindUnifiedAuthMapping,
  getUnifiedAuthConfig,
  getUnifiedAuthLocalUsers,
  getUnifiedAuthMappings,
  saveUnifiedAuthConfig,
  testUnifiedAuthConnection,
  unbindUnifiedAuthMapping,
  type UnifiedAuthConfig,
  type UnifiedAuthConfigPayload,
  type UnifiedAuthIdentity,
  type UnifiedAuthLocalUser,
} from '@/services/unified-auth';

type FormValues = Omit<UnifiedAuthConfigPayload, 'enabled' | 'autoProvisionGeneralUser'> & {
  enabled: boolean;
  autoProvisionGeneralUser: boolean;
};

const statusTag = (identity: UnifiedAuthIdentity) => {
  if (!identity.localUserId) {
    return <Tag color="gold">待绑定</Tag>;
  }
  return identity.localUserType === 'ADMIN_USER'
    ? <Tag color="blue">管理员</Tag>
    : <Tag color="green">普通用户</Tag>;
};

const UnifiedUsersPage: React.FC = () => {
  const { initialState } = useModel('@@initialState');
  const [form] = Form.useForm<FormValues>();
  const oidcEnabled = Form.useWatch('enabled', form);
  const ticketEnabled = Form.useWatch('ticketEnabled', form);
  const [config, setConfig] = useState<UnifiedAuthConfig>({});
  const [identities, setIdentities] = useState<UnifiedAuthIdentity[]>([]);
  const [users, setUsers] = useState<UnifiedAuthLocalUser[]>([]);
  const [loading, setLoading] = useState(false);
  const [saving, setSaving] = useState(false);
  const [testing, setTesting] = useState(false);
  const [binding, setBinding] = useState<number>();

  const load = useCallback(async () => {
    setLoading(true);
    try {
      const [configResult, mappingResult, userResult] = await Promise.all([
        getUnifiedAuthConfig(),
        getUnifiedAuthMappings(),
        getUnifiedAuthLocalUsers(),
      ]);
      const nextConfig = configResult.data || {};
      setConfig(nextConfig);
      setIdentities(mappingResult.data || []);
      setUsers(userResult.data || []);
      form.setFieldsValue({
        issuer: nextConfig.issuer || '',
        clientId: nextConfig.clientId || '',
        redirectUri: nextConfig.redirectUri || '',
        clientSecret: '',
        enabled: Boolean(nextConfig.enabled),
        autoProvisionGeneralUser: Boolean(nextConfig.autoProvisionGeneralUser),
        ticketEnabled: Boolean(nextConfig.ticketEnabled),
        ticketValidationEndpoint: nextConfig.ticketValidationEndpoint || '',
        ticketValidationMethod: nextConfig.ticketValidationMethod || 'GET',
        ticketLoginEntryUrl: nextConfig.ticketLoginEntryUrl || '',
        ticketIssuer: nextConfig.ticketIssuer || '',
        ticketParameterName: nextConfig.ticketParameterName || 'ticket',
        ticketSuccessField: nextConfig.ticketSuccessField || 'success',
        ticketSuccessValue: nextConfig.ticketSuccessValue || 'true',
        ticketUsernameField: nextConfig.ticketUsernameField || 'username',
        ticketResultEmailPointer: nextConfig.ticketResultEmailPointer || '/email',
        ticketResultDisplayNamePointer: nextConfig.ticketResultDisplayNamePointer || '/name',
      });
    } catch (_error) {
      message.error('统一用户配置读取失败');
    } finally {
      setLoading(false);
    }
  }, [form]);

  useEffect(() => {
    if (initialState?.currentUser?.access === 'admin') {
      void load();
    }
  }, [initialState?.currentUser?.access, load]);

  const save = async (values: FormValues) => {
    setSaving(true);
    try {
      const payload: UnifiedAuthConfigPayload = {
        issuer: values.issuer,
        clientId: values.clientId,
        redirectUri: values.redirectUri,
        enabled: values.enabled,
        autoProvisionGeneralUser: values.autoProvisionGeneralUser,
        ticketEnabled: values.ticketEnabled,
        ticketValidationEndpoint: values.ticketValidationEndpoint,
        ticketValidationMethod: values.ticketValidationMethod,
        ticketLoginEntryUrl: values.ticketLoginEntryUrl,
        ticketIssuer: values.ticketIssuer,
        ticketParameterName: values.ticketParameterName,
        ticketSuccessField: values.ticketSuccessField,
        ticketSuccessValue: values.ticketSuccessValue,
        ticketUsernameField: values.ticketUsernameField,
        ticketResultEmailPointer: values.ticketResultEmailPointer,
        ticketResultDisplayNamePointer: values.ticketResultDisplayNamePointer,
        ...(values.clientSecret ? { clientSecret: values.clientSecret } : {}),
      };
      const result = await saveUnifiedAuthConfig(payload);
      const nextConfig = result.data || {};
      setConfig(nextConfig);
      form.setFieldValue('clientSecret', '');
      message.success('统一用户配置已保存');
      await load();
    } catch (_error) {
      message.error('统一用户配置保存失败，请检查 Issuer、Client Secret 和加密密钥配置');
    } finally {
      setSaving(false);
    }
  };

  const testConnection = async () => {
    const values = await form.validateFields(['issuer', 'clientId']);
    setTesting(true);
    try {
      const result = await testUnifiedAuthConnection({
        ...form.getFieldsValue(),
        issuer: values.issuer,
        clientId: values.clientId,
      } as UnifiedAuthConfigPayload);
      if (result.data?.connected) {
        message.success(result.data.message || 'OIDC Provider 连接正常');
      } else {
        message.error(result.data?.message || 'OIDC Provider 连接失败');
      }
    } catch (_error) {
      message.error('OIDC Provider 连接失败');
    } finally {
      setTesting(false);
    }
  };

  const bind = async (identity: UnifiedAuthIdentity, localUserId?: number) => {
    if (!localUserId) {
      return;
    }
    setBinding(identity.identityId);
    try {
      await bindUnifiedAuthMapping(identity.identityId, localUserId);
      message.success('外部身份已绑定');
      await load();
    } catch (_error) {
      message.error('外部身份绑定失败');
    } finally {
      setBinding(undefined);
    }
  };

  const unbind = async (identity: UnifiedAuthIdentity) => {
    setBinding(identity.identityId);
    try {
      await unbindUnifiedAuthMapping(identity.identityId);
      message.success('外部身份已解除绑定');
      await load();
    } catch (_error) {
      message.error('外部身份解除绑定失败');
    } finally {
      setBinding(undefined);
    }
  };

  if (initialState?.currentUser?.access !== 'admin') {
    return <PageContainer><Alert type="error" showIcon message="此页面仅供管理员使用" /></PageContainer>;
  }

  return (
    <PageContainer>
      <div style={{ display: 'grid', gridTemplateColumns: 'minmax(340px, 0.9fr) minmax(480px, 1.1fr)', gap: 16, alignItems: 'stretch' }}>
        <Card
          title={<Space><SafetyCertificateOutlined /><span>OIDC Provider</span></Space>}
          extra={<Button icon={<ReloadOutlined />} onClick={() => void load()} loading={loading}>刷新</Button>}
          style={{ minHeight: 420 }}
        >
          <Form<FormValues> form={form} layout="vertical" onFinish={save}>
            <Form.Item
              label="Issuer"
              name="issuer"
              rules={oidcEnabled ? [{ required: true, message: '请输入 OIDC Issuer' }] : []}
            >
              <Input placeholder="https://identity.example.com" autoComplete="off" />
            </Form.Item>
            <Form.Item
              label="Client ID"
              name="clientId"
              rules={oidcEnabled ? [{ required: true, message: '请输入 Client ID' }] : []}
            >
              <Input autoComplete="off" />
            </Form.Item>
            <Form.Item
              label="Client Secret"
              name="clientSecret"
              extra={config.clientSecretConfigured ? '已配置，留空可保留当前密钥' : '启用 OIDC 时需要 Client Secret；使用服务端数据源主密钥加密保存'}
              rules={config.clientSecretConfigured || !oidcEnabled ? [] : [{ required: true, message: '请输入 Client Secret' }]}
            >
              <Input.Password autoComplete="new-password" placeholder={config.clientSecretConfigured ? '已配置' : '请输入 Client Secret'} />
            </Form.Item>
            <Form.Item label="Redirect URI" name="redirectUri" extra="请将此固定回调地址登记为 OIDC Provider 的 Redirect URI">
              <Input readOnly />
            </Form.Item>
            <Form.Item name="enabled" valuePropName="checked" label="启用统一用户">
              <Switch checkedChildren="启用" unCheckedChildren="停用" />
            </Form.Item>
            <Form.Item name="autoProvisionGeneralUser" valuePropName="checked" label="自动创建普通用户">
              <Switch checkedChildren="自动创建" unCheckedChildren="仅待绑定" />
            </Form.Item>
            <Form.Item
              name="ticketEnabled"
              valuePropName="checked"
              label="启用外部单次 ticket"
              extra="外部 ticket 可独立使用；关闭 OIDC 时无需填写 OIDC Issuer、Client ID 或 Client Secret。"
            >
              <Switch checkedChildren="启用" unCheckedChildren="停用" />
            </Form.Item>
            <Form.Item
              label="ticket 验证地址"
              name="ticketValidationEndpoint"
              rules={ticketEnabled ? [{ required: true, message: '请输入外部 ticket 验证地址' }] : []}
              extra="验证请求会在服务端发送，ticket 不会写入日志或转发给浏览器。"
            >
              <Input placeholder="https://identity.example.com/api/ticket/validate" autoComplete="off" />
            </Form.Item>
            <Form.Item label="验证方法" name="ticketValidationMethod">
              <Select options={[{ value: 'GET', label: 'GET' }, { value: 'POST', label: 'POST' }]} />
            </Form.Item>
            <Form.Item
              label="外部登录入口地址（可选）"
              name="ticketLoginEntryUrl"
              extra="由管理员维护的可信门户 URL；生产使用 HTTPS，本机回环地址可用 HTTP 测试。门户回跳仍使用 /api/v1/unified-auth/sso/entry?ticket=...。留空时仍可接受外部门户主动发起的 ticket 回调。"
            >
              <Input placeholder="https://login.example.com/" autoComplete="off" />
            </Form.Item>
            <Form.Item
              label="Ticket Issuer"
              name="ticketIssuer"
              rules={ticketEnabled ? [{ required: true, message: '请输入稳定的外部 Issuer' }] : []}
              extra="用于隔离外部身份命名空间，必须是稳定的 HTTPS Issuer 地址。"
            >
              <Input placeholder="https://identity.example.com" autoComplete="off" />
            </Form.Item>
            <Form.Item
              label="验证请求参数名"
              name="ticketParameterName"
              rules={ticketEnabled ? [{ required: true, message: '请输入 ticket 参数名' }] : []}
            >
              <Input placeholder="ticket" autoComplete="off" />
            </Form.Item>
            <Form.Item
              label="验证成功字段"
              name="ticketSuccessField"
              rules={ticketEnabled ? [{ required: true, message: '请输入成功标记字段路径' }] : []}
              extra="使用点分隔 JSON 路径，例如 success 或 data.valid。"
            >
              <Input placeholder="success" autoComplete="off" />
            </Form.Item>
            <Form.Item
              label="验证成功值"
              name="ticketSuccessValue"
              rules={ticketEnabled ? [{ required: true, message: '请输入成功标记值' }] : []}
            >
              <Input placeholder="true" autoComplete="off" />
            </Form.Item>
            <Form.Item
              label="用户名字段路径"
              name="ticketUsernameField"
              rules={ticketEnabled ? [{ required: true, message: '请输入用户字段路径' }] : []}
              extra="该值作为外部 subject；同一 Issuer 下必须稳定且唯一，例如 data.username。"
            >
              <Input placeholder="username" autoComplete="off" />
            </Form.Item>
            <Form.Item label="Email JSON Pointer" name="ticketResultEmailPointer">
              <Input placeholder="/email" />
            </Form.Item>
            <Form.Item label="显示名称 JSON Pointer" name="ticketResultDisplayNamePointer">
              <Input placeholder="/name" />
            </Form.Item>
            <Space wrap>
              <Button
                icon={<LockOutlined />}
                loading={testing}
                disabled={!oidcEnabled}
                onClick={() => void testConnection()}
              >
                测试 OIDC Provider
              </Button>
              <Button type="primary" htmlType="submit" icon={<SaveOutlined />} loading={saving}>保存配置</Button>
              {config.enabled && <a href="/api/v1/unified-auth/oidc/start">使用统一用户登录</a>}
            </Space>
          </Form>
        </Card>

        <Card
          title="外部身份绑定"
          extra={<Typography.Text type="secondary">角色取自绑定的本地用户</Typography.Text>}
          style={{ minHeight: 420 }}
          loading={loading}
        >
          <Table<UnifiedAuthIdentity>
            rowKey="identityId"
            dataSource={identities}
            pagination={{ pageSize: 6, showSizeChanger: false }}
            size="middle"
            scroll={{ x: 760 }}
            columns={[
              {
                title: '外部身份',
                dataIndex: 'displayName',
                render: (_value, identity) => (
                  <Space direction="vertical" size={2}>
                    <span>{identity.displayName || identity.email || identity.subject}</span>
                    <Typography.Text type="secondary">{identity.email || identity.issuer}</Typography.Text>
                    <Typography.Text copyable={{ text: identity.subject }}>sub: {identity.subject}</Typography.Text>
                  </Space>
                ),
              },
              { title: '角色', width: 100, render: (_value, identity) => statusTag(identity) },
              {
                title: '本地用户',
                width: 270,
                render: (_value, identity) => identity.localUserId ? (
                  <Space>
                    <span>{identity.localUserName || `用户 ${identity.localUserId}`}</span>
                    <Button size="small" danger loading={binding === identity.identityId} onClick={() => void unbind(identity)}>解除绑定</Button>
                  </Space>
                ) : (
                  <Select
                    aria-label={`绑定 ${identity.displayName || identity.subject}`}
                    style={{ width: 250 }}
                    placeholder="选择本地用户"
                    showSearch
                    optionFilterProp="label"
                    loading={binding === identity.identityId}
                    onChange={(value: number) => void bind(identity, value)}
                    options={users.map((user) => ({
                      value: user.id,
                      label: `${user.userName}（${user.userType === 'ADMIN_USER' ? '管理员' : '普通用户'}）`,
                    }))}
                  />
                ),
              },
              { title: '最近验证', dataIndex: 'lastObservedAt', width: 160 },
            ]}
          />
        </Card>
      </div>
    </PageContainer>
  );
};

export default UnifiedUsersPage;
