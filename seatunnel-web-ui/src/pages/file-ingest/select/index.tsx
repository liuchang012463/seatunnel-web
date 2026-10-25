import {
  ArrowRightOutlined,
  DatabaseOutlined,
  FileExcelOutlined,
  FileZipOutlined,
  PartitionOutlined,
  WarningOutlined,
} from '@ant-design/icons';
import { history } from '@umijs/max';
import { App, Tooltip } from 'antd';
import type { ReactNode } from 'react';
import PageHeader from '@/pages/batch-link-up/detail/components/PageHeader';
import { openPrettyNotification } from '@/utils/prettyNotification';
import { fileIngestTaskApi } from '../api';
import { FILE_RESOURCE_SOURCE, fileTaskDraftKey } from '../types';
import './index.less';

interface ImportChoice {
  key: string;
  title: string;
  description: string;
  icon: ReactNode;
  iconClassName: string;
  tags: Array<{ label: string; disabled?: boolean }>;
  action?: () => void;
  disabled?: boolean;
  disabledHint?: string;
}

/** Shared entry for the canvas-based structured/DuckDB flows. */
const enterFileIngestWizard = async (createButtonText: string) => {
  const response = await fileIngestTaskApi.getUniqueId();
  const id = response?.data as any;
  if (response?.code !== 0 || !id) {
    throw new Error(response?.message || '申请任务定义 ID 失败');
  }
  sessionStorage.setItem(
    fileTaskDraftKey('FILE_INGEST', String(id)),
    JSON.stringify({
      id,
      taskType: 'FILE_INGEST',
      mode: 'GUIDE_SINGLE',
      sourceType: FILE_RESOURCE_SOURCE,
      targetType: {
        dbType: 'MYSQL',
        connectorType: 'Jdbc',
        pluginName: 'JDBC-MYSQL',
      },
    }),
  );
  history.push(`/sync/file-ingest/${id}/detail?scene=create&from=${createButtonText}`);
};

const ImportSelectPage: React.FC = () => {
  const { message } = App.useApp();

  const choices: ImportChoice[] = [
    {
      key: 'structured',
      title: '结构化数据导入',
      description: '上传或选择结构化文件，导入到目标库表',
      icon: <FileExcelOutlined />,
      iconClassName: 'import-select-card__icon--structured',
      tags: [
        { label: 'Excel' },
        { label: 'CSV' },
        { label: 'JSON' },
        { label: 'TXT' },
      ],
      action: () => {
        enterFileIngestWizard('structured').catch((error: any) => {
          message.error(error?.message || '新建离线文件导入任务失败');
        });
      },
    },
    {
      key: 'dmp',
      title: 'DMP 数据导入',
      description: '使用厂商还原工具导入数据库备份文件',
      icon: <DatabaseOutlined />,
      iconClassName: 'import-select-card__icon--dmp',
      tags: [
        { label: '人大金仓 KingbaseES DMP' },
        { label: '达梦 DM DMP' },
      ],
      action: () => history.push('/sync/file-ingest/import/create?type=DMP'),
    },
    {
      key: 'sql',
      title: 'SQL 数据导入',
      description: '解析 SQL 脚本文件并在目标库执行',
      icon: <PartitionOutlined />,
      iconClassName: 'import-select-card__icon--sql',
      tags: [
        { label: '人大金仓 KingbaseES SQL' },
        { label: '达梦 DM SQL' },
        { label: 'Oracle SQL', disabled: true },
      ],
      action: () => history.push('/sync/file-ingest/import/create?type=SQL'),
    },
    {
      key: 'package',
      title: '离线数据包导入',
      description: '接入离线三级采报工具导出的数据包',
      icon: <FileZipOutlined />,
      iconClassName: 'import-select-card__icon--package',
      tags: [{ label: '离线三级采报工具' }],
      disabled: true,
      disabledHint: '已在采报工具项目实现接入，当前入口待开放',
    },
    {
      key: 'duckdb',
      title: 'DuckDB 数据导入',
      description: '读取 DuckDB 数据库文件并导入目标库',
      icon: <FileZipOutlined />,
      iconClassName: 'import-select-card__icon--duckdb',
      tags: [{ label: 'DuckDB' }],
      action: () => {
        enterFileIngestWizard('duckdb').catch((error: any) => {
          message.error(error?.message || '新建离线文件导入任务失败');
        });
      },
    },
  ];

  const handleChoice = (choice: ImportChoice) => {
    if (choice.disabled) {
      openPrettyNotification({
        type: 'info',
        title: '功能提示',
        description: choice.disabledHint || '该导入方式暂未开放',
      });
      return;
    }
    choice.action?.();
  };

  return (
    <div className="import-select-page">
      <PageHeader onBack={() => history.push('/sync/file-ingest')} title="离线数据导入" />

      <div className="import-select-page__body">
        <div className="import-select-page__heading">
          <h2 className="import-select-page__title">选择导入类型</h2>
          <p className="import-select-page__subtitle">
            请选择数据导入方式，进入对应任务的创建流程
          </p>
        </div>

        <div className="import-select-page__grid">
          {choices.map((choice) => {
            const card = (
              <div
                className={[
                  'import-select-card',
                  choice.disabled ? 'import-select-card--disabled' : '',
                ]
                  .filter(Boolean)
                  .join(' ')}
                onClick={() => handleChoice(choice)}
              >
                <div className={`import-select-card__icon ${choice.iconClassName}`}>
                  {choice.icon}
                </div>
                <div className="import-select-card__main">
                  <div className="import-select-card__title">
                    {choice.title}
                    {choice.disabled && (
                      <span className="import-select-card__badge">
                        <WarningOutlined /> 待开放
                      </span>
                    )}
                  </div>
                  <div className="import-select-card__description">{choice.description}</div>
                  <div className="import-select-card__tags">
                    {choice.tags.map((tag) => (
                      <span
                        key={tag.label}
                        className={[
                          'import-select-card__tag',
                          tag.disabled ? 'import-select-card__tag--disabled' : '',
                        ]
                          .filter(Boolean)
                          .join(' ')}
                      >
                        {tag.label}
                        {tag.disabled ? '（待支持）' : ''}
                      </span>
                    ))}
                  </div>
                </div>
                {!choice.disabled && (
                  <div className="import-select-card__arrow">
                    <ArrowRightOutlined />
                  </div>
                )}
              </div>
            );
            return choice.disabled ? (
              <Tooltip key={choice.key} title={choice.disabledHint}>
                {card}
              </Tooltip>
            ) : (
              <div key={choice.key}>{card}</div>
            );
          })}
        </div>
      </div>
    </div>
  );
};

export default ImportSelectPage;
