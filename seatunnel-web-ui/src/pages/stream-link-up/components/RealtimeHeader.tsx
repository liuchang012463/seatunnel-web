import { PlusOutlined, ThunderboltOutlined } from "@ant-design/icons";
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
  onCreate,
  creating = false,
}) => {
  return (
    <TaskListPageHeader
      icon={<ThunderboltOutlined />}
      title="实时数据引接"
      subtitle="持续采集与实时处理数据流，统一管理实时数据引接任务"
      actions={
        <Button
          type="primary"
          icon={<PlusOutlined />}
          loading={creating}
          onClick={onCreate}
          className="task-list-page-header__create-button"
        >
          创建实时任务
        </Button>
      }
    />
  );
};

export default RealtimeHeader;
