import { FolderOpenOutlined } from '@ant-design/icons';
import { Button, List, Modal, Spin } from 'antd';
import { useCallback, useEffect, useState } from 'react';
import { dataSourceCatalogApi } from '@/pages/data-source/service';

interface RemoteEntry {
  name: string;
  path: string;
  type: 'DIRECTORY' | 'FILE' | 'LINK';
  size?: number;
}

interface DirectoryPickerModalProps {
  open: boolean;
  datasourceId?: string;
  title?: string;
  onCancel: () => void;
  onSelect: (path: string) => void;
  refreshToken?: number;
}

/** 复用目录浏览接口的通用目录选择弹窗。 */
const DirectoryPickerModal: React.FC<DirectoryPickerModalProps> = ({
  open,
  datasourceId,
  title = '选择目录',
  onCancel,
  onSelect,
  refreshToken,
}) => {
  const [entries, setEntries] = useState<RemoteEntry[]>([]);
  const [currentPath, setCurrentPath] = useState<string | undefined>(undefined);
  const [loading, setLoading] = useState(false);

  const browse = useCallback(
    async (path?: string) => {
      if (!datasourceId) return;
      setLoading(true);
      try {
        const res = await dataSourceCatalogApi.listFiles(datasourceId, path);
        setEntries(res?.data || []);
        setCurrentPath(path);
      } catch (error: any) {
        setEntries([]);
      } finally {
        setLoading(false);
      }
    },
    [datasourceId],
  );

  useEffect(() => {
    if (open && datasourceId) {
      browse(currentPath);
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [open, datasourceId, refreshToken]);

  return (
    <Modal
      open={open}
      title={`${title} · ${currentPath || '根目录'}`}
      footer={null}
      onCancel={onCancel}
      width={560}
    >
      {loading ? (
        <div className="flex items-center justify-center py-10">
          <Spin />
        </div>
      ) : (
        <List
          dataSource={entries}
          locale={{ emptyText: '目录为空' }}
          renderItem={(entry) => (
            <List.Item
              actions={
                entry.type === 'DIRECTORY'
                  ? [
                      <Button key="enter" type="link" onClick={() => browse(entry.path)}>
                        进入
                      </Button>,
                      <Button
                        key="select"
                        type="link"
                        onClick={() => {
                          onSelect(entry.path);
                          onCancel();
                        }}
                      >
                        选择
                      </Button>,
                    ]
                  : []
              }
            >
              <List.Item.Meta
                avatar={
                  <FolderOpenOutlined
                    className={entry.type === 'DIRECTORY' ? 'text-amber-500' : 'text-slate-400'}
                  />
                }
                title={entry.name}
                description={entry.path}
              />
            </List.Item>
          )}
        />
      )}
    </Modal>
  );
};

export default DirectoryPickerModal;
