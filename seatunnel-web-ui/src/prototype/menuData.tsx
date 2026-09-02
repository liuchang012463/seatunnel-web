import {
  ApiOutlined,
  ApartmentOutlined,
  BarChartOutlined,
  CloudServerOutlined,
  DatabaseOutlined,
  FormOutlined,
  FolderOpenOutlined,
  LinkOutlined,
  MonitorOutlined,
  ReadOutlined,
  SafetyCertificateOutlined,
  SettingOutlined,
  SwapOutlined,
  ThunderboltOutlined,
} from '@ant-design/icons';
import type { MenuDataItem } from '@ant-design/pro-components';
import React from 'react';

export const prototypeMenuData: MenuDataItem[] = [
  {
    path: '/bi',
    name: '引接态势',
    icon: <BarChartOutlined />,
  },
  {
    path: '/data-source',
    name: '数据源管理',
    icon: <DatabaseOutlined />,
  },
  {
    path: '/menu/exploration',
    name: '数据探查',
    icon: <ReadOutlined />,
    children: [
      { path: '/data-exploration/overview', name: '探查概览', icon: <BarChartOutlined /> },
      { path: '/data-exploration/tasks', name: '探查任务配置', icon: <ThunderboltOutlined /> },
      { path: '/data-exploration/results', name: '探查结果展示', icon: <ApartmentOutlined /> },
    ],
  },
  {
    path: '/reporting/forms',
    name: '数据采报',
    icon: <FormOutlined />,
  },
  {
    path: '/menu/ingestion',
    name: '数据引接',
    icon: <SwapOutlined />,
    children: [
      { path: '/metrics', name: '任务概览', icon: <MonitorOutlined /> },
      { path: '/sync/batch-link-up', name: '离线引接任务', icon: <SwapOutlined /> },
      { path: '/sync/stream-link-up', name: '实时引接任务', icon: <ThunderboltOutlined /> },
      { path: '/sync/file-link-up', name: '文件引接任务', icon: <FolderOpenOutlined /> },
      { path: '/sync/cloud-edge-tasks', name: '云边协同任务', icon: <CloudServerOutlined /> },
      {
        path: '/sync/edge-access-tasks',
        name: '边缘接入任务',
        icon: <ApiOutlined />,
      },
      { path: '/sync/topology', name: '数据拓扑', icon: <ApartmentOutlined /> },
    ],
  },
  {
    path: '/menu/operations',
    name: '运行运维',
    icon: <MonitorOutlined />,
    children: [
      { path: '/client', name: '引擎管理', icon: <ApiOutlined /> },
      { path: '/alarm', name: '告警管理', icon: <SettingOutlined /> },
      { path: '/operations/protocol', name: '协议管理', icon: <ApiOutlined /> },
      { path: '/operations/diagnostics', name: '安全加密', icon: <SafetyCertificateOutlined /> },
    ],
  },
  {
    path: '/menu/lake',
    name: '入湖管理',
    icon: <CloudServerOutlined />,
    children: [
      { path: '/lake/warehouse', name: '数据湖管理', icon: <SettingOutlined /> },
      { path: '/lake/resources', name: '物理入湖管理', icon: <DatabaseOutlined /> },
      { path: '/lake/logical-access', name: '逻辑入湖管理', icon: <LinkOutlined /> },
      { path: '/lake/lifecycle', name: '数据生命周期管理', icon: <FolderOpenOutlined /> },
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
