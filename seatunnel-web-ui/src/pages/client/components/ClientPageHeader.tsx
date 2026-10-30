import React from "react";
import { Button } from "antd";
import { PlusOutlined } from "@ant-design/icons";
import { BLUE } from "../constants";

interface Props {
  onAdd: () => void;
}

const ClientPageHeader: React.FC<Props> = ({ onAdd }) => {
  return (
    <div className="mb-3 flex items-center justify-end gap-4">
      <Button
        type="primary"
        icon={<PlusOutlined />}
        onClick={onAdd}
        className="task-list-page-header__create-button"
        style={{ background: BLUE, borderColor: BLUE }}
      >
        新建 Client
      </Button>
    </div>
  );
};

export default ClientPageHeader;
