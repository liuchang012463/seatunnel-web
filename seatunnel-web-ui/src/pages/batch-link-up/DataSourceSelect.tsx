import { SendOutlined } from '@ant-design/icons';
import { Select } from 'antd';
import React from 'react';
import { DATA_SOURCE_REGISTRY } from '../data-source/dataSourceRegistry';
import DatabaseIcons from '../data-source/icon/DatabaseIcons';
import './index.less';

export interface DataSourceType {
  value: string;
  label: React.ReactNode;
  rawLabel?: string;
  icon?: React.ReactNode;
  connectorType?: string;
  pluginName?: string;
}

const toOption = (item: (typeof DATA_SOURCE_REGISTRY)[number]): DataSourceType => ({
  value: item.dbType,
  connectorType: item.connectorType,
  pluginName: item.pluginName,
  rawLabel: item.label,
  label: (
    <div style={{ display: 'flex', alignItems: 'center' }}>
      <DatabaseIcons dbType={item.dbType} width="24px" height="24px" />
      <span style={{ marginLeft: 8 }}>{item.label}</span>
    </div>
  ),
});

const taskRegistry = () =>
  DATA_SOURCE_REGISTRY.filter((item) => item.taskSelector);

export const generateSourceDataSourceOptions = (): DataSourceType[] =>
  taskRegistry().filter((item) => item.source).map(toOption);

/** Sink 与整库同步只展示支持写入的数据源。 */
export const generateDataSourceOptions = (): DataSourceType[] =>
  taskRegistry().filter((item) => item.sink).map(toOption);

export const generateCDCDataSourceOptions = (): DataSourceType[] => [
  {
    value: 'MYSQL',
    connectorType: 'Jdbc',
    pluginName: 'MySQL-CDC',
    rawLabel: 'MySQL-CDC',
    label: (
      <div style={{ display: 'flex', alignItems: 'center' }}>
        <DatabaseIcons dbType="MYSQL" width="24px" height="24px" />
        <span style={{ marginLeft: 8 }}>MySQL-CDC</span>
      </div>
    ),
  },
  {
    value: 'POSTGRE_SQL',
    connectorType: 'Postgres-CDC',
    pluginName: 'PostgreSQL-CDC',
    rawLabel: 'PostgreSQL-CDC',
    label: (
      <div style={{ display: 'flex', alignItems: 'center' }}>
        <DatabaseIcons dbType="POSTGRE_SQL" width="24px" height="24px" />
        <span style={{ marginLeft: 8 }}>PostgreSQL-CDC</span>
      </div>
    ),
  },
];

export const generateRealtimeSourceOptions = (): DataSourceType[] => [
  ...generateCDCDataSourceOptions(),
  ...taskRegistry().filter((item) => item.realtime && item.source).map(toOption),
];

interface DataSourceSelectProps {
  value: any;
  onChange: (value: string, option: any) => void;
  placeholder: string;
  prefix: string;
  dataSourceOptions: DataSourceType[];
  width?: string;
}

export const DataSourceSelect: React.FC<DataSourceSelectProps> = ({
  value,
  onChange,
  placeholder,
  prefix,
  dataSourceOptions,
  width = '42%',
}) => (
  <Select
    showSearch
    className="custom-ant-select-selector"
    placeholder={placeholder}
    value={value?.dbType}
    optionFilterProp="rawLabel"
    onChange={onChange}
    suffixIcon={<SendOutlined />}
    style={{ width, borderRadius: 24 }}
    prefix={<span style={{ fontSize: 12, fontWeight: 500 }}>{prefix}</span>}
    filterOption={(input, option) =>
      String(option?.rawLabel || '')
        .toLowerCase()
        .includes(input.toLowerCase())
    }
    options={dataSourceOptions}
  />
);

export default DataSourceSelect;
