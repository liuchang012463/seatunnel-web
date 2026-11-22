import { Button, Card, Form, Input, Space, Alert, Typography, message } from 'antd';
import React, { useEffect, useState } from 'react';
import { history, useModel } from '@umijs/max';
import HttpUtils from '@/utils/HttpUtils';
import {
  cancelUnifiedAuthTicketLogin,
  confirmUnifiedAuthTicketLogin,
  getUnifiedAuthTicketPending,
  type UnifiedAuthTicketPending,
} from '@/services/unified-auth';
import './index.less';

type ProviderStatus = {
  enabled?: boolean;
  authenticationMode?: string;
  ticketLoginEnabled?: boolean;
  ticketLoginEntryUrl?: string;
};

type LoginValues = {
  userName: string;
  userPassword: string;
};

const UnifiedLoginPage: React.FC = () => {
  const { initialState } = useModel('@@initialState');
  const [provider, setProvider] = useState<ProviderStatus>({});
  const [loading, setLoading] = useState(false);
  const [ticketPending, setTicketPending] = useState<UnifiedAuthTicketPending>();
  const [ticketLoginFailed, setTicketLoginFailed] = useState(false);
  const [ticketConfirming, setTicketConfirming] = useState(false);
  const [ticketCancelling, setTicketCancelling] = useState(false);
  const [form] = Form.useForm<LoginValues>();

  useEffect(() => {
    if (initialState?.currentUser?.userid) {
      history.replace('/bi');
      return;
    }
    let active = true;
    void HttpUtils.get<ProviderStatus>('/api/v1/unified-auth/oidc/provider', { skipErrorHandler: true })
      .then((response) => {
        if (active) setProvider(response.data || {});
      })
      .catch(() => {
        if (active) setProvider({});
      });
    return () => {
      active = false;
    };
  }, [initialState?.currentUser?.userid]);

  useEffect(() => {
    if (window.location.pathname !== '/login' || initialState?.currentUser?.userid) {
      return undefined;
    }
    let active = true;
    setTicketLoginFailed(new URLSearchParams(window.location.search).get('ticketLogin') === 'failed');
    void getUnifiedAuthTicketPending()
      .then((response) => {
        if (active) setTicketPending(response.data);
      })
      .catch(() => {
        if (active) setTicketPending(undefined);
      });
    return () => {
      active = false;
    };
  }, [initialState?.currentUser?.userid]);

  const submitPassword = async (values: LoginValues) => {
    setLoading(true);
    try {
      const result = await HttpUtils.post<boolean>('/api/v1/login', values);
      if (result.code !== 0) {
        throw new Error(result.msg || '本地账号登录失败');
      }
      window.location.assign('/bi');
    } catch (error) {
      message.error(error instanceof Error ? error.message : '本地账号登录失败');
    } finally {
      setLoading(false);
    }
  };

  const confirmTicketLogin = async () => {
    const confirmationToken = ticketPending?.confirmationToken;
    if (!ticketPending?.confirmable || !confirmationToken) return;
    setTicketConfirming(true);
    try {
      const result = await confirmUnifiedAuthTicketLogin(confirmationToken);
      if (result.code !== 0) throw new Error(result.msg || '外部登录确认失败');
      window.location.assign('/bi');
    } catch (error) {
      message.error(error instanceof Error ? error.message : '外部登录确认失败，请重新登录');
      setTicketPending(undefined);
    } finally {
      setTicketConfirming(false);
    }
  };

  const cancelTicketLogin = async () => {
    const cancellationToken = ticketPending?.cancellationToken;
    if (!cancellationToken) return;
    setTicketCancelling(true);
    try {
      const result = await cancelUnifiedAuthTicketLogin(cancellationToken);
      if (result.code !== 0) throw new Error(result.msg || '取消外部登录失败');
      setTicketPending(undefined);
      setTicketLoginFailed(false);
    } catch (error) {
      message.error(error instanceof Error ? error.message : '取消外部登录失败');
    } finally {
      setTicketCancelling(false);
    }
  };

  const isDevBypass = provider.authenticationMode === 'DEV_BYPASS';

  return (
    <main className="unified-login-page">
      <Card className="unified-login-card" title={<Typography.Title level={2}>登录 SeaTunnel Web</Typography.Title>}>
        <Space direction="vertical" size={20} style={{ width: '100%' }}>
          {ticketPending ? (
            <Alert
              showIcon
              type={ticketPending.confirmable ? 'info' : 'warning'}
              message={ticketPending.confirmable
                ? `请确认登录本地账号：${ticketPending.localUserName}`
                : '外部身份尚未绑定可用的本地账号'}
              description={(
                <Space direction="vertical" size={8}>
                  <Typography.Text>外部身份：{ticketPending.externalDisplayName}</Typography.Text>
                  {ticketPending.externalEmail ? (
                    <Typography.Text>邮箱：{ticketPending.externalEmail}</Typography.Text>
                  ) : null}
                  {ticketPending.confirmable ? (
                    <Typography.Text>确认后将登录显示的本地账号。</Typography.Text>
                  ) : (
                    <Typography.Text>请联系管理员完成身份绑定后重新发起登录。</Typography.Text>
                  )}
                  <Space>
                    {ticketPending.confirmable ? (
                      <Button type="primary" loading={ticketConfirming} onClick={() => void confirmTicketLogin()}>
                        确认并登录
                      </Button>
                    ) : null}
                    <Button
                      disabled={!ticketPending.cancellationToken}
                      loading={ticketCancelling}
                      onClick={() => void cancelTicketLogin()}
                    >
                      取消
                    </Button>
                  </Space>
                </Space>
              )}
            />
          ) : null}
          {ticketLoginFailed && !ticketPending ? (
            <Alert showIcon type="error" message="外部身份登录失败，请重新尝试或使用本地账号登录。" />
          ) : null}
          {isDevBypass ? (
            <Alert
              showIcon
              type="warning"
              message="当前使用 DEV_BYPASS 开发旁路，统一认证与密码会话均未生效。"
              description="生产环境请设置 SEATUNNEL_WEB_AUTH_MODE=PASSWORD。"
            />
          ) : null}
          {provider.enabled ? (
            <Button
              block
              size="large"
              type="primary"
              href="/api/v1/unified-auth/oidc/start"
            >
              使用统一用户登录
            </Button>
          ) : null}
          {provider.ticketLoginEnabled && provider.ticketLoginEntryUrl ? (
            <Button
              block
              size="large"
              href={provider.ticketLoginEntryUrl}
            >
              通过外部身份门户登录
            </Button>
          ) : null}

          {!isDevBypass ? (
            <Form<LoginValues> form={form} layout="vertical" onFinish={submitPassword}>
              <Form.Item
                label="本地账号"
                name="userName"
                rules={[{ required: true, message: '请输入本地账号' }]}
              >
                <Input autoComplete="username" />
              </Form.Item>
              <Form.Item
                label="密码"
                name="userPassword"
                rules={[{ required: true, message: '请输入密码' }]}
              >
                <Input.Password autoComplete="current-password" />
              </Form.Item>
              <Button block type="default" htmlType="submit" loading={loading}>
                使用本地账号登录
              </Button>
            </Form>
          ) : null}

          <Typography.Text type="secondary">
            本地管理员账号保留作故障恢复入口。统一用户不可用时，可联系管理员恢复本地登录配置。
          </Typography.Text>
          <Button type="link" onClick={() => history.replace('/bi')}>
            返回应用
          </Button>
        </Space>
      </Card>
    </main>
  );
};

export default UnifiedLoginPage;
