import { SendOutlined } from "@ant-design/icons";
import { Select } from "antd";
import MysqlIcon from "../data-source/icon/MysqlIcon";
import OracleIcon from "../data-source/icon/OracleIcon";
import PostgreSQL from "../data-source/icon/PsSqlIcon";
import DorisIcon from "../data-source/icon/DorisIcon";
import KingBaseIcon from "../data-source/icon/KingBaseIcon";
import DaMengIcon from "../data-source/icon/DamengIcon";
import DatabaseIcons from "../data-source/icon/DatabaseIcons";
import "./index.less";
// 类型定义
export interface DataSourceType {
  value: string;
  label: React.ReactNode;
  icon?: React.ReactNode;
  connectorType?: string;
  pluginName?: string;
}
const generateBidirectionalDataSourceOptions = (): DataSourceType[] => [
  {
    value: "JDBC",
    connectorType: "Jdbc",
    pluginName: "JDBC-JDBC",
    label: (
      <div style={{ display: "flex", alignItems: "center" }}>
        <DatabaseIcons dbType="JDBC" width="24px" height="24px" />
        <span style={{ marginLeft: 8 }}>JDBC</span>
      </div>
    ),
  },
  {
    value: "MYSQL",
    connectorType: "Jdbc",
    pluginName: "JDBC-MYSQL",
    label: (
      <div style={{ display: "flex", alignItems: "center" }}>
        <MysqlIcon height="24px" width="24px" />
        <span style={{ marginLeft: 8 }}>MYSQL</span>
      </div>
    ),
  },
  {
    value: "ORACLE",
    connectorType: "Jdbc",
    pluginName: "JDBC-ORACLE",
    label: (
      <div style={{ display: "flex", alignItems: "center" }}>
        <OracleIcon />
        <span style={{ marginLeft: 8 }}>ORACLE</span>
      </div>
    ),
  },
  {
    value: "POSTGRE_SQL",
    connectorType: "Jdbc",
    pluginName: "JDBC-POSTGRESQL",
    label: (
      <div style={{ display: "flex", alignItems: "center" }}>
        <PostgreSQL />
        <span style={{ marginLeft: 8 }}>PostGreSQL</span>
      </div>
    ),
  },
  {
    value: "KAFKA",
    connectorType: "Kafka",
    pluginName: "KAFKA",
    label: (
      <div style={{ display: "flex", alignItems: "center" }}>
        <DatabaseIcons dbType="KAFKA" width="24px" height="24px" />
        <span style={{ marginLeft: 8 }}>Kafka</span>
      </div>
    ),
  },
  {
    value: "DORIS",
    connectorType: "Doris",
    pluginName: "DORIS",
    label: (
      <div style={{ display: "flex", alignItems: "center" }}>
        <DorisIcon />
        <span style={{ marginLeft: 8 }}>Doris</span>
      </div>
    ),
  },
  {
    value: "KINGBASE",
    connectorType: "Jdbc",
    pluginName: "JDBC-KINGBASE",
    label: (
      <div style={{ display: "flex", alignItems: "center" }}>
        <KingBaseIcon />
        <span style={{ marginLeft: 8 }}>KINGBASE</span>
      </div>
    ),
  },
  {
    value: "DAMENG",
    connectorType: "Jdbc",
    pluginName: "JDBC-DAMENG",
    label: (
      <div style={{ display: "flex", alignItems: "center" }}>
        <DaMengIcon />
        <span style={{ marginLeft: 8 }}>DAMENG</span>
      </div>
    ),
  },
];

const generateHttpSourceOption = (): DataSourceType => ({
  value: "HTTP",
  connectorType: "Http",
  pluginName: "HTTP",
  label: (
    <div style={{ display: "flex", alignItems: "center" }}>
      <DatabaseIcons dbType="HTTP" width="24px" height="24px" />
      <span style={{ marginLeft: 8 }}>HTTP / API</span>
    </div>
  ),
});

export const generateSourceDataSourceOptions = (): DataSourceType[] => [
  ...generateBidirectionalDataSourceOptions(),
  generateHttpSourceOption(),
];

/** Sink 与整库同步只展示支持写入的数据源。 */
export const generateDataSourceOptions = (): DataSourceType[] =>
  generateBidirectionalDataSourceOptions();

export const generateCDCDataSourceOptions = (): DataSourceType[] => [
  {
    value: "MYSQL",
    connectorType: "Jdbc",
    pluginName: "MySQL-CDC",
    label: (
      <div style={{ display: "flex", alignItems: "center" }}>
        <MysqlIcon height="24px" width="24px" />
        <span style={{ marginLeft: 8 }}>MySQL-CDC</span>
      </div>
    ),
  },
  {
    value: "POSTGRE_SQL",
    connectorType: "Postgres-CDC",
    pluginName: "PostgreSQL-CDC",
    label: (
      <div style={{ display: "flex", alignItems: "center" }}>
        <PostgreSQL />
        <span style={{ marginLeft: 8 }}>PostgreSQL-CDC</span>
      </div>
    ),
  },
];

/**
 * 实时任务既支持数据库 CDC，也支持 Kafka 这类原生流式 Source。
 * 保留 CDC 选项生成器供纯 CDC 场景使用，避免调用方把“实时来源”等同于“CDC 来源”。
 */
export const generateRealtimeSourceOptions = (): DataSourceType[] => {
  const kafkaOption = generateBidirectionalDataSourceOptions().find(
    (option) => option.value === "KAFKA",
  );

  return [
    ...generateCDCDataSourceOptions(),
    ...(kafkaOption ? [kafkaOption] : []),
    generateHttpSourceOption(),
  ];
};

// 数据源选择器组件
interface DataSourceSelectProps {
  value: any;
  onChange: (value: string, option: any) => void;
  placeholder: string;
  prefix: string;
  dataSourceOptions: any[],
  width?: string;
}

export const DataSourceSelect: React.FC<DataSourceSelectProps> = ({
  value,
  onChange,
  placeholder,
  prefix,
  dataSourceOptions,
  width = "42%",
}) => {
  return (
    <Select
      showSearch
      className="custom-ant-select-selector"
      placeholder={placeholder}
      value={value?.dbType}
      optionFilterProp="label"
      onChange={onChange}
      suffixIcon={<SendOutlined />}
      style={{ width: width, borderRadius: 24 }}
      prefix={<span style={{ fontSize: 12,fontWeight: 500 }}>{prefix}</span>}
      filterOption={(input, option) => {
        const labelText =
          typeof option?.label === "string" ? option.label : "MYSQL";
        return labelText.toLowerCase().includes(input.toLowerCase());
      }}
      options={dataSourceOptions}
    />
  );
};

export default DataSourceSelect;
