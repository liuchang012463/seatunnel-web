import { useIntl } from '@umijs/max';
import { Button, Form, message, Modal } from 'antd';
import { forwardRef, useImperativeHandle, useRef, useState } from 'react';
import { dataSourceGroupList } from '../constants';
import { createDataSource, testDataSourceConnectionWithParams, updateDataSource } from '../service';
import type {
  DataSourceFormValues,
  DataSourceModalOpenPayload,
  DataSourceModalRef,
  DataSourceOperateType,
  DataSourceRecord,
} from '../types';
import { buildSubmitPayload, parseOriginalJson } from '../utils';
import DataSourceTypeSelector from './DataSourceTypeSelector';
import DynamicDataSourceForm from './DynamicDataSourceForm';

interface AddOrEditDataSourceModalProps {
  onManageMasterData?: () => void;
}

const AddOrEditDataSourceModal = forwardRef<DataSourceModalRef, AddOrEditDataSourceModalProps>(({ onManageMasterData }, ref) => {
  const intl = useIntl();

  const [basicForm] = Form.useForm<DataSourceFormValues>();
  const [configForm] = Form.useForm();

  const [open, setOpen] = useState(false);
  const [operateType, setOperateType] = useState<DataSourceOperateType>('CREATE' as DataSourceOperateType);
  const [currentRecord, setCurrentRecord] = useState<DataSourceRecord>();
  const [selectedDbType, setSelectedDbType] = useState('');
  const [showFormStep, setShowFormStep] = useState(false);
  const [hideBackButton, setHideBackButton] = useState(false);

  const successCallbackRef = useRef<(() => void) | undefined>();

  const isCreateMode = operateType === ('CREATE' as DataSourceOperateType);
  const isEditMode = operateType === ('EDIT' as DataSourceOperateType);

  const resetModalState = () => {
    setCurrentRecord(undefined);
    setSelectedDbType('');
    setShowFormStep(false);
    setHideBackButton(false);
    basicForm.resetFields();
    configForm.resetFields();
  };

  const handleClose = () => {
    setOpen(false);
    resetModalState();
  };

  const initializeEditForm = (record: DataSourceRecord) => {
    basicForm.setFieldsValue({
      name: record.name || '',
      unitId: record.unitId == null ? undefined : String(record.unitId),
      // Historical rows may have no business-system binding. Leave the field
      // empty so the required rule makes the editor choose a canonical owner.
      businessSystemId: record.businessSystemId == null ? undefined : String(record.businessSystemId),
      environment: record.environment || '',
      remark: record.remark || '',
    });

    // 注意：不再在这里设置 configForm，而是将数据传递给 DynamicDataSourceForm
    // 由 DynamicDataSourceForm 在加载完表单配置后再设置值
  };

  useImperativeHandle(ref, () => ({
    open: ({
      operateType: nextOperateType,
      currentRecord: nextRecord,
      onSuccess,
      dbType,
      hideBack,
    }: DataSourceModalOpenPayload) => {
      /**
       * 每次打开前，先清理上一次弹窗状态。
       * 这里非常关键，避免 MySQL / PostgreSQL 动态表单互相污染。
       */
      resetModalState();

      setOpen(true);
      setOperateType(nextOperateType);
      setCurrentRecord(nextRecord);
      successCallbackRef.current = onSuccess;

      /**
       * 编辑模式：保持原来的逻辑。
       * 编辑时直接进入表单页，不显示“上一步”。
       */
      if (nextOperateType === ('EDIT' as DataSourceOperateType) && nextRecord) {
        setSelectedDbType(nextRecord.dbType || '');
        setShowFormStep(true);
        setHideBackButton(true);
        initializeEditForm(nextRecord);
        return;
      }

      /**
       * 创建模式 + 外部传入 dbType：
       * 直接进入动态表单页，不走类型选择页。
       */
      if (nextOperateType === ('CREATE' as DataSourceOperateType) && dbType) {
        setSelectedDbType(dbType);
        setShowFormStep(true);
        setHideBackButton(Boolean(hideBack));
        return;
      }

      /**
       * 普通创建模式：
       * 先进入数据源类型选择页。
       */
      setSelectedDbType('');
      setShowFormStep(false);
      setHideBackButton(false);
    },
    close: handleClose,
  }));

  const handleSelectDbType = (dbType: string) => {
    basicForm.resetFields();
    configForm.resetFields();

    setSelectedDbType(dbType);
    setShowFormStep(false);
    setHideBackButton(false);
  };

  const handleBackToTypeSelection = () => {
    setShowFormStep(false);
    setSelectedDbType('');
    setHideBackButton(false);
    basicForm.resetFields();
    configForm.resetFields();
  };

  const handleTestConnection = async () => {
    try {
      const connectionValues = await configForm.validateFields();

      const response = await testDataSourceConnectionWithParams({
        connJson: JSON.stringify({
          ...connectionValues,
          type: selectedDbType,
        }),
      });

      if (response.code === 0) {
        if (response.data === true) {
          message.success(
            intl.formatMessage({
              id: 'pages.datasource.modal.message.success',
              defaultMessage: 'Success',
            }),
          );
          return;
        }

        return;
      }
    } catch (error: any) {
      if (error?.errorFields) return;
    }
  };

  const handleSubmit = async () => {
    try {
      const basicValues = await basicForm.validateFields();
      const connectionValues = await configForm.validateFields();

      const payload = buildSubmitPayload(selectedDbType, basicValues, connectionValues);

      if (isCreateMode) {
        const response = await createDataSource(payload);

        if (response.code !== 0) {
          // message.error(response.message || response.msg || "创建数据源失败");
          return;
        }
      }

      if (isEditMode) {
        if (!currentRecord?.id) {
          return;
        }

        const response = await updateDataSource(currentRecord.id, payload);

        if (response.code !== 0) {
          return;
        }
      }

      message.success(
        intl.formatMessage({
          id: 'pages.datasource.modal.message.success',
          defaultMessage: 'Success',
        }),
      );

      handleClose();
      successCallbackRef.current?.();
    } catch (error: any) {
      if (error?.errorFields) return;
    }
  };

  const modalTitle = isEditMode ? '编辑数据源' : '新增数据源';

  return (
    <Modal
      className={`datasource-editor-modal ${showFormStep ? 'datasource-editor-modal--form' : 'datasource-editor-modal--selector'}`}
      width="min(1196px, calc(100vw - 40px))"
      open={open}
      style={{ top: 111 }}
      maskClosable={false}
      onCancel={handleClose}
      destroyOnClose
      styles={{
        header: {
          padding: '20px 24px 16px',
          borderBottom: '1px solid var(--st-color-divider)',
          background: 'var(--st-color-bg-panel)',
          marginBottom: 0,
        },
        body: {
          padding: '20px 24px 16px',
          background: 'var(--st-color-bg-panel)',
          maxHeight: '72vh',
          overflowY: 'auto',
        },
        footer: {
          padding: '14px 24px 18px',
          borderTop: '1px solid var(--st-color-divider)',
          background: 'var(--st-color-bg-panel)',
          marginTop: 0,
        },
        content: {
          borderRadius: 10,
          overflow: 'hidden',
        },
      }}
      title={<div className="datasource-modal-heading">{modalTitle}</div>}
      footer={
        <div className="datasource-modal-footer">
          {showFormStep && isCreateMode && !hideBackButton ? (
            <Button onClick={handleBackToTypeSelection}>上一步</Button>
          ) : null}
          {showFormStep ? <Button onClick={handleTestConnection}>连接测试</Button> : null}
          {showFormStep ? (
            <Button type="primary" onClick={handleSubmit}>
              完成
            </Button>
          ) : (
            <Button
              type="primary"
              onClick={() => {
                if (selectedDbType) {
                  setShowFormStep(true);
                }
              }}
            >
              下一步
            </Button>
          )}
          <Button onClick={handleClose}>取消</Button>
        </div>
      }
    >
      <div className="datasource-modal-steps" aria-label="数据源创建步骤">
        <span className={!showFormStep ? 'is-active' : ''}>
          <b>1</b>
          选择数据源类型
        </span>
        <i />
        <span className={showFormStep ? 'is-active' : ''}>
          <b>2</b>
          信息配置
        </span>
      </div>
      {showFormStep ? (
        <div className="datasource-form-step">
          <DynamicDataSourceForm
            key={`${operateType}-${selectedDbType}-${currentRecord?.id || 'create'}`}
            dbType={selectedDbType}
            form={basicForm}
            configForm={configForm}
            operateType={operateType}
            onManageMasterData={onManageMasterData}
            initialConfig={isEditMode ? parseOriginalJson(currentRecord?.originalJson) : undefined}
          />
        </div>
      ) : (
        <div className="datasource-type-selector-wrap">
          <DataSourceTypeSelector dataSourceGroups={dataSourceGroupList} onSelect={handleSelectDbType} />
        </div>
      )}
    </Modal>
  );
});

export default AddOrEditDataSourceModal;
