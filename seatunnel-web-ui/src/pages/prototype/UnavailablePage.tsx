import { ArrowLeftOutlined, ReadOutlined } from '@ant-design/icons';
import { PageContainer } from '@ant-design/pro-components';
import { Button, Card, Empty, Space } from 'antd';
import { history, useLocation } from '@umijs/max';
import React from 'react';

const PAGE_COPY: Record<string, { title: string; description: string }> = {
  '/reporting/forms': {
    title: '数据采报',
    description: '采报模板与填报闭环尚未接入当前版本。',
  },
  '/sync/cloud-edge-tasks': {
    title: '云边协同任务',
    description: '云边协同任务能力尚未接入当前版本。',
  },
  '/sync/edge-access-tasks': {
    title: '边缘接入任务',
    description: '边缘接入任务能力尚未接入当前版本。',
  },
  '/sync/links': {
    title: '数据协同任务',
    description: '数据协同任务能力尚未接入当前版本。',
  },
  '/sync/topology': {
    title: '数据拓扑',
    description: '数据拓扑能力尚未接入当前版本。',
  },
  '/bi': {
    title: '引接态势',
    description: '引接态势能力尚未接入当前版本。',
  },
  '/operations/diagnostics': {
    title: '安全加密',
    description: '安全加密与诊断能力尚未接入当前版本。',
  },
};

const UnavailablePage: React.FC = () => {
  const { pathname } = useLocation();
  const copy = PAGE_COPY[pathname] || {
    title: '页面暂不可用',
    description: '该页面尚未接入当前版本。',
  };
  const isDiscovery = pathname === '/resources/data-discovery';

  return (
    <PageContainer title={copy.title} subTitle={copy.description}>
      <Card>
        <Empty
          image={<ReadOutlined style={{ fontSize: 40, color: 'var(--st-color-text-muted)' }} />}
          description="功能建设中，当前不会展示演示数据或虚假操作。"
        >
          <Space wrap>
            <Button icon={<ArrowLeftOutlined />} onClick={() => history.push('/data-source')}>
              返回数据源管理
            </Button>
            {isDiscovery ? (
              <Button type="primary" onClick={() => history.push('/data-exploration/overview')}>
                查看探查概览
              </Button>
            ) : null}
          </Space>
        </Empty>
      </Card>
    </PageContainer>
  );
};

export default UnavailablePage;
