import FileResourceSourceCard from '../file-ingest/FileResourceSourceCard';

interface FileTransferSourceCardProps {
  sourceConfig: Record<string, any>;
  onChange: (patch: Record<string, any>) => void;
  onOpenManager?: () => void;
}

/** Resource-backed source for binary transfer; remote sources stay in the existing canvas editor. */
const FileTransferSourceCard: React.FC<FileTransferSourceCardProps> = ({
  sourceConfig,
  onChange,
  onOpenManager,
}) => (
  <FileResourceSourceCard
    sourceConfig={sourceConfig}
    onChange={onChange}
    onOpenManager={onOpenManager}
    selectionMode="file"
    title="同步来源"
    description="选择湖文件区中的文件；执行节点直接读取对象存储。"
    binary
  />
);

export default FileTransferSourceCard;
