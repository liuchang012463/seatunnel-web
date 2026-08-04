import { Select } from "antd";
import React, { useEffect, useRef, useState } from "react";
import { DATA_SOURCE_STATUS_OPTIONS } from "../constants";

interface SearchBarProps {
  value: string;
  onChange: (value: string) => void;
  unitOptions: string[];
  selectedUnit?: string;
  onUnitChange: (value?: string) => void;
  selectedStatus?: string;
  onStatusChange: (value?: string) => void;
}

const SearchBar: React.FC<SearchBarProps> = ({
  value,
  onChange,
  unitOptions,
  selectedUnit,
  onUnitChange,
  selectedStatus,
  onStatusChange,
}) => {
  const [open, setOpen] = useState(false);
    const wrapperRef = useRef<HTMLDivElement | null>(null);
  
    useEffect(() => {
      const handleClickOutside = (event: MouseEvent) => {
        if (!wrapperRef.current) return;
        if (!wrapperRef.current.contains(event.target as Node)) {
          setOpen(false);
        }
      };
  
      document.addEventListener("mousedown", handleClickOutside);
      return () => {
        document.removeEventListener("mousedown", handleClickOutside);
      };
    }, []);
  return (
    <div className="datasource-search-bar" ref={wrapperRef}>
      <div className="datasource-search-filter-row">
        <div
          className={`datasource-search-control${open ? " is-open" : ""}`}
        >
          <div className="relative rounded-full">
            <input
              className="datasource-search-control-input"
              placeholder="根据数据源名称搜索"
              type="text"
              value={value}
              onFocus={() => setOpen(true)}
              onChange={(e) => {
                onChange(e.target.value);
                if (!open) setOpen(true);
              }}
              style={{
                border: "none",
                boxShadow: "none",
                background: "transparent",
              }}
            />

            <svg
              xmlns="http://www.w3.org/2000/svg"
              width="24"
              height="24"
              viewBox="0 0 24 24"
              fill="none"
              stroke="currentColor"
              strokeWidth="2"
              strokeLinecap="round"
              strokeLinejoin="round"
              className="datasource-search-control-icon"
            >
              <circle cx="11" cy="11" r="8" />
              <path d="m21 21-4.3-4.3" />
            </svg>
          </div>
        </div>

        <Select
          allowClear
          showSearch
          value={selectedUnit}
          options={unitOptions.map((unit) => ({ label: unit, value: unit }))}
          placeholder="按数据源单位筛选"
          className="datasource-filter-select"
          optionFilterProp="label"
          onChange={onUnitChange}
        />

        <Select
          allowClear
          value={selectedStatus}
          options={DATA_SOURCE_STATUS_OPTIONS}
          placeholder="按生命周期状态筛选"
          className="datasource-filter-select"
          onChange={onStatusChange}
        />
      </div>
    </div>
  );
};

export default SearchBar;
