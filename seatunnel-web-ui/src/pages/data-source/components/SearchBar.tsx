import { SearchOutlined } from '@ant-design/icons';
import { Input, Select } from 'antd';
import React from 'react';
import { DATA_SOURCE_STATUS_OPTIONS } from '../constants';
import type { BusinessSystemOption, DataSourceEntityId, DataSourceUnitOption } from '../types';

interface SearchBarProps {
  value: string;
  onChange: (value: string) => void;
  unitOptions: DataSourceUnitOption[];
  selectedUnit?: DataSourceEntityId;
  onUnitChange: (value?: string) => void;
  businessSystemOptions: BusinessSystemOption[];
  selectedBusinessSystem?: DataSourceEntityId;
  onBusinessSystemChange: (value?: string) => void;
  selectedStatus?: string;
  onStatusChange: (value?: string) => void;
}

const SearchBar: React.FC<SearchBarProps> = ({
  value,
  onChange,
  unitOptions,
  selectedUnit,
  onUnitChange,
  businessSystemOptions,
  selectedBusinessSystem,
  onBusinessSystemChange,
  selectedStatus,
  onStatusChange,
}) => {
  return (
    <div className="datasource-search-bar">
      <div className="datasource-search-filter-row">
        <Input
          allowClear
          prefix={<SearchOutlined className="datasource-search-control-icon" />}
          placeholder="根据数据源名称搜索"
          value={value}
          className="datasource-search-control"
          onChange={(e) => onChange(e.target.value)}
        />

        <Select
          allowClear
          showSearch
          value={selectedUnit === undefined ? undefined : String(selectedUnit)}
          options={unitOptions.map((unit) => ({
            label: unit.unitName,
            value: String(unit.id),
          }))}
          placeholder="按数据源单位筛选"
          className="datasource-filter-select"
          optionFilterProp="label"
          onChange={onUnitChange}
        />

        <Select
          allowClear
          showSearch
          value={selectedBusinessSystem === undefined ? undefined : String(selectedBusinessSystem)}
          options={businessSystemOptions.map((system) => ({
            label: system.systemName,
            value: String(system.id),
          }))}
          placeholder="按业务系统筛选"
          className="datasource-filter-select"
          optionFilterProp="label"
          disabled={selectedUnit === undefined}
          onChange={onBusinessSystemChange}
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
