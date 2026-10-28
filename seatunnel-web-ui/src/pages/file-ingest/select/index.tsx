import { ArrowRightOutlined } from '@ant-design/icons';
import { history } from '@umijs/max';
import { App } from 'antd';
import { useMemo } from 'react';
import background from '@/assets/offline-import/background.png';
import network from '@/assets/offline-import/network.png';
import structuredArt from '@/assets/offline-import/structured.png';
import dmpArt from '@/assets/offline-import/dmp.png';
import sqlArt from '@/assets/offline-import/sql.png';
import packageArt from '@/assets/offline-import/package.png';
import duckdbArt from '@/assets/offline-import/duckdb.png';
import structuredDivider from '@/assets/offline-import/divider-structured.png';
import dmpDivider from '@/assets/offline-import/divider-dmp.png';
import sqlDivider from '@/assets/offline-import/divider-sql.png';
import packageDivider from '@/assets/offline-import/divider-package.png';
import duckdbDivider from '@/assets/offline-import/divider-duckdb.png';
import { openPrettyNotification } from '@/utils/prettyNotification';
import { fileIngestTaskApi } from '../api';
import { FILE_RESOURCE_SOURCE, fileTaskDraftKey } from '../types';
import './index.less';

interface ImportEntry {
  key: string;
  label: string;
  detail: string;
  action?: () => void;
  disabled?: boolean;
  notifyOnClick?: boolean;
  disabledHint?: string;
}

interface ImportChoice {
  key: string;
  title: string;
  subtitle: string;
  artwork: string;
  divider: string;
  entries: ImportEntry[];
  note?: string;
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

  const choices = useMemo<ImportChoice[]>(() => {
    const enterStructured = () => {
      enterFileIngestWizard('structured').catch((error: any) => {
        message.error(error?.message || '新建离线文件导入任务失败');
      });
    };
    const enterDuckdb = () => {
      enterFileIngestWizard('duckdb').catch((error: any) => {
        message.error(error?.message || '新建离线文件导入任务失败');
      });
    };

    return [
      {
        key: 'structured',
        title: '结构化数据导入',
        subtitle: '轻量文件，快速接入',
        artwork: structuredArt,
        divider: structuredDivider,
        entries: [
          { key: 'excel', label: 'Excel 文件', detail: '.xlsx / .xls', action: enterStructured },
          { key: 'csv', label: 'CSV 文件', detail: '.csv', action: enterStructured },
          { key: 'json', label: 'JSON 文件', detail: '.json', action: enterStructured },
          { key: 'txt', label: 'TXT 文件', detail: '.txt', action: enterStructured },
        ],
      },
      {
        key: 'dmp',
        title: 'DMP 数据导入',
        subtitle: '数据库备份，离线迁移',
        artwork: dmpArt,
        divider: dmpDivider,
        note: '选择与备份文件对应的源数据库，进入专属导入配置。',
        entries: [
          {
            key: 'kingbase-dmp',
            label: '人大金仓',
            detail: 'KingbaseES · DMP',
            action: () => history.push('/sync/file-ingest/import/create?type=DMP&importType=KINGBASE_DMP'),
          },
          {
            key: 'dameng-dmp',
            label: '达梦',
            detail: 'DM · DMP',
            action: () => history.push('/sync/file-ingest/import/create?type=DMP&importType=DAMENG_DMP'),
          },
        ],
      },
      {
        key: 'sql',
        title: 'SQL 数据导入',
        subtitle: '多源脚本，统一导入',
        artwork: sqlArt,
        divider: sqlDivider,
        entries: [
          {
            key: 'kingbase-sql',
            label: '人大金仓',
            detail: 'KingbaseES · SQL',
            action: () => history.push('/sync/file-ingest/import/create?type=SQL&importType=KINGBASE_SQL'),
          },
          {
            key: 'dameng-sql',
            label: '达梦',
            detail: 'DM · SQL',
            action: () => history.push('/sync/file-ingest/import/create?type=SQL&importType=DAMENG_SQL'),
          },
          {
            key: 'oracle-sql',
            label: 'Oracle',
            detail: 'Oracle · SQL',
            disabled: true,
            disabledHint: 'Oracle SQL 导入待支持',
          },
        ],
      },
      {
        key: 'package',
        title: '离线数据包导入',
        subtitle: '采报数据，一站接入',
        artwork: packageArt,
        divider: packageDivider,
        note: '接入离线三级采报工具导出的文件，衔接离线采集与平台管理。',
        entries: [
          {
            key: 'report-package',
            label: '离线三级采报工具',
            detail: '导入工具导出的离线文件',
            disabled: true,
            notifyOnClick: true,
            disabledHint: '已在采报工具项目实现接入，当前入口待开放',
          },
        ],
      },
      {
        key: 'duckdb',
        title: 'DuckDB 数据导入',
        subtitle: '本地数据库，便捷接入',
        artwork: duckdbArt,
        divider: duckdbDivider,
        note: '将已有 DuckDB 数据库文件接入平台，统一管理离线数据。',
        entries: [
          {
            key: 'duckdb-file',
            label: 'DuckDB',
            detail: '本地 DuckDB 数据库文件',
            action: enterDuckdb,
          },
        ],
      },
    ];
  }, [message]);

