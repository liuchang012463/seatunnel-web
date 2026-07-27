import {
  CloudServerOutlined,
  DatabaseOutlined,
  FormOutlined,
  MonitorOutlined,
  SettingOutlined,
  SwapOutlined,
} from '@ant-design/icons';
import type { MenuDataItem } from '@ant-design/pro-components';
import React from 'react';

export const prototypeMenuData: MenuDataItem[] = [
  {
    path: '/menu/reporting',
    name: '数据采报',
    icon: <FormOutlined />,
    children: [
      { path: '/reporting/forms', name: '数据采报管理' },
      { path: '/reporting/reports', name: '采集报告管理' },
    ],
  },
  {
    path: '/menu/resources',
    name: '引接资源',
    icon: <DatabaseOutlined />,
    children: [
      { path: '/data-source', name: '数据源管理' },
      { path: '/client', name: '引擎管理' },
      { path: '/resources/data-discovery', name: '数据探查' },
    ],
  },
  {
    path: '/menu/ingestion',
    name: '数据引接',
    icon: <SwapOutlined />,
    children: [
      { path: '/sync/batch-link-up', name: '离线引接任务管理' },
      { path: '/sync/stream-link-up', name: '实时引接任务管理' },
      { path: '/sync/cloud-edge-tasks', name: '云边协同任务管理' },
      { path: '/sync/edge-access-tasks', name: '边缘接入任务管理' },
      { path: '/sync/links', name: '引接链路管理' },
      { path: '/sync/topology', name: '数据拓扑管理' },
    ],
  },
  {
    path: '/menu/operations',
    name: '运行运维',
    icon: <MonitorOutlined />,
    children: [
      { path: '/bi', name: '引接态势' },
      { path: '/metrics', name: '运行监控' },
      { path: '/alarm', name: '告警管理' },
      { path: '/operations/diagnostics', name: '故障辅助' },
    ],
  },
  {
    path: '/menu/lake',
    name: '入湖管理',
    icon: <CloudServerOutlined />,
    children: [
      { path: '/lake/resources', name: '入湖资源管理' },
      { path: '/lake/lifecycle', name: '数据生命周期管理' },
      { path: '/lake/logical-access', name: '逻辑入湖管理' },
    ],
  },
  {
    path: '/menu/system',
    name: '系统管理',
    icon: <SettingOutlined />,
    children: [
      { path: '/knowledge-management', name: '参数与知识' },
      { path: '/open-api', name: '开放接口' },
    ],
  },
];
