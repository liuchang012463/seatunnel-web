import { SunOutlined } from "@ant-design/icons";
import { useIntl } from "@umijs/max";
import { Button } from "antd";
import React from "react";
import TaskListPageHeader from "@/components/TaskListPageHeader";

interface DataSyncHeaderProps {
  goDetail: (value: any) => void;
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
  sourceType,
  setSourceType,
  targetType,
  setTargetType,
}) => {
  const intl = useIntl();

  const handleSourceChange = (value: string, option: any) => {
    setSourceType({
      dbType: value,
      connectorType: option?.connectorType,
      pluginName: option?.pluginName,
    });
  };

  const handleTargetChange = (value: string, option: any) => {
    setTargetType({
      dbType: value,
      connectorType: option?.connectorType,
      pluginName: option?.pluginName,
    });
  };

  const handleCreateClick = () => {
    goDetail(undefined);
  };

  const isButtonDisabled = !sourceType || !targetType;

  return (
    <TaskListPageHeader
      icon={<SunOutlined />}
      title={intl.formatMessage({
        id: "pages.datasync.header.title",
        defaultMessage: "链路管理（离线）",
      })}
      subtitle={intl.formatMessage({
        id: "pages.datasync.header.subtitle",
        defaultMessage: "统一管理采集引接链路：配置、调度与健康状态监测",
      })}
      actions={
        <Button
          type="primary"
          disabled={isButtonDisabled}
          onClick={handleCreateClick}
          className="task-list-page-header__create-button font-semibold"
        >
          创建离线任务
        </Button>
      }
    >
      
    </TaskListPageHeader>
  );
};

export default DataSyncHeader;
