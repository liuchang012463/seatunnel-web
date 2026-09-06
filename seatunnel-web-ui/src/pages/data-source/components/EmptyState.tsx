import { Empty } from 'antd';
import React from "react";
import "./index.less";

type EmptyStateProps = {
  onCreate?: () => void;
};

const EmptyState: React.FC<EmptyStateProps> = ({ onCreate }) => {
  return (
    <div className="datasource-empty-state">
      <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description={false} />
      <div className="datasource-empty-state__message">
        暂无数据，
        <button type="button" onClick={onCreate}>
          立即新建数据源
        </button>
      </div>
    </div>
  );
};

export default EmptyState;
