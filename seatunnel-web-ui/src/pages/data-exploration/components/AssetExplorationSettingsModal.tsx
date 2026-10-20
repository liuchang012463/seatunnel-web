import { Alert, Button, Input, Modal, Space, Spin, Switch, Tag, message } from 'antd';
import React, { useCallback, useEffect, useRef, useState } from 'react';
import {
  fetchDataSourceMetadataStatus,
  retryDataSourceMetadataSync,
  triggerDataSourceScan,
  updateDataSourceSampleData,
  updateDataSourceStorageManifest,
} from '@/pages/data-source/service';
import type { DataSourceMetadataStatus } from '@/pages/data-source/types';
import { normalizeExplorationDbType } from '@/utils/assetExplorationStatus';

export interface AssetExplorationSettingsModalProps {
  open: boolean;
  dataSourceId?: string;
  dataSourceName?: string;
  dbType?: string;
  onClose: () => void;
  onSaved?: () => void;
}

function sampleDescription(dbType?: string): string {
  switch (normalizeExplorationDbType(dbType)) {
    case 'KAFKA':
      return '扫描时读取主题最近消息（约 10 条），写入 OpenMetadata。';
    case 'ELASTICSEARCH':
      return '扫描时读取索引样本文档，最多 10 条，写入 OpenMetadata。';
    case 'SFTP':
    case 'FTP':
      return '扫描时下载 CSV/TSV 文件内容，最多 50 行；大文件会带来 IO 开销，非 CSV/TSV 不采样。';
    case 'MINIO':
    case 'S3':
      return '仅对结构化容器采集；需先配置容器清单，扫描后由 AutoClassification 采集。';
    default:
      return '开启后，扫描会读取真实内容并写入 OpenMetadata。';
  }
}

function validateManifest(value: string): string | undefined {
  if (!value.trim()) {
    return undefined;
  }
  try {
    const parsed = JSON.parse(value);
    if (!parsed || typeof parsed !== 'object' || !Array.isArray(parsed.entries)) {
      return '清单必须是包含 entries 数组的 JSON 对象。';
    }
    return undefined;
  } catch {
    return '清单不是合法的 JSON。';
  }
}

function syncStatusLabel(status?: string): string {
  switch (String(status || '').toUpperCase()) {
    case 'READY':
      return '已就绪';
    case 'PENDING':
      return '待同步';
    case 'SYNCING':
      return '同步中';
    case 'WAITING':
      return '等待同步';
    case 'ERROR':
      return '同步异常';
    default:
      return '未初始化';
  }
}

