import React from 'react';
import { Button } from 'antd';
import { PlusOutlined } from '@ant-design/icons';
import { useIntl } from '@umijs/max';

interface PageHeaderProps {
  onCreate: () => void;
}

const PageHeader: React.FC<PageHeaderProps> = ({ onCreate }) => {
  const intl = useIntl();

  return (
    <div className="datasource-page-header">
      <div className="min-w-0 flex-1">
        <div className="mb-2 flex items-center gap-3">
          <div className="datasource-page-icon-wrapper">
            <svg
              xmlns="http://www.w3.org/2000/svg"
              width="23"
              height="23"
              viewBox="0 0 24 24"
              fill="none"
              stroke="currentColor"
              strokeWidth="2"
              strokeLinecap="round"
              strokeLinejoin="round"
            >
              <path d="M6 22V4a2 2 0 0 1 2-2h8a2 2 0 0 1 2 2v18Z" />
              <path d="M6 12H4a2 2 0 0 0-2 2v6a2 2 0 0 0 2 2h2" />
              <path d="M18 9h2a2 2 0 0 1 2 2v9a2 2 0 0 1-2 2h-2" />
              <path d="M10 6h4" />
              <path d="M10 10h4" />
              <path d="M10 14h4" />
              <path d="M10 18h4" />
            </svg>
          </div>

          <h1 className="datasource-page-title">
            {intl.formatMessage({
              id: 'pages.datasource.header.title',
              defaultMessage: 'List of Data Sources',
            })}
          </h1>
        </div>

        <p className="datasource-page-description">
          统一管理数据源连接、访问权限与安全策略，让数据接入更规范、更可控。
        </p>
      </div>

      <Button
        type="primary"
        icon={<PlusOutlined />}
        size="large"
        onClick={onCreate}
        className="datasource-create-button"
      >
        新建数据源
      </Button>
    </div>
  );
};

export default PageHeader;
