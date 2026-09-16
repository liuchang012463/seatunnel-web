import { LeftOutlined, RightOutlined } from "@ant-design/icons";
import { Button, Pagination, PaginationProps } from "antd";
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
    page,
    type,
    originalElement
  ) => {
    if (type === "prev") {
      return (
        <Button
          style={{ marginRight: 4 }}
          size="small"
          icon={
            <LeftOutlined
              style={{
                bottom: 2,
                position: "relative",
                paddingBottom: 5,
                fontSize: 7,
                color: "rgba(185,185,185,1)",
              }}
            />
          }
        />
      );
    }
    if (type === "next") {
      return (
        <Button
          style={{ marginLeft: 4, marginRight: 4 }}
          size="small"
          icon={
            <RightOutlined
              style={{
                bottom: 2,
                position: "relative",
                fontSize: 7,
                color: "rgba(185,185,185,1)",
              }}
            />
          }
        />
      );
    }

    if (type === "page" && React.isValidElement(originalElement)) {
      const params = new URLSearchParams(window.location.search);
      params.set("current", String(page));
      if (pageSize) {
        params.set("pageSize", String(pageSize));
      }

      return React.cloneElement(originalElement, {
        href: `${window.location.pathname}?${params.toString()}`,
      });
    }

    return originalElement;
  };

  return (
    <div className="flex items-center gap-4 text-slate-500">
      <span>共 {total} 条</span>

      <Pagination
        size="small"
        total={total}
        current={current}
        pageSize={pageSize}
        showSizeChanger
        pageSizeOptions={[10, 20, 50]}
        itemRender={itemRender}
        onChange={onChange}
        onShowSizeChange={onChange}
      />
    </div>
  );
};

export default CustomPagination;
