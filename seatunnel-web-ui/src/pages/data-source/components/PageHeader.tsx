import { PlusOutlined, SettingOutlined } from '@ant-design/icons';
import { Button } from 'antd';
import React from 'react';

interface PageHeaderProps {
  onCreate: () => void;
  onManageMasterData?: () => void;
}

const PageHeader: React.FC<PageHeaderProps> = ({ onCreate, onManageMasterData }) => (
  <div className="datasource-page-header">
    <div className="datasource-page-actions">
      <Button type="primary" icon={<PlusOutlined />} onClick={onCreate} className="datasource-create-button">
        新建数据源
      </Button>
      {onManageMasterData ? (
        <Button icon={<SettingOutlined />} onClick={onManageMasterData} className="datasource-master-data-button">
          单位与业务系统维护
        </Button>
      ) : null}
    </div>
  </div>
);

export default PageHeader;
