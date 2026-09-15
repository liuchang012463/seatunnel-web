import { ThunderboltOutlined } from "@ant-design/icons";
import { Button } from "antd";
import React from "react";
import TaskListPageHeader from "@/components/TaskListPageHeader";

interface RealtimeHeaderProps {
  sourceType: any;
  sinkType: any;
  onSourceChange: (value: any) => void;
  onSinkChange: (value: any) => void;
  onCreate: () => void;
  creating?: boolean;
}

const RealtimeHeader: React.FC<RealtimeHeaderProps> = ({
  sourceType,
  sinkType,
  onSourceChange,
  onSinkChange,
  onCreate,
  creating = false,
}) => {
  const handleSourceChange = (value: string, option: any) => {
    onSourceChange({
      dbType: value,
      connectorType: option?.connectorType,
      pluginName: option?.pluginName,
    });
  };

  const handleSinkChange = (value: string, option: any) => {
    onSinkChange({
      dbType: value,
      connectorType: option?.connectorType,
      pluginName: option?.pluginName,
    });
  };

  const isButtonDisabled = !sourceType?.dbType || !sinkType?.dbType;

  return (
    <TaskListPageHeader
      icon={<ThunderboltOutlined />}
      title="实时数据引接任务管理"
      subtitle="持续采集与实时处理数据流，统一管理实时数据引接任务"
      actions={
        <Button
          type="primary"
          disabled={isButtonDisabled}
          loading={creating}
          onClick={onCreate}
          className="h-10 rounded-full border-none bg-gradient-to-r font-semibold"
        >
          创建实时数据引接
        </Button>
      }
    >

          
    </TaskListPageHeader>
  );
};

export default RealtimeHeader;
