import { DATA_SOURCE_REGISTRY } from '@/pages/data-source/dataSourceRegistry';
import DatabaseIcons from '@/pages/data-source/icon/DatabaseIcons';

export interface FileSourceTypeOption {
  value: string;
  connectorType: string;
  pluginName: string;
  rawLabel: string;
  label: React.ReactNode;
  sourceManaged?: boolean;
}

const toOption = (item: (typeof DATA_SOURCE_REGISTRY)[number]): FileSourceTypeOption => ({
  value: item.dbType,
  connectorType: item.connectorType,
  pluginName: item.connectorType,
  rawLabel: item.label,
  label: (
    <div style={{ display: 'flex', alignItems: 'center' }}>
      <DatabaseIcons dbType={item.dbType} width="24px" height="24px" />
      <span style={{ marginLeft: 8 }}>{item.label}</span>
    </div>
  ),
});

const WEB_UPLOAD_OPTION: FileSourceTypeOption = {
  value: 'WEB_UPLOAD',
  connectorType: 'S3File',
  pluginName: 'S3File',
  rawLabel: '本地文件（Web 上传）',
  sourceManaged: true,
  label: (
    <div style={{ display: 'flex', alignItems: 'center' }}>
      <DatabaseIcons dbType="MINIO" width="24px" height="24px" />
      <span style={{ marginLeft: 8 }}>本地文件（Web 上传）</span>
    </div>
  ),
};

/**
 * 文件引接来源类型：浏览器本地上传（平台内置 MinIO）+ 远程文件。
 */
export const generateFileTypeSourceOptions = (): FileSourceTypeOption[] =>
  [WEB_UPLOAD_OPTION, ...DATA_SOURCE_REGISTRY
    .filter((item) => item.category === 'FILE_TRANSFER')
    .map(toOption)];

/** 文件引接去向类型（远程文件系统/对象存储）。 */
export const generateFileTypeTargetOptions = (): FileSourceTypeOption[] =>
  DATA_SOURCE_REGISTRY.filter(
    (item) => item.category === 'FILE_TRANSFER',
  ).map(toOption);
