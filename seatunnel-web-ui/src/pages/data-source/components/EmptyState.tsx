import { PlusOutlined } from "@ant-design/icons";
import { useIntl } from "@umijs/max";
import { Button } from "antd";
import React from "react";
import "./index.less";

type EmptyStateProps = {
  onCreate?: () => void;
  filtered?: boolean;
  onClearFilters?: () => void;
};

const EmptyState: React.FC<EmptyStateProps> = ({ onCreate, filtered = false, onClearFilters }) => {
  const intl = useIntl();

  return (
    <div className="datasource-empty-state">
      <div className="datasource-empty-state__bg">
        <span className="bubble bubble-1" />
        <span className="bubble bubble-2" />
        <span className="bubble bubble-3" />
      </div>


      <div className="datasource-empty-state__title">
        {filtered
          ? intl.formatMessage({
              id: "pages.datasource.empty.filtered",
              defaultMessage: "没有找到匹配的数据源",
            })
          : intl.formatMessage({
              id: "pages.datasource.empty",
              defaultMessage: "我翻了一圈，这里还是空空的",
            })}
      </div>

      <div className="datasource-empty-state__desc">
        {filtered
          ? intl.formatMessage({
              id: "pages.datasource.empty.filtered.desc",
              defaultMessage: "当前搜索或筛选条件下没有结果，试试调整关键字或清除筛选。",
            })
          : intl.formatMessage({
              id: "pages.datasource.empty.desc",
              defaultMessage:
                "先创建一个数据源吧，这样我们就可以开始配置和使用了。",
            })}
      </div>

      <div className="datasource-empty-state__tip">
        {filtered
          ? intl.formatMessage({
              id: "pages.datasource.empty.filtered.tip",
              defaultMessage: "清除筛选后可查看全部数据源",
            })
          : intl.formatMessage({
              id: "pages.datasource.empty.tip",
              defaultMessage: "点一下“创建数据源”，马上就能使用了",
            })}
      </div>

      <div
        className="datasource-empty-state__actions"
        style={{ display: "flex", justifyContent: "center", gap: 12 }}
      >
        {filtered ? (
          <>
            <Button type="primary" onClick={onClearFilters}>
              {intl.formatMessage({
                id: "pages.datasource.empty.clearFilters",
                defaultMessage: "清除筛选",
              })}
            </Button>
            <Button onClick={onCreate}>
              {intl.formatMessage({
                id: "pages.datasource.create",
                defaultMessage: "创建数据源",
              })}
            </Button>
          </>
        ) : (
          <Button
            className="animated-profile-btn-v2"
            block
            type="default"
            onClick={onCreate}
          >
            <span className="default-layer">
              {intl.formatMessage({
                id: "pages.datasource.create",
                defaultMessage: "创建数据源",
              })}
            </span>

            <span className="hover-layer">
              <span className="hover-label">
                {intl.formatMessage({
                  id: "pages.datasource.create",
                  defaultMessage: "创建数据源",
                })}
              </span>
              <span className="hover-icon">
                <PlusOutlined />
              </span>
            </span>
          </Button>
        )}
      </div>
    </div>
  );
};

export default EmptyState;
