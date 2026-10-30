import React from "react";
import { PlusOutlined, SearchOutlined } from "@ant-design/icons";
import { Button, Input } from "antd";
import { MenuKey } from "../../types";
import { BLUE } from "../../constants/ui";

interface Props {
  activeMenu: MenuKey;
  keyword: string;
  onKeywordChange: (value: string) => void;
  onAdd: () => void;
}

const ConnectorContentHeader: React.FC<Props> = ({
  keyword,
  onKeywordChange,
  onAdd,
}) => {
  return (
    <div className="mb-5 flex flex-wrap items-center justify-between gap-3">
      <Input
        allowClear
        value={keyword}
        onChange={(e) => onKeywordChange(e.target.value)}
        prefix={<SearchOutlined />}
        placeholder="搜索 Connector、参数名称、说明、类型"
        className="h-10 w-[420px] max-w-full"
      />

      <Button
        type="primary"
        icon={<PlusOutlined />}
        onClick={onAdd}
        className="task-list-page-header__create-button"
        style={{
          background: BLUE,
          borderColor: BLUE,
        }}
      >
        新增 Connector 参数
      </Button>
    </div>
  );
};

export default ConnectorContentHeader;
