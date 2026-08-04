import {
  Box,
  Cloud,
  Database,
  File,
  FileKey,
  Globe2,
  Radio,
} from 'lucide-react';
import type { LucideIcon } from 'lucide-react';

interface ConnectorIconProps {
  dbType: string;
  width: string;
  height: string;
}

const iconMap: Record<string, { icon: LucideIcon; color: string }> = {
  kafka: { icon: Radio, color: '#cc3258' },
  kafka_source: { icon: Radio, color: '#cc3258' },
  ftp: { icon: File, color: '#f59e0b' },
  ftpfile: { icon: File, color: '#f59e0b' },
  sftp: { icon: FileKey, color: '#2563eb' },
  sftpfile: { icon: FileKey, color: '#2563eb' },
  s3: { icon: Cloud, color: '#f97316' },
  s3file: { icon: Cloud, color: '#f97316' },
  minio: { icon: Box, color: '#c72c48' },
  http: { icon: Globe2, color: '#0ea5e9' },
  h2: { icon: Database, color: '#2563eb' },
};

const ConnectorIcon = ({ dbType, width, height }: ConnectorIconProps) => {
  const config = iconMap[dbType.trim().toLowerCase()];

  if (!config) return null;

  const Icon = config.icon;
  return <Icon width={width} height={height} color={config.color} strokeWidth={1.8} />;
};

export default ConnectorIcon;
