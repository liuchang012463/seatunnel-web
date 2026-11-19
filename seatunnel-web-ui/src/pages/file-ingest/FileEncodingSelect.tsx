import { Select } from 'antd';
import React from 'react';

const TEXT_ENCODING_OPTIONS = [
  { value: 'UTF-8', label: 'UTF-8' },
  { value: 'GB2312', label: 'GB 2312（简体中文）' },
  { value: 'GBK', label: 'GBK' },
  { value: 'GB18030', label: 'GB18030' },
];

const EXCEL_ENCODING_OPTIONS = [{ value: 'auto', label: '按工作簿格式自动读取' }];

interface FileEncodingSelectProps {
  value?: string;
  onChange: (encoding: string) => void;
  workbook?: boolean;
}

export default function FileEncodingSelect({ value, onChange, workbook }: FileEncodingSelectProps) {
  const encoding = value || 'UTF-8';
  // 已保存的任务可能通过原文本输入框配置了其他 Java 字符集。
  const textOptions = TEXT_ENCODING_OPTIONS.some((option) => option.value === encoding)
    ? TEXT_ENCODING_OPTIONS
    : [...TEXT_ENCODING_OPTIONS, { value: encoding, label: encoding }];

  return (
    <label className="block text-sm text-slate-600">
      <span className="mb-1 block">文件编码</span>
      <Select
        aria-label="文件编码"
        className="w-full"
        disabled={workbook}
        value={workbook ? 'auto' : encoding}
        options={workbook ? EXCEL_ENCODING_OPTIONS : textOptions}
        onChange={onChange}
      />
    </label>
  );
}
