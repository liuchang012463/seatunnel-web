import {
  LinkOutlined,
  SearchOutlined,
} from "@ant-design/icons";
import { Alert, Button, Input, Pagination, Skeleton, Tag } from "antd";
import { history } from "@umijs/max";
import React, { useEffect, useMemo, useState } from "react";
import MethodSegmented, { type FilterMethod } from "./MethodSegmented";
import { HttpMethod, useOpenApiData } from "./openapi-parser";
import "./index.less";

const methodColorMap: Record<HttpMethod, string> = {
  GET: "green",
  POST: "blue",
  PUT: "orange",
  DELETE: "red",
  PATCH: "purple",
  OPTIONS: "default",
  HEAD: "default",
};

const OPEN_API_URL = "/v3/api-docs";

const ApiManagementPage: React.FC = () => {
  const { loading, error, controllers, apis, reload } =
    useOpenApiData(OPEN_API_URL);

  const [activeController, setActiveController] = useState<string>("all");
  const [method, setMethod] = useState<FilterMethod>("ALL");
  const [keyword, setKeyword] = useState("");
  const [pageNo, setPageNo] = useState(1);
  const [pageSize, setPageSize] = useState(20);

  const currentController = useMemo(() => {
    return controllers.find((item) => item.key === activeController);
  }, [controllers, activeController]);

  const methodOptions = useMemo(() => {
    const usedMethods = Array.from(new Set(apis.map((item) => item.method)));
    return ["ALL", ...usedMethods] as FilterMethod[];
  }, [apis]);

  const filteredApis = useMemo(() => {
    return apis.filter((item) => {
      const matchController =
        activeController === "all" || item.controller === activeController;

      const matchMethod = method === "ALL" || item.method === method;

      const search = keyword.trim().toLowerCase();
      const matchKeyword =
        !search ||
        item.name.toLowerCase().includes(search) ||
        item.path.toLowerCase().includes(search) ||
        item.desc.toLowerCase().includes(search) ||
        (item.operationId || "").toLowerCase().includes(search);

      return matchController && matchMethod && matchKeyword;
    });
  }, [apis, activeController, method, keyword]);

  // The OpenAPI document can contain hundreds of endpoints; render one page at a time so the
  // page does not become a 46k-pixel scroll surface.
  const pagedApis = useMemo(() => {
    const start = (pageNo - 1) * pageSize;
    return filteredApis.slice(start, start + pageSize);
  }, [filteredApis, pageNo, pageSize]);

  useEffect(() => {
    setPageNo(1);
  }, [activeController, method, keyword]);

  return (
    <div className="open-api-page">
      <div className="open-api-body">
        {error ? (
          <Alert
            type="error"
            showIcon
            className="mb-4"
            message="获取 OpenAPI 文档失败"
            description={error}
            action={<Button type="link" onClick={reload}>重试</Button>}
          />
        ) : null}

        <div className="open-api-workspace">
          <aside className="open-api-panel open-api-controllers">
            <div className="open-api-panel__heading">
              <div className="text-[16px] font-semibold text-[var(--st-color-text-primary)]">
                Controller 列表
              </div>
              <div className="text-[14px] text-[var(--st-color-text-muted)]">
                共 {Math.max(controllers.length - 1, 0)} 个控制器
              </div>
            </div>

            {loading ? (
              <div className="space-y-3 p-4">
                {Array.from({ length: 6 }).map((_, index) => (
                  <Skeleton.Button
                    key={index}
                    active
                    block
                    className="!h-[78px] !rounded-[8px]"
                  />
                ))}
              </div>
            ) : (
              <div className="open-api-controllers__scroll space-y-2.5">
                {controllers.map((item) => {
                  const active = item.key === activeController;

                  return (
                    <button
                      type="button"
                      key={item.key}
                      onClick={() => setActiveController(item.key)}
                      className={[
                        "group relative w-full cursor-pointer overflow-hidden rounded-[8px] border px-4 py-3.5 transition-all duration-200",
                        active
                          ? "open-api-controller-item is-active"
                          : "open-api-controller-item",
                      ].join(" ")}
                    >
                      <div className="flex items-start justify-between gap-3">
                        <div className="min-w-0 flex-1">
                          <div
                            className={[
                              "truncate text-[14px] font-semibold transition-colors",
                              active
                                ? "text-[var(--st-color-accent)]"
                                : "text-[var(--st-color-text-primary)]",
                            ].join(" ")}
                          >
                            {item.name}
                          </div>
                          {item.desc ? (
                            <div className="mt-1.5 line-clamp-2 text-[14px] leading-5 text-[var(--st-color-text-muted)]">
                              {item.desc}
                            </div>
                          ) : null}
                        </div>

                        <div
                          className={[
                            "mt-0.5 inline-flex h-7 min-w-[34px] items-center justify-center rounded-full px-2.5 text-[14px] font-semibold",
                            active
                              ? "border border-[var(--st-color-border)] text-[var(--st-color-accent)]"
                              : "bg-[var(--st-color-bg-control)] text-[var(--st-color-text-secondary)]",
                          ].join(" ")}
                        >
                          {item.count}
                        </div>
                      </div>
                    </button>
                  );
                })}
              </div>
            )}
          </aside>

          <section className="open-api-panel open-api-results">
            <div className="open-api-results__toolbar flex flex-wrap items-center justify-between gap-3">
              <div className="w-full max-w-[420px]">
                <Input
                  allowClear
                  value={keyword}
                  onChange={(e) => setKeyword(e.target.value)}
                  placeholder="搜索接口名称、路径、描述"
                  prefix={<SearchOutlined />}
                  className="!h-9"
                />
              </div>

              <MethodSegmented method={method} setMethod={setMethod} />
            </div>

            <div className="mt-5 space-y-3">
              {loading ? (
                Array.from({ length: 6 }).map((_, index) => (
                  <Skeleton.Button
                    key={index}
                    active
                    block
                    className="!h-[118px] !rounded-[20px]"
                  />
                ))
              ) : filteredApis.length > 0 ? (
                pagedApis.map((item) => (
                  <div
                    key={item.id}
                    className="open-api-card group rounded-[8px] border p-4 transition-all"
                  >
                    <div className="flex items-start justify-between gap-4">
                      <div className="min-w-0 flex-1">
                        <div className="flex flex-wrap items-center gap-3">
                          <Tag
                            color={methodColorMap[item.method]}
                            className="!mr-0 !rounded-full !px-3 !py-[2px] !font-semibold"
                          >
                            {item.method}
                          </Tag>

                          <div className="truncate text-[15px] font-semibold text-[var(--st-color-text-primary)]">
                            {item.name}
                          </div>

                          <div className="rounded-full bg-[var(--st-color-bg-control)] px-2.5 py-1 text-[14px] font-medium text-[var(--st-color-accent)]">
                            {controllers.find((c) => c.key === item.controller)
                              ?.name || item.controller}
                          </div>
                        </div>

                        <div className="mt-3 flex items-center gap-2 text-[14px] text-[var(--st-color-text-secondary)]">
                          <LinkOutlined />
                          <code className="rounded-md bg-[var(--st-color-bg-control)] px-2 py-1 text-[14px] text-[var(--st-color-text-secondary)]">
                            {item.path}
                          </code>
                        </div>

                        <div className="mt-3 text-[14px] leading-6 text-[var(--st-color-text-muted)]">
                          {item.desc}
                        </div>

                        {item.operationId ? (
                          <div className="mt-2 text-[14px] text-[var(--st-color-text-muted)]">
                            operationId: {item.operationId}
                          </div>
                        ) : null}
                      </div>

                      <div className="shrink-0">
                        <Button>
                          查看详情
                        </Button>
                      </div>
                    </div>
                  </div>
                ))
              ) : (
                <div className="open-api-empty flex h-[240px] flex-col items-center justify-center rounded-[8px] border border-dashed text-center">
                  <div className="text-[16px] font-semibold text-[var(--st-color-text-primary)]">
                    暂无匹配接口
                  </div>
                  <div className="mt-2 text-[14px] text-[var(--st-color-text-muted)]">
                    可以试试切换 Controller、请求方式，或者修改搜索关键字
                  </div>
                </div>
              )}
            </div>

            {!loading && filteredApis.length > 0 ? (
              <div className="mt-5 flex justify-end">
                <Pagination
                  current={pageNo}
                  pageSize={pageSize}
                  total={filteredApis.length}
                  showSizeChanger
                  pageSizeOptions={[10, 20, 50, 100]}
                  showTotal={(total) => `共 ${total} 个接口`}
                  onChange={(page, size) => {
                    setPageNo(page);
                    setPageSize(size);
                  }}
                />
              </div>
            ) : null}
          </section>
        </div>
      </div>
    </div>
  );
};

export default ApiManagementPage;
