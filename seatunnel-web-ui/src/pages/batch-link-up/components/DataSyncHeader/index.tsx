import React from "react";

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

const DataSyncHeader: React.FC<DataSyncHeaderProps> = () => (
  <div className="offline-task-page-header">
    数据引接 / 离线引接任务
  </div>
);

export default DataSyncHeader;
