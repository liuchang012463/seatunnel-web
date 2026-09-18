import React from "react";
import { Col, Form, Input, Radio, Row, Select } from "antd";
import {
  supportsDatabaseScope,
  supportsSchemaScope,
} from "@/pages/data-source/dataSourceRegistry";

const { TextArea } = Input;

interface Props {
  form: any;
  sourceOption: any[];
  targetOption: any[];
  sourceType?: any;
  targetType?: any;
  sourceDatabase?: string;
  targetDatabase?: string;
  sourceSchemaName?: string;
  targetSchemaName?: string;
  sourceDatabaseOptions?: any[];
  targetDatabaseOptions?: any[];
  sourceSchemaOptions?: any[];
  targetSchemaOptions?: any[];
  databaseLoading?: boolean;
  schemaLoading?: boolean;
  matchMode: string;
  tableKeyword: string;
  onSourceIdChange: (value: string) => void;
  onTargetIdChange: (value: string, option?: any) => void;
  onSourceDatabaseChange: (value: string, option?: any) => void;
  onTargetDatabaseChange: (value: string, option?: any) => void;
  onSourceSchemaChange: (value: string, option?: any) => void;
  onTargetSchemaChange?: (value: string, option?: any) => void;
  onMatchModeChange: (value: string) => void;
  onKeywordChange: (value: string) => void;
}

const formItemClass = "[&_.ant-form-item-label>label]:text-[13px] [&_.ant-form-item-label>label]:text-slate-600";

