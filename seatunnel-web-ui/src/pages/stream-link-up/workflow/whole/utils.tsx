import React from "react";
import DatabaseIcons from "@/pages/data-source/icon/DatabaseIcons";
import {
  DEFAULT_REALTIME_SOURCE_TYPE,
  DEFAULT_TASK_TARGET_DATASOURCE_TYPE,
} from "@/pages/data-source/dataSourceRegistry";
import { TableOutlined } from "@ant-design/icons";
import type { TableItem } from "./types";

/** 实时整库任务默认来源 MySQL-CDC，去向默认 ZeoneDB-D。 */
export const DEFAULT_SOURCE_DB_TYPE = { ...DEFAULT_REALTIME_SOURCE_TYPE };
export const DEFAULT_TARGET_DB_TYPE = { ...DEFAULT_TASK_TARGET_DATASOURCE_TYPE };

export const safeParseDraft = (draftStr?: string) => {
  if (!draftStr) return null;
  try {
    return JSON.parse(draftStr);
  } catch (error) {
    console.error("Failed to parse draft:", error);
    return null;
  }
};

export const isGraphDraft = (draft: any) => {
  return !!draft && typeof draft === "object" && "nodes" in draft;
};

export const buildDataSourceOptions = (list: any[] = []) => {
  return list.map((item) => ({
    label: (
      <div style={{ display: "flex", alignItems: "center" }}>
        <DatabaseIcons dbType={item?.label} width="18" height="18" />
        &nbsp;&nbsp;{item?.label}
      </div>
    ),
    value: item.value,
  }));
};

export const buildTableItems = (list: any[] = []): TableItem[] => {
  return list.map((item: any) => ({
    key: item.value,
    rawTitle: item.label,
    title: (
      <div>
        <TableOutlined style={{ color: "orange" }} />
        &nbsp;&nbsp;
        {item.label}
      </div>
    ),
  }));
};