import { BellOutlined } from '@ant-design/icons';
import { useIntl } from '@umijs/max';
import React from 'react';

const AlarmPageHeader: React.FC = () => {
  const intl = useIntl();

  return (
    <div className="alarm-page__header flex flex-col gap-5 lg:flex-row lg:items-end lg:justify-between">
      <div className="min-w-0">
        <div className="mb-2 flex items-center gap-3">
          <div className="alarm-page__header-icon flex h-11 w-11 shrink-0 items-center justify-center rounded-2xl">
            <BellOutlined style={{ fontSize: 22 }} />
          </div>

          <h1 className="alarm-page__header-title m-0 truncate text-[26px] font-bold leading-8 tracking-[-0.02em]">
            {intl.formatMessage({
              id: 'pages.alarm.header.title',
              defaultMessage: '告警管理',
            })}
          </h1>
        </div>

        <p className="alarm-page__header-description m-0 max-w-[780px] text-sm leading-6">
          {intl.formatMessage({
            id: 'pages.alarm.header.desc',
            defaultMessage:
              '统一管理告警通道、告警规则与投递记录，让任务异常第一时间被感知。',
          })}
        </p>
      </div>
    </div>
  );
};

export default AlarmPageHeader;
