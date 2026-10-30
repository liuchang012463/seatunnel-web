import { ReadOutlined } from '@ant-design/icons';
import { Empty } from 'antd';
import React from 'react';
import './FeaturePlaceholderPage.less';

const FeaturePlaceholderPage: React.FC = () => {
  return (
    <div className="feature-placeholder-page">
      <div className="feature-placeholder-page__panel">
        <Empty
          image={<ReadOutlined style={{ fontSize: 40, color: 'var(--st-color-text-muted)' }} />}
          description={<span className="feature-placeholder-page__desc">功能建设中</span>}
        />
      </div>
    </div>
  );
};

export default FeaturePlaceholderPage;
