import { Col, Form, Input, InputNumber, Row, Select, Switch } from "antd";
import React from "react";

import {
  DATA_SAVE_MODE_OPTIONS,
  FIELD_IDE_OPTIONS,
  SCHEMA_SAVE_MODE_OPTIONS,
  validateServerIdRange,
} from "../config";
import type { DbTypeValue } from "../types";

type MultiWorkflowParamConfigProps = {
  sourceType?: DbTypeValue;
};

const MultiWorkflowParamConfig: React.FC<MultiWorkflowParamConfigProps> = ({
  sourceType,
}) => {
  const pluginName = sourceType?.pluginName?.toUpperCase();
  const isMySqlCdc = pluginName === "MYSQL-CDC";
  const isPostgreSqlCdc = pluginName === "POSTGRESQL-CDC";

  return (
    <div className="mt-6 rounded-2xl bg-white" style={{ marginBottom: 40 }}>
      <div className="mb-5 text-base font-semibold text-slate-800">
        参数设置
      </div>
      <Row gutter={24}>
        <Col span={12}>
          <Form.Item
            label="每次拉取行数（Fetch Size）"
            name="fetchSize"
            rules={[{ required: true, message: "请输入每次拉取行数" }]}
          >
            <InputNumber min={0} style={{ width: "100%" }} placeholder="0" />
          </Form.Item>

          {isPostgreSqlCdc && <>
            <Form.Item
              label="slot.name"
              name="slotName"
              extra="PostgreSQL 逻辑复制槽；需由 DBA 预先创建，并且不得被其他任务占用。"
              rules={[{ required: true, message: "请输入 slot.name" }]}
            >
              <Input placeholder="例如：seatunnel_orders" allowClear />
            </Form.Item>
            <Form.Item
              label="publicationName"
              name="publicationName"
              extra="PostgreSQL publication；需由 DBA 预先创建并覆盖所选表。"
              rules={[{ required: true, message: "请输入 publicationName" }]}
            >
              <Input placeholder="例如：seatunnel_orders_pub" allowClear />
            </Form.Item>
            <Form.Item
              label="启动模式"
              name="startupMode"
              rules={[{ required: true, message: "请选择启动模式" }]}
            >
              <Select
                options={[
                  { label: "initial（快照 + 增量）", value: "initial" },
                  { label: "earliest（最早可用位点）", value: "earliest" },
                  { label: "latest（最新位点）", value: "latest" },
                ]}
              />
            </Form.Item>
          </>}

          <Form.Item
            label="读取分片大小（Split Size）"
            name="splitSize"
            rules={[{ required: true, message: "请输入读取分片大小" }]}
          >
            <InputNumber min={1} style={{ width: "100%" }} placeholder="8096" />
          </Form.Item>
          {isMySqlCdc && <Form.Item
            label="server-id"
            name="serverId"
            extra="支持单个 ID 或连续区间，例如 5400 或 5400-5408；同一个 MySQL 集群内必须唯一。"
            rules={[
              {
                validator: async (_, value) => {
                  if (!value) {
                    return Promise.resolve();
                  }

                  const result = validateServerIdRange(value);
                  if (!result.valid) {
                    return Promise.reject(new Error(result.message));
                  }

                  return Promise.resolve();
                },
              },
            ]}
          >
            <Input placeholder="例如：5400 或 5400-5408" allowClear />
          </Form.Item>}
        </Col>

        <Col span={12}>
          <Row gutter={[16, 4]}>
            <Col span={12}>
              <Form.Item
                label="Schema 保存模式"
                name="schemaSaveMode"
                rules={[{ required: true, message: "请选择 Schema 保存模式" }]}
              >
                <Select
                  placeholder="请选择"
                  options={SCHEMA_SAVE_MODE_OPTIONS}
                />
              </Form.Item>
            </Col>

            <Col span={12}>
              <Form.Item
                label="数据保存模式"
                name="dataSaveMode"
                rules={[{ required: true, message: "请选择数据保存模式" }]}
              >
                <Select placeholder="请选择" options={DATA_SAVE_MODE_OPTIONS} />
              </Form.Item>
            </Col>

            <Col span={12}>
              <Form.Item
                label="启用 Upsert"
                name="enableUpsert"
                valuePropName="checked"
              >
                <Switch />
              </Form.Item>
            </Col>
          </Row>

          <Row gutter={[16, 4]}>
            <Col span={12}>
              <Form.Item
                label="批次大小"
                name="batchSize"
                rules={[{ required: true, message: "请输入批次大小" }]}
              >
                <InputNumber
                  min={1}
                  placeholder="默认 10000"
                  style={{ width: "100%" }}
                />
              </Form.Item>
            </Col>

            <Col span={12}>
              <Form.Item label="字段标识格式" name="fieldIde">
                <Select placeholder="请选择" options={FIELD_IDE_OPTIONS} />
              </Form.Item>
            </Col>
          </Row>
        </Col>
      </Row>
    </div>
  );
};

export default MultiWorkflowParamConfig;
