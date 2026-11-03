import { LeftOutlined, RightOutlined } from "@ant-design/icons";
import { Pagination, PaginationProps } from "antd";
import React from "react";

interface CustomPaginationProps {
  total: number;
  current?: number;
  pageSize?: number;
  onChange?: (page: number, pageSize: number) => void;
}

const CustomPagination: React.FC<CustomPaginationProps> = ({
  total,
  current,
  pageSize,
  onChange,
}) => {
  const itemRender: PaginationProps["itemRender"] = (
    _page,
    type,
    originalElement
  ) => {
    if (type === "prev") {
      return <LeftOutlined />;
    }
    if (type === "next") {
      return <RightOutlined />;
    }

    return originalElement;
  };

  return (
    <div className="custom-pagination flex items-center gap-4">
      <span className="custom-pagination__total">共 {total} 条</span>

      <Pagination
        size="small"
        total={total}
        current={current}
        pageSize={pageSize}
        showSizeChanger
        pageSizeOptions={[10, 20, 50]}
        itemRender={itemRender}
        onChange={onChange}
      />
    </div>
  );
};

export default CustomPagination;