const AssetExplorationSettingsModal: React.FC<AssetExplorationSettingsModalProps> = ({
  open,
  dataSourceId,
  dataSourceName,
  dbType,
  onClose,
  onSaved,
}) => {
  const [loading, setLoading] = useState(false);
  const [loadError, setLoadError] = useState<string>();
  const [status, setStatus] = useState<DataSourceMetadataStatus>();
  const [sampleEnabled, setSampleEnabled] = useState(false);
  const [manifest, setManifest] = useState('');
  const [saving, setSaving] = useState(false);
  const [syncing, setSyncing] = useState(false);
  const [retrying, setRetrying] = useState(false);
  const [syncError, setSyncError] = useState<string>();
  const [manifestError, setManifestError] = useState<string>();
  const [savedReady, setSavedReady] = useState(false);
  const pollToken = useRef(0);

  const loadStatus = useCallback(async () => {
    if (!dataSourceId) return;
    setLoading(true);
    setLoadError(undefined);
    try {
      const response = await fetchDataSourceMetadataStatus(dataSourceId);
      if (response.code !== 0 || !response.data) {
        setLoadError(response.message || '无法读取探查设置');
        return;
      }
      setStatus(response.data);
      setSampleEnabled(Boolean(response.data.sampleDataEnabled));
      setManifest(response.data.storageManifest || '');
    } catch (error: any) {
      setLoadError(error?.response?.data?.message || error?.message || '无法读取探查设置');
    } finally {
      setLoading(false);
    }
  }, [dataSourceId]);

  useEffect(() => {
    if (!open) return;
    pollToken.current += 1;
    setStatus(undefined);
    setSampleEnabled(false);
    setManifest('');
    setSyncError(undefined);
    setManifestError(undefined);
    setSavedReady(false);
    void loadStatus();
    return () => {
      pollToken.current += 1;
    };
  }, [loadStatus, open]);

  const sampleSupported = Boolean(status?.sampleDataSupported);
  const manifestSupported = Boolean(status?.storageManifestSupported);
  const sampleChanged = sampleSupported
    && sampleEnabled !== Boolean(status?.sampleDataEnabled);
  const manifestChanged = manifestSupported
    && manifest !== (status?.storageManifest || '');
  const dirty = sampleChanged || manifestChanged;

  const waitForReady = useCallback(async (): Promise<boolean> => {
    if (!dataSourceId) return false;
    const token = pollToken.current;
    setSyncing(true);
    const deadline = Date.now() + 30_000;
    while (Date.now() < deadline) {
      try {
        const response = await fetchDataSourceMetadataStatus(dataSourceId);
        if (token !== pollToken.current) return false;
        if (response.code === 0 && response.data) {
          setStatus(response.data);
          if (response.data.syncStatus === 'READY') {
            setSyncing(false);
            return true;
          }
          if (response.data.syncStatus === 'ERROR') {
            setSyncError('配置同步失败，请点击“重试同步”。');
            setSyncing(false);
            return false;
          }
        }
      } catch {
        // Keep polling; a transient status read must not fail the save.
      }
      await new Promise((resolve) => { window.setTimeout(resolve, 1500); });
    }
    if (token === pollToken.current) {
      setSyncError('配置仍在同步，请稍后刷新。');
      setSyncing(false);
    }
    return false;
  }, [dataSourceId]);

  const retrySync = useCallback(async (): Promise<boolean> => {
    if (!dataSourceId) return false;
    setRetrying(true);
    setSyncError(undefined);
    try {
      const response = await retryDataSourceMetadataSync(dataSourceId);
      if (response.code !== 0) {
        throw new Error(response.message || '重试同步失败');
      }
      const ready = await waitForReady();
      if (ready) {
        message.success('元数据同步已恢复');
        onSaved?.();
      }
      return ready;
    } catch (error: any) {
      const detail = error?.response?.data?.message || error?.message || '重试同步失败';
      setSyncError(detail);
      message.error(detail);
      return false;
    } finally {
      setRetrying(false);
    }
  }, [dataSourceId, onSaved, waitForReady]);

  const runScan = async () => {
    if (!dataSourceId) return;
    const response = await triggerDataSourceScan(dataSourceId);
    if (response.code !== 0) {
      throw new Error(response.message || '重新扫描提交失败');
    }
    message.success('已提交重新扫描');
  };

  const save = async (scanAfterSave: boolean) => {
    if (!dataSourceId) return;
    const nextManifestError = manifestSupported ? validateManifest(manifest) : undefined;
    setManifestError(nextManifestError);
    if (nextManifestError) {
      return;
    }
    setSaving(true);
    setSyncError(undefined);
    let sampleSaved = false;
    try {
      if (!sampleChanged && !manifestChanged) {
        if (scanAfterSave) {
          if (status?.syncStatus === 'ERROR') {
            const ready = await retrySync();
            if (!ready) return;
          } else if (status?.syncStatus !== 'READY') {
            const ready = await waitForReady();
            if (!ready) return;
          }
          await runScan();
          onSaved?.();
          onClose();
        }
        return;
      }
      if (sampleChanged) {
        const response = await updateDataSourceSampleData(dataSourceId, sampleEnabled);
        if (response.code !== 0) {
          throw new Error(response.message || '样本开关保存失败');
        }
        sampleSaved = true;
      }
      if (manifestChanged) {
        const response = await updateDataSourceStorageManifest(dataSourceId, manifest);
        if (response.code !== 0) {
          throw new Error(
            `${sampleSaved ? '样本开关已保存；' : ''}容器清单保存失败：${response.message || ''}`,
          );
        }
      }
      message.success('探查设置已保存，正在同步到 OpenMetadata');
      const ready = await waitForReady();
      if (!ready) {
        onSaved?.();
        return;
      }
      onSaved?.();
      if (scanAfterSave) {
        await runScan();
        onClose();
        return;
      }
      setSavedReady(true);
    } catch (error: any) {
      const detail = error?.response?.data?.message || error?.message || '探查设置保存失败';
      setSyncError(sampleSaved ? `样本开关已保存；${detail}` : detail);
      message.error(detail);
    } finally {
      setSaving(false);
      setSyncing(false);
    }
  };

  const handleCancel = () => {
    if (saving || syncing || retrying) return;
    if (!dirty) {
      onClose();
      return;
    }
    Modal.confirm({
      title: '放弃未保存的修改？',
      content: '样本开关或容器清单尚未保存，关闭后本次修改会丢失。',
      okText: '放弃修改',
      cancelText: '继续编辑',
      okButtonProps: { danger: true },
      onOk: onClose,
    });
  };

  return (
    <Modal
      className="asset-exploration-settings"
      open={open}
      title={`探查设置 · ${dataSourceName || '数据源'}`}
      onCancel={handleCancel}
      footer={null}
      destroyOnHidden
      maskClosable={false}
      closable={!saving && !syncing && !retrying}
      width={620}
    >
      <Spin spinning={loading}>
        {loadError && (
          <Alert
            type="warning"
            showIcon
            message="设置读取失败"
            description={loadError}
            action={<Button size="small" onClick={() => void loadStatus()}>重试</Button>}
          />
        )}
        {!loadError && status && (
          <div className="asset-exploration-settings__body">
            <div className="asset-exploration-settings__status">
              <span>元数据同步</span>
              <Tag color={status.syncStatus === 'READY' ? 'success'
                : status.syncStatus === 'ERROR' ? 'error' : 'processing'}>
                {syncStatusLabel(status.syncStatus)}
              </Tag>
              {status.syncStatus === 'ERROR' && (
                <Button
                  size="small"
                  loading={retrying}
                  disabled={saving || syncing || retrying}
                  onClick={() => void retrySync()}
                >
                  重试同步
                </Button>
              )}
              {status.scan?.lastSuccessTime && (
                <span className="asset-exploration-settings__muted">
                  最近成功扫描 {status.scan.lastSuccessTime}
                </span>
              )}
            </div>

            {sampleSupported && (
              <section className="asset-exploration-settings__section">
                <div className="asset-exploration-settings__section-head">
                  <div>
                    <strong>样本数据采集</strong>
                    <p>{sampleDescription(dbType)}</p>
                  </div>
                  <Switch
                    aria-label="样本数据采集"
                    checked={sampleEnabled}
                    disabled={saving || syncing || retrying || savedReady}
                    onChange={(checked) => {
                      setSampleEnabled(checked);
                      setSavedReady(false);
                    }}
                  />
                </div>
              </section>
            )}

            {manifestSupported && (
              <section className="asset-exploration-settings__section">
                <div className="asset-exploration-settings__section-head">
                  <div>
                    <strong>结构化容器清单</strong>
                    <p>
                      OpenMetadata 用这份清单把普通容器识别成有 Schema 的结构化容器。
                      内容与桶里的 <code>openmetadata.json</code> 相同，必须包含 entries 数组，
                      例如 <code>{'{"entries":[{"dataPath":"orders/**","structureFormat":"parquet"}]}'}</code>。
                      保存后需要重新扫描。
                    </p>
                  </div>
                </div>
                <Input.TextArea
                  aria-label="结构化容器清单"
                  value={manifest}
                  onChange={(event) => {
                    setManifest(event.target.value);
                    setManifestError(undefined);
                    setSavedReady(false);
                  }}
                  autoSize={{ minRows: 4, maxRows: 10 }}
                  placeholder={'{"entries":[]}'}
                  spellCheck={false}
                  disabled={saving || syncing || retrying || savedReady}
                  status={manifestError ? 'error' : undefined}
                />
                {manifestError && <div className="asset-exploration-settings__error">{manifestError}</div>}
              </section>
            )}

            {!sampleSupported && !manifestSupported && (
              <Alert
                type="info"
                showIcon
                message="该数据源没有可配置的探查项"
                description="样本采集与容器清单不适用于当前数据源类型。"
              />
            )}

            {savedReady && (
              <Alert
                type="success"
                showIcon
                message="配置已同步到 OpenMetadata"
                description="样本或容器清单已生效；重新扫描后生成新的元数据与样本。"
                action={(
                  <Space>
                    <Button size="small" onClick={() => void save(true)}>立即扫描</Button>
                    <Button size="small" type="primary" onClick={onClose}>关闭</Button>
                  </Space>
                )}
              />
            )}

            {syncError && (
              <Alert type="error" showIcon message="配置同步" description={syncError} />
            )}
            {syncing && (
              <div className="asset-exploration-settings__syncing">
                <Spin size="small" /> 正在等待 OpenMetadata 配置同步…
              </div>
            )}

            {!savedReady && (
              <Space className="asset-exploration-settings__actions">
                <Button onClick={handleCancel} disabled={saving || syncing || retrying}>取消</Button>
                <Button
                  type="primary"
                  loading={saving && !syncing}
                  disabled={saving || syncing || retrying}
                  onClick={() => void save(false)}
                >
                  保存
                </Button>
                <Button
                  loading={saving && !syncing}
                  disabled={saving || syncing || retrying}
                  onClick={() => void save(true)}
                >
                  保存并扫描
                </Button>
              </Space>
            )}
          </div>
        )}
      </Spin>
    </Modal>
  );
};

export default AssetExplorationSettingsModal;
