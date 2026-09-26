import React from "react";
import { Col, Form, Input, InputNumber, Row, Select, Switch } from "antd";
import type { DbTypeValue } from "../types";
import {
  DATA_SAVE_MODE_OPTIONS,
  FIELD_IDE_OPTIONS,
  SCHEMA_SAVE_MODE_OPTIONS,
} from "../config";

type MultiWorkflowParamConfigProps = {
  sourceType?: DbTypeValue;
  targetType?: DbTypeValue;
};

const MultiWorkflowParamConfig: React.FC<MultiWorkflowParamConfigProps> = ({
  sourceType,
  targetType,
}) => {
  const isJdbcSource = sourceType?.connectorType?.toLowerCase() === "jdbc";
  const isDorisTarget = targetType?.dbType?.toUpperCase() === "DORIS";

  return (
    <div className="mt-6 rounded-2xl bg-white" style={{ marginBottom: 40 }}>
      <div className="mb-5 text-base font-semibold text-slate-800">参数设置</div>

      <Row gutter={24}>
        <Col span={12}>
          <Form.Item
            label="每次拉取行数（Fetch Size）"
            name="fetchSize"
            rules={[{ required: true, message: "请输入每次拉取行数" }]}
          >
            <InputNumber min={0} style={{ width: "100%" }} placeholder="0" />
          </Form.Item>

          <Form.Item
            label="读取分片大小（Split Size）"
            name="splitSize"
            rules={[{ required: true, message: "请输入读取分片大小" }]}
          >
            <InputNumber min={1} style={{ width: "100%" }} placeholder="8096" />
          </Form.Item>

          {isJdbcSource && (
            <>
              <Form.Item label="分区列（Partition Column）" name="partitionColumn">
                <Input placeholder="可选；自定义 SQL 分片读取时填写" allowClear />
              </Form.Item>
              <Form.Item label="分区下界" name="partitionLowerBound">
                <Input placeholder="可选" allowClear />
              </Form.Item>
              <Form.Item label="分区上界" name="partitionUpperBound">
                <Input placeholder="可选" allowClear />
              </Form.Item>
              <Form.Item label="分区数（Partition Num）" name="partitionNum">
                <InputNumber min={1} style={{ width: "100%" }} placeholder="可选" />
              </Form.Item>
            </>
          )}
        </Col>

        <Col span={12}>
          <Row gutter={[16, 4]}>
            <Col span={12}>
              <Form.Item
                label="Schema 保存模式"
                name="schemaSaveMode"
                rules={[{ required: true, message: "请选择 Schema 保存模式" }]}
              >
                <Select placeholder="请选择" options={SCHEMA_SAVE_MODE_OPTIONS} />
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
              <Form.Item label="启用 Upsert" name="enableUpsert" valuePropName="checked">
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

          {isDorisTarget && (
            <>
              <Form.Item
                label="启用 Doris 2PC"
                name="dorisEnable2pc"
                valuePropName="checked"
              >
                <Switch />
              </Form.Item>
              <Form.Item
                label="Doris 2PC 稳定标签前缀"
                name="dorisLabelPrefix"
                dependencies={["dorisEnable2pc"]}
                extra="开启 2PC 时必填；请使用稳定的任务级值，重建配置时保持不变。"
                rules={[
                  ({ getFieldValue }) => ({
                    validator: async (_, value) => {
                      if (
                        getFieldValue("dorisEnable2pc") &&
                        !String(value || "").trim()
                      ) {
                        throw new Error("开启 Doris 2PC 时必须填写稳定标签前缀");
                      }
                    },
                  }),
                ]}
              >
                <Input placeholder="例如：orders_sync" allowClear />
              </Form.Item>
              <Form.Item
                label="Doris 表副本数"
                name="dorisReplicaCount"
                rules={[{ required: true, message: "请输入 Doris 表副本数" }]}
              >
                <InputNumber min={1} style={{ width: "100%" }} />
              </Form.Item>
            </>
          )}
        </Col>
      </Row>
    </div>
  );
};

export default MultiWorkflowParamConfig;
