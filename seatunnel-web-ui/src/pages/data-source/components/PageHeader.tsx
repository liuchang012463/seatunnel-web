import React from 'react';
import { Button } from 'antd';
import { PlusOutlined, SettingOutlined } from '@ant-design/icons';

interface PageHeaderProps {
  onCreate: () => void;
  onManageMasterData?: () => void;
}

const PageHeader: React.FC<PageHeaderProps> = ({ onCreate, onManageMasterData }) => {
  return (
    <div className="flex flex-shrink-0 flex-wrap items-center justify-end gap-3">
      {onManageMasterData && (
        <Button
          icon={<SettingOutlined />}
          size="large"
          onClick={onManageMasterData}
        >
          单位与业务系统维护
        </Button>
      )}
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
