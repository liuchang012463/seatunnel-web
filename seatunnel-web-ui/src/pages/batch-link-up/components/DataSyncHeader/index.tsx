import { PlusOutlined } from "@ant-design/icons";
import { Button } from "antd";
import React from "react";
import TaskListPageHeader from "@/components/TaskListPageHeader";

interface DataSyncHeaderProps {
  goDetail: (value?: any) => void;
  sourceType: any;
  targetType: any;
  setSourceType: (value: any) => void;
  setTargetType: (value: any) => void;
}

export interface SyncParams {
  sourceType: string;
  targetType: string;
}

const DataSyncHeader: React.FC<DataSyncHeaderProps> = ({
  goDetail,
}) => {
  const handleCreateClick = () => {
    goDetail();
  };

  return (
    <TaskListPageHeader
      actions={
        <Button
          type="primary"
          icon={<PlusOutlined />}
          onClick={handleCreateClick}
          className="task-list-page-header__create-button"
        >
          创建批量任务
        </Button>
      }
    />
  );
};

export default DataSyncHeader;
