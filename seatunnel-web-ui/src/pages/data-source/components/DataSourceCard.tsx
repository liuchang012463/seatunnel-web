import {
  ApiOutlined,
  CheckCircleOutlined,
  DeleteOutlined,
  MoreOutlined,
  StopOutlined,
} from "@ant-design/icons";
import { Button, Card, Dropdown, Tag, Tooltip } from "antd";
import React from "react";
import { environmentTagConfigMap } from "../constants";
import { getDataSourceCategory } from "../dataSourceRegistry";
import DatabaseIcons from "../icon/DatabaseIcons";
import type { DataSourceLifecycleStatus, DataSourceRecord } from "../types";
import DataSourceLifecycleStatusTag from "./DataSourceLifecycleStatus";
import DataSourceStatus from "./DataSourceStatus";

interface DataSourceCardProps {
  record: DataSourceRecord;
  onEdit: (record: DataSourceRecord) => void;
  onDelete: (record: DataSourceRecord) => void;
  onTestConnection: (record: DataSourceRecord) => void;
  onStatusChange: (
    record: DataSourceRecord,
    status: DataSourceLifecycleStatus,
  ) => void;
}

const DataSourceCard: React.FC<DataSourceCardProps> = ({
  record,
  onEdit,
  onDelete,
  onTestConnection,
  onStatusChange,
}) => {
  const environmentConfig = environmentTagConfigMap[
    record.environment || ""
  ] || {
    text: record.environmentName || "-",
    color: "var(--st-color-text-muted)",
    backgroundColor: "rgba(102, 111, 117, 0.14)",
    icon: null,
  };
  const category = getDataSourceCategory(record.dbType);
  const currentStatus = record.status || "ENABLED";
  const lifecycleItems = [
    currentStatus !== "ENABLED" && currentStatus !== "REVOKED"
      ? {
          key: "ENABLED",
          label: "启用",
          icon: <CheckCircleOutlined />,
        }
      : null,
    currentStatus !== "DISABLED" && currentStatus !== "REVOKED"
      ? {
          key: "DISABLED",
          label: "停用",
          icon: <StopOutlined />,
        }
      : null,
    currentStatus !== "REVOKED"
      ? {
          key: "REVOKED",
          label: "注销",
          danger: true,
        }
      : {
          key: "REVOKED",
          label: "已注销",
          disabled: true,
        },
  ].filter(Boolean) as Array<{
    key: string;
    label: string;
    icon?: React.ReactNode;
    danger?: boolean;
    disabled?: boolean;
  }>;

  return (
    <Card
      bodyStyle={{ padding: 0 }}
      className={[
        "datasource-card group relative",
        "transition-colors duration-200 ease-out",
        "hover:!translate-y-0 hover:!transform-none",
      ].join(" ")}
    >
      <div className="datasource-card-cover">
        <div
          className="datasource-card-logo"
        >
          <DatabaseIcons dbType={record.dbType} width="28" height="28" />
        </div>

        <div className="datasource-card-env-tag">
          <span
            className="datasource-card-env-tag-inner"
            style={{
              background: environmentConfig.backgroundColor,
              color: environmentConfig.color,
            }}
          >
            {environmentConfig.icon}
            {record.environmentName || environmentConfig.text}
          </span>
        </div>

        <div
          className={[
            "datasource-card-hover-actions",
            "opacity-0 translate-y-[-6px] pointer-events-none",
            "transition-all duration-200 ease-out",
            "group-hover:opacity-100 group-hover:translate-y-0 group-hover:pointer-events-auto",
          ].join(" ")}
        >
          <Tooltip title="删除" placement="top">
            <button
              type="button"
              className="datasource-card-hover-action datasource-card-hover-action--danger"
              onClick={(event) => {
                event.stopPropagation();
                onDelete(record);
              }}
            >
              <DeleteOutlined />
            </button>
          </Tooltip>

          <Tooltip title="测试连接" placement="top">
            <button
              type="button"
              className="datasource-card-hover-action"
              onClick={(event) => {
                event.stopPropagation();
                onTestConnection(record);
              }}
            >
              <ApiOutlined />
            </button>
          </Tooltip>

          <Tooltip title="生命周期操作" placement="top">
            <Dropdown
              trigger={["click"]}
              menu={{
                items: lifecycleItems,
                onClick: ({ key }) => {
                  onStatusChange(record, key as DataSourceLifecycleStatus);
                },
              }}
            >
              <button
                type="button"
                className="datasource-card-hover-action"
                onClick={(event) => event.stopPropagation()}
              >
                <MoreOutlined />
              </button>
            </Dropdown>
          </Tooltip>
        </div>
      </div>

      <div className="datasource-card-content">
        <div
          className="datasource-card-title truncate"
          title={record.name}
        >
          {record.name || "-"}
        </div>

        <div
          className="datasource-card-jdbc-url"
          title={record.jdbcUrl}
        >
          {record.jdbcUrl || "-"}
        </div>

        <div className="datasource-card-status flex items-center gap-2">
          <DataSourceStatus status={record.connStatus} />
          <DataSourceLifecycleStatusTag status={record.status} />
          <Tag color="blue" style={{ marginInlineEnd: 0, borderRadius: 999 }}>
            {category.label}
          </Tag>
        </div>

        <div className="datasource-card-unit" title={record.dataSourceUnit}>
          单位：{record.dataSourceUnit || "未分配"}
        </div>

        <div className="datasource-card-update-time">
          <span className="datasource-card-update-time-value">
            {record.updateTime || "-"}
          </span>
        </div>

        <Button
          block
          type="primary"
          className={[
            "datasource-card-detail-button group/detail relative overflow-hidden p-0",
            "transition-all duration-300 ease-out",
          ].join(" ")}
          onClick={() => onEdit(record)}
        >
          查看详情
        </Button>
      </div>
    </Card>
  );
};

export default DataSourceCard;