const MultiSyncForm: React.FC<Props> = ({
  form,
  sourceOption,
  targetOption,
  sourceType,
  targetType,
  sourceDatabase,
  targetDatabase,
  sourceSchemaName,
  targetSchemaName,
  sourceDatabaseOptions,
  targetDatabaseOptions,
  sourceSchemaOptions,
  targetSchemaOptions,
  databaseLoading,
  schemaLoading,
  matchMode,
  tableKeyword,
  onSourceIdChange,
  onTargetIdChange,
  onSourceDatabaseChange,
  onTargetDatabaseChange,
  onSourceSchemaChange,
  onTargetSchemaChange,
  onMatchModeChange,
  onKeywordChange,
}) => {
  const sourceDbType = String(sourceType?.dbType || "").toUpperCase();
  const targetDbType = String(targetType?.dbType || "").toUpperCase();
  const sourceDatabaseScoped = supportsDatabaseScope(sourceDbType);
  const targetDatabaseScoped = supportsDatabaseScope(targetDbType);
  const sourceSchemaScoped = supportsSchemaScope(sourceDbType);
  const targetSchemaScoped = supportsSchemaScope(targetDbType);

  return (
    <div className="rounded-2xl ">
      <Form
        form={form}
        initialValues={{ matchMode: "1" }}
        layout="vertical"
      >
        <Row gutter={20}>
          <Col span={12}>
            <Form.Item
              label="来源数据源"
              name="sourceId"
              rules={[{ required: true, message: "请选择来源数据源" }]}
              className={formItemClass}
            >
              <Select
                size="middle"
                placeholder="请选择来源库"
                showSearch
                options={sourceOption}
                onChange={onSourceIdChange}
                className="st-round-select"
              />
            </Form.Item>
            {sourceDatabaseScoped && (
              <Form.Item
                label={sourceDbType === 'DORIS' ? 'Doris 来源数据库' : '来源数据库'}
                name="sourceDatabase"
                rules={[{
                  required: true,
                  message: sourceDbType === 'DORIS' ? "请选择 Doris 来源数据库" : "请选择来源数据库",
                }]}
                className={`${formItemClass} mb-0`}
              >
                <Select
                  className="st-round-select"
                  placeholder={sourceDbType === 'DORIS' ? '请选择 Doris 来源数据库' : '请选择来源数据库'}
                  value={sourceDatabase || undefined}
                  options={sourceDatabaseOptions}
                  loading={databaseLoading}
                  showSearch
                  optionFilterProp="label"
                  onChange={onSourceDatabaseChange}
                />
              </Form.Item>
            )}
            {sourceSchemaScoped && (
              <Form.Item
                label="来源 Schema"
                name="sourceSchemaName"
                rules={sourceSchemaOptions?.length
                  ? [{ required: true, message: "请选择来源 Schema" }]
                  : []}
                className={`${formItemClass} mb-0`}
              >
                <Select
                  className="st-round-select"
                  placeholder="请选择来源 Schema"
                  value={sourceSchemaName || undefined}
                  options={sourceSchemaOptions}
                  loading={schemaLoading}
                  disabled={!sourceDatabase}
                  showSearch
                  optionFilterProp="label"
                  onChange={onSourceSchemaChange}
                />
              </Form.Item>
            )}
          </Col>

          <Col span={12}>
            <Form.Item
              label="目标数据源"
              name="sinkId"
              rules={[{ required: true, message: "请选择目标数据源" }]}
              className={formItemClass}
            >
              <Select
                size="middle"
                placeholder="请选择目标库"
                showSearch
                options={targetOption}
                onChange={onTargetIdChange}
                className="st-round-select"
              />
            </Form.Item>
            {targetDatabaseScoped && (
              <Form.Item
                label={targetDbType === 'DORIS' ? 'Doris 目标数据库' : '目标数据库'}
                name="targetDatabase"
                rules={[{
                  required: true,
                  message: targetDbType === 'DORIS' ? "请选择 Doris 目标数据库" : "请选择目标数据库",
                }]}
                className={`${formItemClass} mb-0`}
              >
                <Select
                  className="st-round-select"
                  placeholder={targetDbType === 'DORIS' ? '请选择 Doris 目标数据库' : '请选择目标数据库'}
                  value={targetDatabase || undefined}
                  options={targetDatabaseOptions}
                  loading={databaseLoading}
                  showSearch
                  optionFilterProp="label"
                  onChange={onTargetDatabaseChange}
                />
              </Form.Item>
            )}
            {targetSchemaScoped && (
              <Form.Item
                label="目标 Schema"
                name="targetSchemaName"
                rules={targetSchemaOptions?.length
                  ? [{ required: true, message: "请选择目标 Schema" }]
                  : []}
                className={`${formItemClass} mb-0`}
              >
                <Select
                  className="st-round-select"
                  placeholder="请选择目标 Schema"
                  value={targetSchemaName || undefined}
                  options={targetSchemaOptions}
                  loading={schemaLoading}
                  disabled={!targetDatabase}
                  showSearch
                  optionFilterProp="label"
                  onChange={onTargetSchemaChange}
                />
              </Form.Item>
            )}
          </Col>
        </Row>

        <Form.Item
          name="matchMode"
          label="表名匹配方式"
          className={`${formItemClass} mb-3`}
        >
          <Radio.Group
            value={matchMode}
            onChange={(e) => onMatchModeChange(e.target.value)}
            className="st-match-radio"
          >
            <Radio value="1">自定义</Radio>
            <Radio value="2">正则匹配</Radio>
            <Radio value="3">精准匹配</Radio>
            <Radio value="4">整库同步</Radio>
          </Radio.Group>
        </Form.Item>

        {(matchMode === "2" || matchMode === "3") && (
          <Form.Item
            name="sourceTable"
            label={matchMode === "2" ? "正则表达式" : "表名关键字"}
            className={`${formItemClass} mb-0`}
          >
            <TextArea
              placeholder={
                matchMode === "2"
                  ? "请输入正则表达式，例如：ods_.*, ..*"
                  : "英文逗号隔开的表名"
              }
              rows={4}
              value={tableKeyword}
              onChange={(e) => onKeywordChange(e.target.value)}
              className="st-round-textarea"
            />
          </Form.Item>
        )}
      </Form>
    </div>
  );
};

export default MultiSyncForm;
