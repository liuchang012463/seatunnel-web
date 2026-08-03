import { useIntl } from "@umijs/max";
import React, { useEffect, useRef, useState } from "react";

interface SearchBarProps {
  value: string;
  onChange: (value: string) => void;
}

const SearchBar: React.FC<SearchBarProps> = ({ value, onChange }) => {
  const intl = useIntl();
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
      {/* <Input
        size="large"
        allowClear
        value={value}
        onChange={(event) => onChange(event.target.value)}
        placeholder={intl.formatMessage({
          id: 'pages.datasource.search.placeholder',
          defaultMessage: 'Search by datasource name...',
        })}
        className="datasource-search-input"
      /> */}
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
    </div>
  );
};

export default SearchBar;
