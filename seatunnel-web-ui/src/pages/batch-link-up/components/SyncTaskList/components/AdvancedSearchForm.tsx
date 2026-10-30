import {
  CheckSquareOutlined,
  CloseOutlined,
  DownOutlined,
  SearchOutlined,
  StopOutlined,
  SyncOutlined,
} from "@ant-design/icons";
import { useIntl } from "@umijs/max";
import { Button, Col, DatePicker, Form, Input, Row, Select, Space } from "antd";
import moment from "moment";
import { useEffect, useMemo, useState } from "react";
import DatabaseIcons from "../../../../data-source/icon/DatabaseIcons";

export interface TaskFilterOption {
  label: React.ReactNode;
  value: string;
}

interface AdvancedSearchFormProps {
  onSearch: (values: any) => void;
  onReset: () => void;
  initialValues?: any;
  createAction?: React.ReactNode;
  fileMode?: boolean;
  sourceOptions?: TaskFilterOption[];
  sinkOptions?: TaskFilterOption[];
  showTableFilters?: boolean;
  hideAdvancedFilters?: boolean;
  forceChineseLabels?: boolean;
}

const { RangePicker } = DatePicker;

const AdvancedSearchForm: React.FC<AdvancedSearchFormProps> = ({
  onSearch,
  onReset,
  initialValues,
  createAction,
  fileMode = false,
  sourceOptions,
  sinkOptions,
  showTableFilters = !fileMode,
  hideAdvancedFilters = false,
  forceChineseLabels = false,
}) => {
  const intl = useIntl();
  const [form] = Form.useForm();
  const [expand, setExpand] = useState(false);

  const defaultTimeRange = useMemo<moment.Moment[]>(() => [], []);
  const text = (messageId: string, defaultMessage: string, chineseMessage: string) =>
    forceChineseLabels ? chineseMessage : intl.formatMessage({ id: messageId, defaultMessage });

  const mergedInitialValues = useMemo(
    () => ({
      createTime: defaultTimeRange,
      ...initialValues,
    }),
    [defaultTimeRange, initialValues],
  );

  useEffect(() => {
    form.setFieldsValue(mergedInitialValues);
  }, [form, mergedInitialValues]);

  useEffect(() => {
    const hasAdvancedValue = Boolean(
      initialValues?.id ||
        initialValues?.status ||
        initialValues?.sourceType ||
        initialValues?.sinkType ||
        initialValues?.sourceTable ||
        initialValues?.sinkTable,
    );

    if (hasAdvancedValue) {
      setExpand(true);
    }
  }, [initialValues]);

  const handleFinish = (values: any) => {
    onSearch(values);
  };

  const handleReset = () => {
    const resetValues = {
      createTime: defaultTimeRange,
      jobName: undefined,
      id: undefined,
      status: undefined,
      sourceType: undefined,
      sinkType: undefined,
      sourceTable: undefined,
      sinkTable: undefined,
    };

    form.setFieldsValue(resetValues);
    onReset();
  };

  const createDataSourceOption = (dbType: string, value: string) => ({
    label: (
      <div className="flex items-center gap-2">
        <DatabaseIcons dbType={dbType} width="14" height="14" />
        <span>{dbType}</span>
      </div>
    ),
    value,
  });

  const defaultDataSourceOptions = fileMode
    ? [
        createDataSourceOption("FTP", "FTP"),
        createDataSourceOption("SFTP", "SFTP"),
        createDataSourceOption("S3", "S3"),
        createDataSourceOption("MINIO", "MINIO"),
      ]
    : [
        createDataSourceOption("JDBC", "JDBC"),
        createDataSourceOption("MySql", "MYSQL"),
        createDataSourceOption("Oracle", "ORACLE"),
        createDataSourceOption("PostgreSQL", "POSTGRE_SQL"),
        createDataSourceOption("海量（Vastbase）", "VASTBASE"),
        createDataSourceOption("Doris", "DORIS"),
        createDataSourceOption("Elasticsearch", "ELASTICSEARCH"),
        createDataSourceOption("Kingbase", "KINGBASE"),
        createDataSourceOption("Dameng", "DAMENG"),
        createDataSourceOption("H2", "H2"),
        createDataSourceOption("Kafka", "KAFKA"),
        createDataSourceOption("HTTP", "HTTP"),
      ];

  const sourceDataSourceOptions = sourceOptions || defaultDataSourceOptions;
  const sinkDataSourceOptions = sinkOptions || defaultDataSourceOptions;

  const statusOptions = [
    {
      label: (
        <span className="inline-flex items-center gap-2">
          <SyncOutlined spin className="text-blue-500" />
          {text("pages.job.status.running", "RUNNING", "运行中")}
        </span>
      ),
      value: "RUNNING",
    },
    {
      label: (
        <span className="inline-flex items-center gap-2">
          <CheckSquareOutlined className="text-emerald-500" />
          {text("pages.job.status.success", "Success", "成功")}
        </span>
      ),
      value: "FINISHED",
    },
    {
      label: (
        <span className="inline-flex items-center gap-2">
          <CloseOutlined className="text-rose-500" />
          {text("pages.job.status.failed", "FAILED", "失败")}
        </span>
      ),
      value: "FAILED",
    },
    {
      label: (
        <span className="inline-flex items-center gap-2">
          <StopOutlined className="text-slate-400" />
          {text("pages.job.status.unstarted", "Unstarted", "未执行")}
        </span>
      ),
      value: "UNSTARTED",
    },
  ];

  const fieldLabel = (text: React.ReactNode) => (
    <span className="task-search-label">{text}</span>
  );

  const commonFormItemProps = {
    className: "mb-0",
    labelCol: {
      flex: "72px",
    },
    wrapperCol: {
      flex: 1,
    },
  };

  const selectPlaceholder = text("pages.job.search.selectPlaceholder", "Select...", "请选择...");

  return (
    <div className="task-search-panel">
      <Form
        form={form}
        name="advanced_search"
        id="task-advanced-search"
        colon={false}
        onFinish={handleFinish}
        initialValues={mergedInitialValues}
      >
        <Row gutter={[16, 14]} align="bottom">
          <Col xs={24} md={12} xl={6}>
            <Form.Item
              {...commonFormItemProps}
              name="jobName"
              label={fieldLabel(
                text("pages.job.search.jobName", "Job Name", "任务名"),
              )}
            >
              <Input
                id="task-search-job-name"
                name="jobName"
                allowClear
                prefix={<SearchOutlined className="text-slate-400" />}
                placeholder={text("pages.job.search.jobName.placeholder", "Enter job name", "请输入任务名")}
                className="h-8"
              />
            </Form.Item>
          </Col>

          <Col xs={24} md={12} xl={6}>
            <Form.Item
              {...commonFormItemProps}
              name="createTime"
              label={fieldLabel(
                text("pages.job.search.createTime", "Create Time", "创建时间"),
              )}
            >
              <RangePicker
                id={{
                  start: "task-search-create-time-start",
                  end: "task-search-create-time-end",
                }}
                className="h-8 w-full"
                placeholder={forceChineseLabels ? ["开始日期", "结束日期"] : undefined}
              />
            </Form.Item>
          </Col>

          <Col xs={24} md={12} xl={4}>
            <Form.Item
              {...commonFormItemProps}
              name="status"
              label={fieldLabel(
                text("pages.job.search.status", "Status", "状态"),
              )}
            >
              <Select
                id="task-search-status"
                aria-label="按任务状态筛选"
                allowClear
                showSearch
                placeholder={selectPlaceholder}
                options={statusOptions}
                className="w-full"
              />
            </Form.Item>
          </Col>

          <Col xs={24} md={12} xl={8}>
            <div className="task-search-actions">
              <Space size={8}>
                <Button
                  type="primary"
                  htmlType="submit"
                  className="task-search-submit h-8 px-3 font-medium"
                >
                  {text("pages.job.search.button.search", "Search", "搜索")}
                </Button>

                <Button
                  onClick={handleReset}
                  className="task-search-reset h-8 px-3"
                >
                  {text("pages.job.search.button.reset", "Reset", "重置")}
                </Button>

                {!hideAdvancedFilters && <button
                  type="button"
                  className="task-search-expand inline-flex h-8 items-center gap-1 px-2 text-xs font-medium text-[color:var(--st-color-accent)] transition hover:bg-[rgba(63,198,255,0.08)]"
                  aria-expanded={expand}
                  aria-controls="task-advanced-search-filters"
                  onClick={() => setExpand((prev) => !prev)}
                >
                  {expand
                    ? intl.formatMessage({
                        id: "pages.job.search.collapse",
                        defaultMessage: "Collapse",
                      })
                    : intl.formatMessage({
                        id: "pages.job.search.expand",
                        defaultMessage: "Expand",
                      })}

                  <DownOutlined
                    className={[
                      "text-[10px] transition-transform duration-200",
                      expand ? "rotate-180" : "rotate-0",
                    ].join(" ")}
                  />
                </button>}
              </Space>
              {createAction ? (
                <div className="task-search-create-action">{createAction}</div>
              ) : null}
            </div>
          </Col>
        </Row>

        {!hideAdvancedFilters && expand && (
          <Row
            id="task-advanced-search-filters"
            gutter={[16, 14]}
            className="mt-4"
            align="bottom"
          >
            <Col xs={24} md={12} xl={7}>
              <Form.Item
                {...commonFormItemProps}
                name="id"
                label={fieldLabel(
                  intl.formatMessage({
                    id: "pages.job.search.jobId",
                    defaultMessage: "Job Definition ID",
                  }),
                )}
              >
                <Input
                  id="task-search-job-id"
                  name="id"
                  allowClear
                  placeholder={intl.formatMessage({
                    id: "pages.job.search.jobId.placeholder",
                    defaultMessage: "Enter job definition ID",
                  })}
                  className="h-8"
                />
              </Form.Item>
            </Col>

            <Col xs={24} md={12} xl={7}>
              <Form.Item
                {...commonFormItemProps}
                name="sourceType"
                label={fieldLabel(
                  intl.formatMessage({
                    id: "pages.job.search.source",
                    defaultMessage: "Source",
                  }),
                )}
              >
              <Select
                id="task-search-source-type"
                allowClear
                  showSearch
                  placeholder={selectPlaceholder}
                  options={sourceDataSourceOptions}
                  className="w-full"
                />
              </Form.Item>
            </Col>

            <Col xs={24} md={12} xl={6}>
              <Form.Item
                {...commonFormItemProps}
                name="sinkType"
                label={fieldLabel(
                  intl.formatMessage({
                    id: "pages.job.search.sink",
                    defaultMessage: "Sink",
                  }),
                )}
              >
              <Select
                id="task-search-sink-type"
                allowClear
                  showSearch
                  placeholder={selectPlaceholder}
                  options={sinkDataSourceOptions}
                  className="w-full"
                />
              </Form.Item>
            </Col>

            <Col xs={24} md={12} xl={4} />

            {showTableFilters && (
              <>
                <Col xs={24} md={12} xl={7}>
                  <Form.Item
                    {...commonFormItemProps}
                    name="sourceTable"
                    label={fieldLabel(
                      intl.formatMessage({
                        id: "pages.job.search.sourceTable",
                        defaultMessage: "Source Table",
                      }),
                    )}
                  >
                    <Input
                      id="task-search-source-table"
                      name="sourceTable"
                      allowClear
                      placeholder={intl.formatMessage({
                        id: "pages.job.search.fuzzyPlaceholder",
                        defaultMessage: "Fuzzy match...",
                      })}
                      className="h-8"
                    />
                  </Form.Item>
                </Col>

                <Col xs={24} md={12} xl={7}>
                  <Form.Item
                    {...commonFormItemProps}
                    name="sinkTable"
                    label={fieldLabel(
                      intl.formatMessage({
                        id: "pages.job.search.sinkTable",
                        defaultMessage: "Sink Table",
                      }),
                    )}
                  >
                    <Input
                      id="task-search-sink-table"
                      name="sinkTable"
                      allowClear
                      placeholder={intl.formatMessage({
                        id: "pages.job.search.fuzzyPlaceholder",
                        defaultMessage: "Fuzzy match...",
                      })}
                      className="h-8"
                    />
                  </Form.Item>
                </Col>
              </>
            )}
          </Row>
        )}
      </Form>
    </div>
  );
};

export default AdvancedSearchForm;
