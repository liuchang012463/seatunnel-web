import TaskDetailWizard, { FILE_INGEST_DETAIL_CONFIG } from '../TaskDetailWizard';

const FileIngestDetailPage: React.FC = () => (
  <TaskDetailWizard config={FILE_INGEST_DETAIL_CONFIG} />
);

export default FileIngestDetailPage;
