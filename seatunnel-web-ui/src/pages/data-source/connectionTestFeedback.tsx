import { appMessage } from '@/utils/antdFeedback';
import { Modal } from 'antd';
import React from 'react';
import { getDataSourceConnectionTestAdvice } from './connectionError';

export interface PresentConnectionTestResultOptions {
  success: boolean;
  /** 已脱敏的失败原因，或成功时的补充说明 */
  detail: string;
  dataSourceName?: string;
  /** 列表页失败时提供：引导去编辑连接配置 */
  onEdit?: () => void;
}

/**
 * 成功短暂提示；失败保留原因、排查建议和编辑入口。
 */
export function presentConnectionTestResult(options: PresentConnectionTestResultOptions) {
  const name = options.dataSourceName?.trim();
  const nameLabel = name ? `「${name}」` : '当前数据源';

  if (options.success) {
    appMessage.success(`${nameLabel}连接测试通过`, 3);
    return;
  }

  const advice = getDataSourceConnectionTestAdvice(options.detail);
  const canEdit = typeof options.onEdit === 'function';

  Modal.confirm({
    title: '连接测试未通过',
    centered: true,
    width: 560,
    okText: canEdit ? '去修改连接配置' : '知道了',
    cancelText: '关闭',
    okCancel: canEdit,
    okButtonProps: canEdit ? { type: 'primary' } : undefined,
    onOk: () => {
      options.onEdit?.();
    },
    content: (
      <div style={{ fontSize: 14, lineHeight: 1.65 }}>
        <p style={{ margin: '0 0 8px' }}>
          {nameLabel}未能连通，请根据下方原因排查后重试。
        </p>
        <div
          style={{
            marginBottom: 12,
            padding: '10px 12px',
            borderRadius: 6,
            background: 'rgba(240, 31, 31, 0.08)',
            border: '1px solid rgba(240, 31, 31, 0.28)',
            wordBreak: 'break-word',
            whiteSpace: 'pre-wrap',
          }}
        >
          <div style={{ marginBottom: 4, color: 'var(--st-color-text-muted, #8aa0b8)' }}>失败原因</div>
          <div>{options.detail || '连接器未返回具体原因'}</div>
        </div>
        <div style={{ color: 'var(--st-color-text-muted, #8aa0b8)', marginBottom: 6 }}>建议排查</div>
        <ul style={{ margin: 0, paddingLeft: 20 }}>
          {advice.map((item) => (
            <li key={item} style={{ marginBottom: 4 }}>
              {item}
            </li>
          ))}
        </ul>
      </div>
    ),
  });
}
