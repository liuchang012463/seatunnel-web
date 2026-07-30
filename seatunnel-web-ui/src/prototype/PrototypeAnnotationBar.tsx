import {
  ApartmentOutlined,
  ReloadOutlined,
  SafetyCertificateOutlined,
} from '@ant-design/icons';
import { history, useLocation } from '@umijs/max';
import { Button, Space, Tag, Tooltip, message } from 'antd';
import React from 'react';
import {
  findPageMeta,
  implementationColors,
  implementationLabels,
} from './registry';
import { resetPrototypeData } from './store';

const PrototypeAnnotationBar: React.FC<React.PropsWithChildren> = ({
  children,
}) => {
  const location = useLocation();
  const meta = findPageMeta(location.pathname);

  const handleReset = () => {
    resetPrototypeData();
    message.success('原型数据已重置');
    window.location.reload();
  };

  return (
    <>
      <div
        style={{
          margin: '12px 24px 0',
          padding: '12px 16px',
          color: 'var(--st-color-text-primary, #fff)',
          borderRadius: 'var(--st-radius-md, 4px)',
          border: '1px solid var(--st-color-border, #2187a8)',
          background: 'var(--st-color-bg-secondary, #002e41)',
          boxShadow: 'none',
        }}
      >
        <Space wrap size={[8, 8]}>
          <Tag color="geekblue" icon={<SafetyCertificateOutlined />}>
            纯前端可交互原型 · Mock SSO
          </Tag>
          {meta ? (
            <>
              <strong>
                {meta.firstMenu} / {meta.secondMenu}
              </strong>
              <Tag color={implementationColors[meta.implementationStatus]}>
                {implementationLabels[meta.implementationStatus]}
              </Tag>
              <Tooltip title={meta.requirementIds.join('、')}>
                <Tag>{meta.requirementIds.length} 项指标</Tag>
              </Tooltip>
              <span style={{ color: 'var(--st-color-text-secondary, #d5d5d5)' }}>
                {meta.source}
              </span>
            </>
          ) : (
            <strong>数据采集引接软件：合同指标—前端页面对应关系</strong>
          )}
          <Button
            size="small"
            icon={<ApartmentOutlined />}
            onClick={() => history.push('/prototype/traceability')}
          >
            查看关系图
          </Button>
          <Button
            size="small"
            icon={<ReloadOutlined />}
            onClick={handleReset}
          >
            重置原型数据
          </Button>
        </Space>
      </div>
      {children}
    </>
  );
};

export default PrototypeAnnotationBar;