  const handleEntry = (entry: ImportEntry) => {
    if (entry.disabled) {
      if (entry.notifyOnClick) {
        openPrettyNotification({
          type: 'info',
          title: '功能提示',
          description: entry.disabledHint || '该导入方式暂未开放',
        });
      }
      return;
    }
    entry.action?.();
  };

  return (
    <div className="import-select-page" style={{ backgroundImage: `url(${background})` }}>
      <main className="import-select-page__body">
        <div className="import-select-page__hero">
          <div className="import-select-page__heading">
            <h1 className="import-select-page__title">离线数据导入</h1>
            <p className="import-select-page__subtitle">多格式文件 · 多类型数据库 · 一个统一的数据入口</p>
          </div>

          <img className="import-select-page__network" src={network} alt="" />

          <div className="import-select-page__stats" aria-label="5 种导入类型，11 个数据入口">
            <div className="import-select-page__stat">
              <strong>05</strong>
              <span>导入类型</span>
            </div>
            <div className="import-select-page__stat">
              <strong>11</strong>
              <span>数据入口</span>
            </div>
          </div>
        </div>

        <div className="import-select-page__grid">
          {choices.map((choice) => (
            <article className={`import-select-card import-select-card--${choice.key}`} key={choice.key}>
              <div className="import-select-card__visual">
                <img className="import-select-card__artwork" src={choice.artwork} alt="" />
                <h2 className="import-select-card__title">{choice.title}</h2>
                <p className="import-select-card__subtitle">{choice.subtitle}</p>
              </div>

              <img className="import-select-card__divider" src={choice.divider} alt="" />

              <div className="import-select-card__entries">
                {choice.entries.map((entry) => (
                  <div className={`import-select-card__entry${entry.disabled ? ' import-select-card__entry--disabled' : ''}`} key={entry.key}>
                    <div className="import-select-card__entry-copy">
                      <strong>{entry.label}</strong>
                      <span>{entry.detail}</span>
                    </div>
                    <button
                      className="import-select-card__enter"
                      type="button"
                      disabled={Boolean(entry.disabled && !entry.notifyOnClick)}
                      aria-disabled={entry.disabled || undefined}
                      title={entry.disabledHint}
                      onClick={() => handleEntry(entry)}
                    >
                      进入 <ArrowRightOutlined />
                    </button>
                  </div>
                ))}
              </div>

              {choice.note && <p className="import-select-card__note">{choice.note}</p>}
            </article>
          ))}
        </div>
      </main>
    </div>
  );
};

export default ImportSelectPage;
