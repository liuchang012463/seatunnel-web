import type { LakeReadOnlyQueryResult } from '@/services/lake';
import { Table, Typography } from 'antd';
import React from 'react';

export type DisplayQueryResult = LakeReadOnlyQueryResult & {
  columnTitles?: Record<string, string>;
};

const QueryRows: React.FC<{ result: DisplayQueryResult }> = ({ result }) => {
  const columns = result.columns.map((key) => ({
    title: result.columnTitles?.[key] || key,
    dataIndex: ['values', key],
    key,
    ellipsis: true,
    render: (value: unknown) => {
      if (value == null) return <Typography.Text type="secondary">NULL</Typography.Text>;
      return typeof value === 'object' ? JSON.stringify(value) : String(value);
    },
  }));
  return (
    <>
      <Typography.Text type="secondary" className="lake-catalog-preview-meta">
        返回 {result.rowCount} 行
        {result.truncated ? ' · 已达到结果上限' : ''}
      </Typography.Text>
      <Table
        dataSource={result.rows.map((values, key) => ({ key, values }))}
        columns={columns}
        pagination={false}
        size="small"
        scroll={{ x: Math.max(620, columns.length * 150) }}
        locale={{ emptyText: '查询结果为空' }}
      />
    </>
  );
};

export default QueryRows;
