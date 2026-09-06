import { SearchOutlined } from '@ant-design/icons';
import { Button, Select } from 'antd';
import React from 'react';
import { DATA_SOURCE_STATUS_OPTIONS } from '../constants';
import { DATA_SOURCE_CATEGORIES } from '../dataSourceRegistry';
import type { DataSourceEntityId, DataSourceUnitOption } from '../types';

interface SearchBarProps {
  value: string;
  onChange: (value: string) => void;
  unitOptions: DataSourceUnitOption[];
  selectedUnit?: DataSourceEntityId;
  onUnitChange: (value?: string) => void;
  selectedStatus?: string;
  onStatusChange: (value?: string) => void;
  selectedCategory: string;
  onCategoryChange: (value: string) => void;
  onSearch?: () => void;
  onReset?: () => void;
}

const SearchBar: React.FC<SearchBarProps> = ({
  value,
  onChange,
  unitOptions,
  selectedUnit,
  onUnitChange,
  selectedStatus,
  onStatusChange,
  selectedCategory,
  onCategoryChange,
  onSearch,
  onReset,
}) => (
  <div className="datasource-search-bar">
    <div className="datasource-search-filter-row">
      <label className="datasource-filter-item datasource-search-control">
        <span className="datasource-filter-label">数据源名称：</span>
        <span className="datasource-search-control-field">
          <SearchOutlined className="datasource-search-control-icon" />
          <input
            className="datasource-search-control-input"
            placeholder="请输入"
            type="text"
            value={value}
            onChange={(event) => onChange(event.target.value)}
          />
        </span>
      </label>

      <label className="datasource-filter-item">
        <span className="datasource-filter-label">数据源单位：</span>
        <Select
          allowClear
          showSearch
          value={selectedUnit === undefined ? undefined : String(selectedUnit)}
          options={unitOptions.map((unit) => ({ label: unit.unitName, value: String(unit.id) }))}
          placeholder="请选择"
          className="datasource-filter-select"
          optionFilterProp="label"
          onChange={onUnitChange}
        />
      </label>

      <label className="datasource-filter-item">
        <span className="datasource-filter-label">生命周期状态：</span>
        <Select
          allowClear
          value={selectedStatus}
          options={DATA_SOURCE_STATUS_OPTIONS}
          placeholder="请选择"
          className="datasource-filter-select"
          onChange={onStatusChange}
        />
      </label>

      <label className="datasource-filter-item">
        <span className="datasource-filter-label">数据源类型：</span>
        <Select
          allowClear={selectedCategory !== 'ALL'}
          value={selectedCategory === 'ALL' ? undefined : selectedCategory}
          options={DATA_SOURCE_CATEGORIES.map((category) => ({ label: category.label, value: category.key }))}
          placeholder="请选择"
          className="datasource-filter-select"
          onChange={(value) => onCategoryChange(value || 'ALL')}
        />
      </label>

      <div className="datasource-search-buttons">
        <Button type="primary" onClick={onSearch}>查询</Button>
        <Button onClick={onReset}>重置</Button>
      </div>
    </div>
  </div>
);

export default SearchBar;
