import {
  ApartmentOutlined,
  CompressOutlined,
  ExpandOutlined,
  FilterOutlined,
} from '@ant-design/icons';
import { PageContainer } from '@ant-design/pro-components';
import { history } from '@umijs/max';
import { Button, Card, Col, Row, Select, Space, Tag, Typography } from 'antd';
import React, { useMemo, useState } from 'react';
import ReactFlow, {
  Background,
  Controls,
  MarkerType,
  MiniMap,
  Panel,
  type Edge,
  type Node,
} from 'reactflow';
import 'reactflow/dist/style.css';
import {
  implementationColors,
  implementationLabels,
  prototypePageRegistry,
} from '@/prototype/registry';
import {
  requirementParents,
  requirementRelations,
} from '@/prototype/requirements';
import type { ImplementationStatus } from '@/prototype/types';
import './index.less';

const firstMenus = Array.from(
  new Set(prototypePageRegistry.map(({ firstMenu }) => firstMenu)),
);
const technicalModules = Array.from(
  new Set(prototypePageRegistry.flatMap(({ technicalModules: modules }) => modules)),
).sort();

const TraceabilityPage: React.FC = () => {
  const [firstMenu, setFirstMenu] = useState<string>();
  const [technicalModule, setTechnicalModule] = useState<string>();
  const [implementationStatus, setImplementationStatus] =
    useState<ImplementationStatus>();
  const [expandedParent, setExpandedParent] = useState<string>();
  const [selectedNode, setSelectedNode] = useState<string>();

  const visiblePages = useMemo(
    () =>
      prototypePageRegistry.filter(
        (page) =>
          (!firstMenu || page.firstMenu === firstMenu) &&
          (!technicalModule || page.technicalModules.includes(technicalModule)) &&
          (!implementationStatus ||
            page.implementationStatus === implementationStatus),
      ),
    [firstMenu, implementationStatus, technicalModule],
  );

  const graph = useMemo(() => {
    const pageRequirementIds = new Set(
      visiblePages.flatMap(({ requirementIds }) => requirementIds),
    );
    const parents = Object.keys(requirementParents).filter((parentId) =>
      requirementRelations.some(
        ({ id, parentId: relationParent }) =>
          relationParent === parentId && pageRequirementIds.has(id),
      ),
    );

    const requirementNodes: Node[] = [];
    let y = 0;
    parents.forEach((parentId) => {
      const atomic = requirementRelations.filter(
        (item) =>
          item.parentId === parentId && pageRequirementIds.has(item.id),
      );
      if (expandedParent === parentId && atomic.length > 1) {
        atomic.forEach((item) => {
          requirementNodes.push({
            id: `req:${item.id}`,
            type: 'default',
            position: { x: 20, y },
            data: { label: `${item.id} ${item.title}` },
            style: {
              width: 300,
              borderRadius: 10,
              borderColor: '#475569',
              background: selectedNode === `req:${item.id}` ? '#dbeafe' : '#fff',
            },
          });
          y += 78;
        });
      } else {
        requirementNodes.push({
          id: `parent:${parentId}`,
          position: { x: 20, y },
          data: {
            label: `${parentId} ${requirementParents[parentId]}${
              atomic.length > 1 ? `（${atomic.length}）` : ''
            }`,
          },
          style: {
            width: 300,
            borderRadius: 10,
            borderColor: '#0f172a',
            background: selectedNode === `parent:${parentId}` ? '#dbeafe' : '#f8fafc',
            fontWeight: 600,
          },
        });
        y += 88;
      }
    });

    const pageNodes: Node[] = visiblePages.map((page, index) => ({
      id: `page:${page.id}`,
      position: { x: 690, y: index * 105 + 10 },
      data: {
        label: `${page.firstMenu} / ${page.secondMenu}\n${implementationLabels[page.implementationStatus]}`,
      },
      style: {
        width: 270,
        whiteSpace: 'pre-line',
        borderRadius: 12,
        border: `2px solid ${implementationColors[page.implementationStatus]}`,
        background:
          selectedNode === `page:${page.id}`
            ? `${implementationColors[page.implementationStatus]}22`
            : '#fff',
      },
    }));

    const edges: Edge[] = [];
    visiblePages.forEach((page) => {
      page.requirementIds.forEach((requirementId) => {
        const relation = requirementRelations.find(({ id }) => id === requirementId);
        if (!relation) return;
        const source =
          expandedParent === relation.parentId &&
          requirementRelations.filter(
            (item) => item.parentId === relation.parentId,
          ).length > 1
            ? `req:${requirementId}`
            : `parent:${relation.parentId}`;
        if (!requirementNodes.some(({ id }) => id === source)) return;
        const target = `page:${page.id}`;
        const isHighlighted =
          selectedNode === source ||
          selectedNode === target ||
          selectedNode === `parent:${relation.parentId}`;
        edges.push({
          id: `${source}-${target}-${requirementId}`,
          source,
          target,
          animated: isHighlighted,
          markerEnd: { type: MarkerType.ArrowClosed },
          style: {
            stroke: isHighlighted
              ? implementationColors[page.implementationStatus]
              : '#cbd5e1',
            strokeWidth: isHighlighted ? 3 : 1.2,
          },
        });
      });
    });

    return { nodes: [...requirementNodes, ...pageNodes], edges };
  }, [expandedParent, selectedNode, visiblePages]);

  return (
    <PageContainer
      title="数据采集引接软件：合同指标—前端页面对应关系"
      subTitle="默认聚合展示 F-01～F-15、P-01～P-06；点击父指标展开原子指标，点击页面节点进入原型。"
      extra={[
        <Button
          key="expand"
          icon={expandedParent ? <CompressOutlined /> : <ExpandOutlined />}
          onClick={() => setExpandedParent(undefined)}
        >
          收起原子指标
        </Button>,
      ]}
    >
      <Row gutter={[16, 16]}>
        <Col span={24} className="prototype-traceability-filter">
          <Card>
            <Space wrap size={[12, 12]}>
              <FilterOutlined />
              <Select
                allowClear
                placeholder="一级菜单"
                style={{ width: 160 }}
                value={firstMenu}
                onChange={setFirstMenu}
                options={firstMenus.map((value) => ({ value, label: value }))}
              />
              <Select
                allowClear
                placeholder="技术模块"
                style={{ width: 160 }}
                value={technicalModule}
                onChange={setTechnicalModule}
                options={technicalModules.map((value) => ({
                  value,
                  label: value,
                }))}
              />
              <Select
                allowClear
                placeholder="实现方式"
                style={{ width: 160 }}
                value={implementationStatus}
                onChange={setImplementationStatus}
                options={Object.entries(implementationLabels).map(
                  ([value, label]) => ({ value, label }),
                )}
              />
              {Object.entries(implementationLabels).map(([status, label]) => (
                <Tag
                  key={status}
                  color={
                    implementationColors[status as ImplementationStatus]
                  }
                >
                  {label}
                </Tag>
              ))}
              <Typography.Text type="secondary">
                当前显示 {visiblePages.length} / 20 个二级菜单
              </Typography.Text>
            </Space>
          </Card>
        </Col>
        <Col span={24} className="prototype-traceability-graph">
          <Card styles={{ body: { padding: 0 } }}>
            <div style={{ height: 'calc(100vh - 300px)', minHeight: 620 }}>
              <ReactFlow
                nodes={graph.nodes}
                edges={graph.edges}
                fitView
                minZoom={0.15}
                onNodeClick={(_, node) => {
                  setSelectedNode(node.id);
                  if (node.id.startsWith('parent:')) {
                    setExpandedParent(node.id.replace('parent:', ''));
                  }
                  if (node.id.startsWith('page:')) {
                    const page = prototypePageRegistry.find(
                      ({ id }) => id === node.id.replace('page:', ''),
                    );
                    if (page) history.push(page.route);
                  }
                }}
              >
                <Background gap={24} />
                <MiniMap pannable zoomable />
                <Controls />
                <Panel position="top-left">
                  <Tag icon={<ApartmentOutlined />} color="blue">
                    点击指标展开 · 点击页面跳转 · 点击节点高亮连线
                  </Tag>
                </Panel>
              </ReactFlow>
            </div>
          </Card>
        </Col>
      </Row>
    </PageContainer>
  );
};

export default TraceabilityPage;
