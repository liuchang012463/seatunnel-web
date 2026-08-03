import {
  ApiOutlined,
  ApartmentOutlined,
  BarChartOutlined,
  CloudServerOutlined,
  DatabaseOutlined,
  FileTextOutlined,
  FormOutlined,
  FolderOpenOutlined,
  LinkOutlined,
  MonitorOutlined,
  ReadOutlined,
  SettingOutlined,
  SwapOutlined,
  ThunderboltOutlined,
} from '@ant-design/icons';
import type { MenuDataItem } from '@ant-design/pro-components';
import React from 'react';

export const prototypeMenuData: MenuDataItem[] = [
  {
    path: '/menu/reporting',
    name: '数据采报',
    icon: <FormOutlined />,
    children: [
      { path: '/reporting/forms', name: '数据采报管理', icon: <FormOutlined /> },
      { path: '/reporting/reports', name: '采集报告管理', icon: <FileTextOutlined /> },
    ],
  },
  {
    path: '/menu/resources',
    name: '引接资源',
    icon: <DatabaseOutlined />,
    children: [
      { path: '/data-source', name: '数据源管理', icon: <DatabaseOutlined /> },
      { path: '/client', name: '引擎管理', icon: <ApiOutlined /> },
      { path: '/resources/data-discovery', name: '数据探查', icon: <ReadOutlined /> },
    ],
  },
  {
    path: '/menu/ingestion',
    name: '数据引接',
    icon: <SwapOutlined />,
    children: [
      { path: '/sync/batch-link-up', name: '离线引接任务管理', icon: <SwapOutlined /> },
      { path: '/sync/file-link-up', name: '文件引接任务管理', icon: <FolderOpenOutlined /> },
      { path: '/sync/stream-link-up', name: '实时引接任务管理', icon: <ThunderboltOutlined /> },
      { path: '/sync/cloud-edge-tasks', name: '云边协同任务管理', icon: <CloudServerOutlined /> },
      { path: '/sync/edge-access-tasks', name: '边缘接入任务管理', icon: <ApiOutlined /> },
      { path: '/sync/links', name: '引接链路管理', icon: <LinkOutlined /> },
      { path: '/sync/topology', name: '数据拓扑管理', icon: <ApartmentOutlined /> },
    ],
  },
  {
    path: '/menu/operations',
    name: '运行运维',
    icon: <MonitorOutlined />,
    children: [
      { path: '/bi', name: '引接态势', icon: <BarChartOutlined /> },
      { path: '/metrics', name: '运行监控', icon: <MonitorOutlined /> },
      { path: '/alarm', name: '告警管理', icon: <SettingOutlined /> },
      { path: '/operations/diagnostics', name: '故障辅助', icon: <ApiOutlined /> },
    ],
  },
  {
    path: '/menu/lake',
    name: '入湖管理',
    icon: <CloudServerOutlined />,
    children: [
      { path: '/lake/resources', name: '入湖资源管理', icon: <DatabaseOutlined /> },
      { path: '/lake/lifecycle', name: '数据生命周期管理', icon: <CloudServerOutlined /> },
      { path: '/lake/logical-access', name: '逻辑入湖管理', icon: <LinkOutlined /> },
    ],
  },
  {
    path: '/menu/system',
    name: '系统管理',
    icon: <SettingOutlined />,
    children: [
      { path: '/knowledge-management', name: '参数与知识', icon: <ReadOutlined /> },
      { path: '/open-api', name: '开放接口', icon: <ApiOutlined /> },
    ],
  },
];
