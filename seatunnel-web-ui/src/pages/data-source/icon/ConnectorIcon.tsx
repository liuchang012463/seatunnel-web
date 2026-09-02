import { Database, FolderOpen } from "lucide-react";

import SimpleIcon, { type SimpleIconSlug } from "./SimpleIcon";

interface ConnectorIconProps {
  dbType: string;
  width: string;
  height: string;
}

const iconMap: Record<
  string,
  { slug?: SimpleIconSlug; icon?: typeof Database; color: string }
> = {
  // Kafka's official monochrome mark is nearly black; use a light contrast
  // color here so the downloaded path remains visible on the dark card.
  kafka: { slug: "apachekafka", color: "#e8edf2" },
  kafka_source: { slug: "apachekafka", color: "#e8edf2" },
  ftp: { slug: "filezilla", color: "#bf0000" },
  ftpfile: { slug: "filezilla", color: "#bf0000" },
  sftp: { slug: "filezilla", color: "#bf0000" },
  sftpfile: { slug: "filezilla", color: "#bf0000" },
  s3: { slug: "amazonaws", color: "#ff9900" },
  s3file: { slug: "amazonaws", color: "#ff9900" },
  minio: { slug: "minio", color: "#c72e49" },
  http: { slug: "httpie", color: "#73dc8c" },
  local_file: { icon: FolderOpen, color: "#0ea5e9" },
  localfile: { icon: FolderOpen, color: "#0ea5e9" },
  h2: { icon: Database, color: "#2563eb" },
};

const ConnectorIcon = ({ dbType, width, height }: ConnectorIconProps) => {
  const config = iconMap[dbType.trim().toLowerCase()];

  if (!config) return null;

  if (config.slug) {
    return (
      <SimpleIcon
        slug={config.slug}
        width={width}
        height={height}
        color={config.color}
      />
    );
  }

  const Icon = config.icon || Database;
  return <Icon width={width} height={height} color={config.color} strokeWidth={1.8} />;
};

export default ConnectorIcon;
