import { PlusOutlined, SunOutlined } from "@ant-design/icons";
import { useIntl } from "@umijs/max";
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
  const intl = useIntl();

  const handleCreateClick = () => {
    goDetail();
  };

  return (
    <TaskListPageHeader
      icon={<SunOutlined />}
      title={intl.formatMessage({
        id: "pages.datasync.header.title",
        defaultMessage: "批量数据引接",
      })}
      subtitle={intl.formatMessage({
        id: "pages.datasync.header.subtitle",
        defaultMessage: "统一管理采集引接链路：配置、调度与健康状态监测",
      })}
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
