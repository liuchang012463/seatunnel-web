import { DATA_SOURCE_REGISTRY } from '@/pages/data-source/dataSourceRegistry';
import DatabaseIcons from '@/pages/data-source/icon/DatabaseIcons';

export interface FileSourceTypeOption {
  value: string;
  connectorType: string;
  pluginName: string;
  rawLabel: string;
  label: React.ReactNode;
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

/**
 * 文件引接来源类型：本地上传（LocalFile）+ 远程文件（FTP/SFTP/S3/MinIO）。
 * 本地文件放在首位，作为本地上传的默认选择。
 */
export const generateFileTypeSourceOptions = (): FileSourceTypeOption[] =>
  DATA_SOURCE_REGISTRY.filter((item) => item.category === 'FILE_TRANSFER')
    .sort((a, b) => (a.dbType === 'LOCAL_FILE' ? -1 : b.dbType === 'LOCAL_FILE' ? 1 : 0))
    .map(toOption);

/** 文件引接去向类型（远程文件系统/对象存储）。 */
export const generateFileTypeTargetOptions = (): FileSourceTypeOption[] =>
  DATA_SOURCE_REGISTRY.filter(
    (item) => item.category === 'FILE_TRANSFER' && item.dbType !== 'LOCAL_FILE',
  ).map(toOption);
