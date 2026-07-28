import { Code2, FlaskConical, ShieldCheck } from "lucide-react";
import React from "react";
import type { DataSourceGroup, DataSourceOptionItem } from "./types";

export const PAGE_DEFAULT_PAGINATION = {
  pageNo: 1,
  pageSize: 10,
  total: 0,
};

export const COMMON_DB_OPTIONS: DataSourceOptionItem[] = [
  { label: "JDBC", value: "JDBC" },
  { label: "MYSQL", value: "MYSQL" },
  { label: "ORACLE", value: "ORACLE" },
  { label: "POSTGRE_SQL", value: "POSTGRE_SQL" },
  { label: "DORIS", value: "DORIS" },
  { label: "KINGBASE", value: "KINGBASE" },
  { label: "DAMENG", value: "DAMENG" },
  { label: "KAFKA", value: "KAFKA" },
  { label: "FTP", value: "FTP" },
  { label: "SFTP", value: "SFTP" },
  { label: "Amazon S3", value: "S3" },
  { label: "MinIO", value: "MINIO" },
];

export const ENVIRONMENT_OPTIONS: DataSourceOptionItem[] = [
  { label: "DEVELOP", value: "DEVELOP" },
  { label: "TEST", value: "TEST" },
  { label: "PROD", value: "PROD" },
];

export const dataSourceGroupList: DataSourceGroup[] = [
  {
    groupName: "关系型数据库",
    datasourceList: [
      {
        onlyDiScript: false,
        dbType: "JDBC",
        type: "JDBC",
        connectorType: "Jdbc",
      },
      {
        onlyDiScript: false,
        dbType: "MYSQL",
        type: "MYSQL",
        connectorType: "Jdbc",
      },
      {
        onlyDiScript: false,
        dbType: "ORACLE",
        type: "ORACLE",
        connectorType: "Jdbc",
      },
      {
        onlyDiScript: false,
        dbType: "POSTGRE_SQL",
        type: "POSTGRE_SQL",
        connectorType: "Jdbc",
      },
      {
        onlyDiScript: false,
        dbType: "KINGBASE",
        type: "KINGBASE",
        connectorType: "Jdbc",
      },
      {
        onlyDiScript: false,
        dbType: "DAMENG",
        type: "DAMENG",
        connectorType: "Jdbc",
      },
    ],
  },
  {
    groupName: "消息队列",
    datasourceList: [
      {
        onlyDiScript: false,
        dbType: "KAFKA",
        type: "KAFKA",
        connectorType: "Kafka",
      },
    ],
  },
  {
    groupName: "文件与对象存储",
    datasourceList: [
      {
        onlyDiScript: false,
        dbType: "FTP",
        type: "FTP",
        connectorType: "FtpFile",
      },
      {
        onlyDiScript: false,
        dbType: "SFTP",
        type: "SFTP",
        connectorType: "SftpFile",
      },
      {
        onlyDiScript: false,
        dbType: "S3",
        type: "S3",
        connectorType: "S3File",
      },
      {
        onlyDiScript: false,
        dbType: "MINIO",
        type: "MINIO",
        connectorType: "S3File",
      },
    ],
  },
  {
    groupName: "OLAP 数据库",
    datasourceList: [
      {
        onlyDiScript: false,
        dbType: "DORIS",
        type: "DORIS",
        connectorType: "Doris",
      },
    ],
  },
];

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
    color: "#ff4d4f",
    backgroundColor: "#fff2f0",
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
    color: "#52c41a",
    backgroundColor: "#f6ffed",
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
    color: "#1677ff",
    backgroundColor: "#e6f4ff",
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
        ease: [0.22, 1, 0.36, 1],
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
