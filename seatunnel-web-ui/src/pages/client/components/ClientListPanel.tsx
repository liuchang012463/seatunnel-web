import React from "react";
import {
  DeleteOutlined,
  EditOutlined,
  LinkOutlined,
} from "@ant-design/icons";
import { Button, Popconfirm, Tooltip } from "antd";
import { getHealthMeta } from "../utils";
import "./client-list-panel.less";

interface Props {
  clients: any[];
  selectedClientId?: number;
  onSelect: (id: number) => void;
  onEdit: (client: any) => void;
  onDelete: (client: any) => void;
  deleteLoadingId?: number;
}

const ClientListPanel: React.FC<Props> = ({
  clients,
  selectedClientId,
  onSelect,
  onEdit,
  onDelete,
  deleteLoadingId,
}) => {
  return (
    <div className="client-list-panel">
      <div className="client-list-panel__title">Client 列表</div>

      <div className="client-list-panel__items">
        {(clients || []).map((client) => {
          const active = client.id === selectedClientId;
          const itemHealth = getHealthMeta(client.healthStatus);
          const deleting = deleteLoadingId === client.id;

          return (
            <div
              key={client.id}
              onClick={() => onSelect(client.id)}
              className={[
                "client-list-panel__item",
                active ? "is-active" : "",
              ]
                .filter(Boolean)
                .join(" ")}
            >
              <div className="client-list-panel__item-main">
                <span className="client-list-panel__icon">
                  <LinkOutlined />
                </span>

                <div className="client-list-panel__meta">
                  <div className="client-list-panel__name">
                    {client.clientName || "--"}
                  </div>
                  <div className="client-list-panel__remark">
                    <span
                      className={`client-list-panel__dot ${itemHealth.dot}`}
                    />
                    {client?.remark ? (
                      <span className="truncate">{client.remark}</span>
                    ) : (
                      <span className="truncate">暂无备注</span>
                    )}
                  </div>
                </div>
              </div>

              <div
                className="client-list-panel__actions"
                onClick={(e) => e.stopPropagation()}
              >
                <Tooltip title="编辑">
                  <Button
                    size="small"
                    type="text"
                    icon={<EditOutlined />}
                    onClick={() => onEdit(client)}
                  />
                </Tooltip>

                <Popconfirm
                  title="删除 Client"
                  description="删除后不可恢复，确定要删除这个 Client 吗？"
                  okText="删除"
                  cancelText="取消"
                  okButtonProps={{
                    danger: true,
                    loading: deleting,
                  }}
                  onConfirm={() => onDelete(client)}
                >
                  <Tooltip title="删除">
                    <Button
                      size="small"
                      type="text"
                      danger
                      icon={<DeleteOutlined />}
                      loading={deleting}
                    />
                  </Tooltip>
                </Popconfirm>
              </div>
            </div>
          );
        })}

        {!clients?.length ? (
          <div className="client-list-panel__empty">
            还没有 Client，先新增一个地址吧。
          </div>
        ) : null}
      </div>
    </div>
  );
};

export default ClientListPanel;
