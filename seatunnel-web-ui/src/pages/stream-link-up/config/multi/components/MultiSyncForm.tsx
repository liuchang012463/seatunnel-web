import React from "react";
import { Col, Form, Input, Radio, Row, Select } from "antd";
import { supportsSchemaScope } from "@/pages/data-source/dataSourceRegistry";
import type { DbTypeValue } from "../types";

const { TextArea } = Input;

interface Props {
  sourceOption: any[];
  targetOption: any[];
  matchMode: string;
  tableKeyword: string;
  sourceType?: DbTypeValue;
  schemaOptions?: string[];
  selectedSchema?: string;
  targetSchemaScoped: boolean;
  targetDatabases: { options: { label: string; value: string }[]; loading: boolean };
  targetSchemas: { options: { label: string; value: string }[]; loading: boolean };
  onSourceIdChange: (value: string) => void;
  onSchemaChange?: (value: string) => void;
  onMatchModeChange: (value: string) => void;
  onKeywordChange: (value: string) => void;
}

const formItemClass = "[&_.ant-form-item-label>label]:text-[14px] [&_.ant-form-item-label>label]:text-slate-600";

const MultiSyncForm: React.FC<Props> = ({
  sourceOption,
  targetOption,
  matchMode,
  tableKeyword,
  sourceType,
  schemaOptions = [],
  selectedSchema,
  targetSchemaScoped,
  targetDatabases,
  targetSchemas,
  onSourceIdChange,
  onSchemaChange,
  onMatchModeChange,
  onKeywordChange,
}) => {
  const isPostgreSqlCdc = sourceType?.pluginName?.toUpperCase() === "POSTGRESQL-CDC";
  const schemaScoped = supportsSchemaScope(sourceType?.dbType);
  const showSchemaSelector = schemaScoped && matchMode === "4";
  const sinkId = Form.useWatch("sinkId");
  const targetDatabase = Form.useWatch("targetDatabase");

  return (
    <div className="rounded-2xl ">
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

            {showSchemaSelector && (
              <Form.Item label="来源 Schema" className={formItemClass}>
                <Select
                  size="middle"
                  placeholder="请选择 Schema"
                  showSearch
                  value={selectedSchema || undefined}
                  options={schemaOptions.map((schema) => ({
                    label: schema,
                    value: schema,
                  }))}
                  onChange={onSchemaChange}
                  className="st-round-select"
                  notFoundContent="当前数据源没有可选 Schema"
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
                className="st-round-select"
              />
            </Form.Item>
            {targetSchemaScoped && (
              <>
                <Form.Item label="目标数据库" name="targetDatabase" className={formItemClass}>
                  <Select
                    className="st-round-select"
                    placeholder="请选择目标数据库"
                    options={targetDatabases.options}
                    loading={targetDatabases.loading}
                    disabled={!sinkId || targetDatabases.loading}
                    showSearch
                    optionFilterProp="label"
                    allowClear
                  />
                </Form.Item>
                <Form.Item label="目标 Schema" name="targetSchemaName" className={formItemClass}>
                  <Select
                    className="st-round-select"
                    placeholder={targetDatabase ? "请选择目标 Schema" : "请先选择目标数据库"}
                    options={targetSchemas.options}
                    loading={targetSchemas.loading}
                    disabled={!targetDatabase || targetSchemas.loading}
                    notFoundContent="当前数据库没有可选 Schema"
                    showSearch
                    optionFilterProp="label"
                    allowClear
                  />
                </Form.Item>
              </>
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
            {!isPostgreSqlCdc && <Radio value="2">正则匹配</Radio>}
            <Radio value="3">精准匹配</Radio>
            <Radio value="4">
              {isPostgreSqlCdc ? "整库同步（发布时扫描）" : "整库同步"}
            </Radio>
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
    </div>
  );
};

export default MultiSyncForm;
