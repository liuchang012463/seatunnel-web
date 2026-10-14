import { ReloadOutlined } from "@ant-design/icons";
import { Alert, Button, Card, Collapse, Descriptions, Empty, Spin, Tag } from "antd";
import React, { useCallback, useEffect, useRef, useState } from "react";

import { batchJobInstanceApi } from "../api";

interface EngineJobInfoTabProps {
  instanceItem: any;
}

const toJson = (value: unknown) => JSON.stringify(value, null, 2);

const EngineJobInfoTab: React.FC<EngineJobInfoTabProps> = ({ instanceItem }) => {
  const instanceId = instanceItem?.id;
  const requestVersion = useRef(0);
  const [data, setData] = useState<any>();
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string>();

  const loadEngineInfo = useCallback(async () => {
    if (!instanceId) {
      setData(undefined);
      return;
    }

    const version = ++requestVersion.current;
    setLoading(true);
    setError(undefined);

    try {
      const response = await batchJobInstanceApi.engineInfo(instanceId);
      if (requestVersion.current !== version) return;

      if (response?.code !== 0) {
        setError(response?.message || response?.msg || "读取 Engine 作业信息失败");
        setData(undefined);
        return;
      }

      setData(response.data);
    } catch (requestError: any) {
      if (requestVersion.current === version) {
        setError(
          requestError?.response?.data?.message ||
            requestError?.message ||
            "读取 Engine 作业信息失败",
        );
        setData(undefined);
      }
    } finally {
      if (requestVersion.current === version) {
        setLoading(false);
      }
    }
  }, [instanceId]);

  useEffect(() => {
    void loadEngineInfo();
    return () => {
      requestVersion.current += 1;
    };
  }, [loadEngineInfo]);

  const sections = [
    { key: "diagnostics", label: "Engine 诊断信息", value: data?.diagnostics },
    { key: "jobDag", label: "作业拓扑（jobDag）", value: data?.jobDag },
    { key: "metrics", label: "Engine 指标快照", value: data?.metrics },
  ].filter((section) => section.value !== undefined && section.value !== null);

  return (
    <div className="space-y-4">
      <Card
        title="SeaTunnel Engine 作业信息"
        extra={
          <Button
            icon={<ReloadOutlined />}
            onClick={() => void loadEngineInfo()}
            loading={loading}
          >
            刷新
          </Button>
        }
      >
        <Spin spinning={loading}>
          {error && (
            <Alert
              className="mb-4"
              type="error"
              showIcon
              message="读取 Engine 作业信息失败"
              description={error}
            />
          )}

          {data && (
            <Descriptions bordered size="small" column={2}>
              <Descriptions.Item label="Engine 状态">
                <Tag color={String(data.status || "").toUpperCase() === "FAILED" ? "error" : "blue"}>
                  {data.status || data.localStatus || "未知"}
                </Tag>
              </Descriptions.Item>
              <Descriptions.Item label="Engine 作业 ID">
                {data.engineJobId || "尚未生成"}
              </Descriptions.Item>
              <Descriptions.Item label="Engine 创建时间">
                {data.createTime == null ? "-" : String(data.createTime)}
              </Descriptions.Item>
              <Descriptions.Item label="Engine 结束时间">
                {data.finishTime == null ? "-" : String(data.finishTime)}
              </Descriptions.Item>
            </Descriptions>
          )}

          {data?.errorMsg && (
            <Alert
              className="mt-4"
              type="error"
              showIcon
              message="Engine 错误信息"
              description={data.errorMsg}
            />
          )}

          {!loading && !data?.engineJobId && !error && (
            <Empty
              className="mt-6"
              description={`Engine 作业 ID 尚未生成（本地状态：${data?.localStatus || instanceItem?.jobStatus || "未知"}）`}
            />
          )}
        </Spin>
      </Card>

      {data?.localErrorMessage && (
        <Alert
          type="warning"
          showIcon
          message="SeaTunnel Web 提交状态说明"
          description={data.localErrorMessage}
        />
      )}

      {sections.length > 0 ? (
        <Collapse
          items={sections.map((section) => ({
            key: section.key,
            label: section.label,
            children: (
              <pre className="max-h-[32rem] overflow-auto whitespace-pre-wrap break-all rounded-lg bg-slate-950 p-4 text-xs text-slate-100">
                {toJson(section.value)}
              </pre>
            ),
          }))}
        />
      ) : (
        !loading &&
        data?.engineJobId && (
          <Empty description="Engine 未返回诊断、拓扑或指标字段；字段会随 Engine 版本和作业状态变化。" />
        )
      )}
    </div>
  );
};

export default EngineJobInfoTab;
