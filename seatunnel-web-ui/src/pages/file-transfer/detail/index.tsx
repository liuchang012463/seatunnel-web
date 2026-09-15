import TaskDetailWizard, { FILE_TRANSFER_DETAIL_CONFIG } from '../../file-ingest/TaskDetailWizard';

const FileTransferDetailPage: React.FC = () => (
  <TaskDetailWizard config={FILE_TRANSFER_DETAIL_CONFIG} />
);

export default FileTransferDetailPage;
