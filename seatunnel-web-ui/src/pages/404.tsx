import { CompassOutlined } from '@ant-design/icons';
import { history, useIntl } from '@umijs/max';
import { Button, Result } from 'antd';
import React, { useEffect } from 'react';

const NoFoundPage: React.FC = () => {
  const intl = useIntl();

  useEffect(() => {
    document.title = '页面不存在 - Aircas Web';
  }, []);

  return (
    <div
      style={{
        minHeight: '100vh',
        display: 'flex',
        alignItems: 'center',
        justifyContent: 'center',
        padding: 24,
        background: 'var(--st-color-bg-app)',
      }}
    >
      <Result
        // Ant Design ignores the icon prop for the built-in 404 illustration, which is a
        // light-theme SVG. The info status lets us render the themed icon instead.
        status="info"
        icon={<CompassOutlined style={{ color: 'var(--st-color-accent)', fontSize: 64 }} />}
        title={<h1 style={{ margin: 0, fontSize: 28 }}>404</h1>}
        subTitle={intl.formatMessage({ id: 'pages.404.subTitle' })}
        extra={
          <Button type="primary" onClick={() => history.push('/')}>
            {intl.formatMessage({ id: 'pages.404.buttonText' })}
          </Button>
        }
      />
    </div>
  );
};

export default NoFoundPage;
