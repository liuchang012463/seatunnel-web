import { Modal } from 'antd';
import React from 'react';

/** 仅用于主动提交失败；表单字段校验、后台轮询不应阻断用户。 */
export function presentOperationFailure(title: string, reason: unknown) {
  const detail = reason instanceof Error ? reason.message : typeof reason === 'string' ? reason : title;
  // 不输出请求参数，避免敏感连接配置进入日志。
  console.error('[operation-failure]', title);
  Modal.error({
    title, centered: true, width: 560, okText: '知道了',
    content: <div style={{ whiteSpace: 'pre-wrap', overflowWrap: 'anywhere' }}>{detail || title}</div>,
  });
}
