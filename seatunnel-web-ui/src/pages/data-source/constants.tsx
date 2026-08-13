import { Code2, FlaskConical, ShieldCheck } from "lucide-react";
import React from "react";
import type { DataSourceGroup, DataSourceOptionItem } from "./types";
import {
  DATA_SOURCE_CATEGORIES,
  DATA_SOURCE_REGISTRY,
} from "./dataSourceRegistry";

export const PAGE_DEFAULT_PAGINATION = {
  pageNo: 1,
  pageSize: 10,
  total: 0,
};

export const COMMON_DB_OPTIONS: DataSourceOptionItem[] = [
  ...DATA_SOURCE_REGISTRY.filter((item) => item.creatable !== false).map((item) => ({
    label: item.label,
    value: item.dbType,
  })),
];

export const ENVIRONMENT_OPTIONS: DataSourceOptionItem[] = [
  { label: "DEVELOP", value: "DEVELOP" },
  { label: "TEST", value: "TEST" },
  { label: "PROD", value: "PROD" },
];

export const DATA_SOURCE_STATUS_OPTIONS: DataSourceOptionItem[] = [
  { label: "启用", value: "ENABLED" },
  { label: "停用", value: "DISABLED" },
  { label: "注销", value: "REVOKED" },
];

export const dataSourceGroupList: DataSourceGroup[] = DATA_SOURCE_CATEGORIES
  .filter((category) => category.key !== "OTHER")
  .map((category) => ({
    groupName: category.label,
    datasourceList: DATA_SOURCE_REGISTRY
      .filter((item) => item.category === category.key && item.creatable !== false)
      .map((item) => ({
        onlyDiScript: false,
        dbType: item.dbType,
        label: item.label,
        type: item.dbType,
        connectorType: item.connectorType,
      })),
  }));

export const environmentTagConfigMap: Record<
  string,
  {
    text: string;
    color: string;
    backgroundColor: string;
    icon: React.ReactNode;
  }
> = {
  PROD: {
    text: "生产",
    color: "#ff7875",
    backgroundColor: "rgba(194, 59, 59, 0.18)",
    icon: (
      <div>
        {" "}
        <div className="flex items-center gap-2">
          <span className="flex h-4 w-4 items-center justify-center rounded-lg ">
            <ShieldCheck size={12} />
          </span>
        </div>
      </div>
    ),
  },
  TEST: {
    text: "测试",
    color: "#73d13d",
    backgroundColor: "rgba(56, 178, 74, 0.18)",
    icon: (
      <div>
        {" "}
        <div className="flex items-center gap-2">
          <span className="flex h-4 w-4 items-center justify-center rounded-lg ">
            <FlaskConical size={12} />
          </span>
        </div>
      </div>
    ),
  },
  DEVELOP: {
    text: "开发",
    color: "#4dd2ff",
    backgroundColor: "rgba(33, 135, 168, 0.2)",
    icon: (
      <div>
        {" "}
        <div className="flex items-center gap-2">
          <span className="flex h-4 w-4 items-center justify-center rounded-lg ">
            <Code2 size={12} />
          </span>
        </div>
      </div>
    ),
  },
};

export const PAGE_ANIMATION = {
  fadeUp: {
    hidden: { opacity: 0, y: 18 },
    visible: {
      opacity: 1,
      y: 0,
      transition: {
        duration: 0.45,
        ease: [0.22, 1, 0.36, 1] as [number, number, number, number],
      },
    },
  },
  sectionStagger: {
    hidden: {},
    visible: {
      transition: {
        staggerChildren: 0.08,
        delayChildren: 0.06,
      },
    },
  },
  cardStagger: {
    hidden: {},
    visible: {
      transition: {
        staggerChildren: 0.06,
      },
    },
  },
};
