import HttpUtils from '@/utils/HttpUtils';
import { InfoCircleOutlined, LoadingOutlined } from '@ant-design/icons';
import { useIntl } from '@umijs/max';
import { Alert, Button, Form, Input, InputNumber, message, Select, Switch, Tooltip } from 'antd';
import TextArea from 'antd/es/input/TextArea';
import React, { useCallback, useEffect, useRef, useState } from 'react';
import DatabaseIcons from '../../icon/DatabaseIcons';
import { DataSourceOperateType, DynamicDataSourceFormProps } from '../../types';
import DataSourceUnitSelect from '../DataSourceUnitSelect';
import CustomKVList from './components/CustomKVList';
import DriverLocationField from './components/DriverLocationField';
import { getConfigInitialValues, isFieldVisible, transformRules } from './utils/formUtils';

/** Hidden from UI; always sent on create / preserved on edit. */
const DEFAULT_ENVIRONMENT = 'DEVELOP';

const sectionTitleClass = 'm-0 text-[15px] font-semibold text-slate-800';
const sectionDescClass = 'mt-1 mb-0 text-[13px] leading-[22px] text-slate-500';

const isEmptyValue = (value: any) => {
  return value === undefined || value === null || value === '';
};

const isCreateOperateType = (operateType?: DataSourceOperateType) => {
  return operateType === ('CREATE' as DataSourceOperateType) || operateType === (DataSourceOperateType as any)?.Create;
};

const getFormFieldValue = (valueOrEvent: any) => {
  if (valueOrEvent?.target && typeof valueOrEvent.target === 'object' && 'value' in valueOrEvent.target) {
    return valueOrEvent.target.value;
  }
  return valueOrEvent;
};

const DynamicDataSourceForm: React.FC<DynamicDataSourceFormProps> = ({
  dbType,
  form,
  configForm,
  operateType,
  onManageMasterData,
  initialConfig,
  hideBaseFields = false,
  allowExistingPassword = false,
}) => {
  const intl = useIntl();

  const [formConfig, setFormConfig] = useState<any[]>([]);
  const [loading, setLoading] = useState<boolean>(false);
  const authenticationType = Form.useWatch('authenticationType', configForm);

  const [needInstall, setNeedInstall] = useState(false);
  const [installing, setInstalling] = useState(false);
  const [loadErrMsg, setLoadErrMsg] = useState<string>('');

  /**
   * 用请求序号解决“慢一拍”的问题。
   * 比如先请求 MySQL，再请求 PostgreSQL。
   * 如果 MySQL 后返回，不能再覆盖 PostgreSQL 的表单。
   */
  const requestSeqRef = useRef(0);

  const fillCreateDefaultBaseInfo = useCallback(() => {
    if (hideBaseFields || !isCreateOperateType(operateType)) {
      return;
    }

    const current = form.getFieldsValue(true);
    const patch: Record<string, any> = {};

    if (isEmptyValue(current?.environment)) {
      patch.environment = DEFAULT_ENVIRONMENT;
    }

    if (Object.keys(patch).length) {
      form.setFieldsValue(patch);
    }
  }, [hideBaseFields, operateType, form]);

  const loadFormConfig = useCallback(
    async (currentDbType: string): Promise<void> => {
      const requestSeq = requestSeqRef.current + 1;
      requestSeqRef.current = requestSeq;

      try {
        setLoading(true);
        setNeedInstall(false);
        setLoadErrMsg('');

        /**
         * 切换 dbType 时，先把旧字段清掉。
         * 否则 PostgreSQL 配置请求还没回来时，页面可能短暂显示 MySQL 字段。
         */
        setFormConfig([]);
        configForm.resetFields();

        const response = await HttpUtils.get<any>(`/api/v1/data-source/plugin/config?pluginType=${currentDbType}`);

        /**
         * 只允许最新请求更新页面。
         * 旧请求回来直接丢弃。
         */
        if (requestSeq !== requestSeqRef.current) {
          return;
        }

        if (response?.code === 0) {
          const data = response?.data || {};

          // 检查是否需要安装插件
          if (data.installRequired) {
            setNeedInstall(true);
            setLoadErrMsg(data.installHint || '请先安装数据源插件');
            setFormConfig([]);
            configForm.resetFields();
            return;
          }

          const fields = data.formFields || [];

          setNeedInstall(false);
          setLoadErrMsg('');
          setFormConfig(fields);

          /**
           * 注意：
           * 这里不要用"只 patch 空值"的方式。
           * 因为 MySQL 和 PostgreSQL 有很多同名字段，例如 host、port、user、password。
           * 切换类型时应该以当前 dbType 的默认值为准。
           */
          const init = getConfigInitialValues(fields);
          configForm.resetFields();

          /**
           * 编辑模式：使用传入的 initialConfig 覆盖默认值
           * 创建模式：使用表单的默认值
           */
          if (initialConfig && Object.keys(initialConfig).length > 0) {
            configForm.setFieldsValue({
              ...init,
              ...initialConfig,
            });
          } else {
            configForm.setFieldsValue(init);
          }
          return;
        }

        setNeedInstall(true);
        setLoadErrMsg(response?.msg || response?.message || 'Plugin config not available');
        setFormConfig([]);
        configForm.resetFields();
      } catch (error: any) {
        if (requestSeq !== requestSeqRef.current) {
          return;
        }

        setNeedInstall(true);
        setLoadErrMsg(
          error?.message ||
            intl.formatMessage({
              id: 'pages.datasource.form.loadConfigFail',
              defaultMessage: 'Failed to load form config',
            }),
        );
        setFormConfig([]);
        configForm.resetFields();
      } finally {
        if (requestSeq === requestSeqRef.current) {
          setLoading(false);
        }
      }
    },
    [configForm, intl],
  );

  useEffect(() => {
    fillCreateDefaultBaseInfo();
  }, [fillCreateDefaultBaseInfo]);

  useEffect(() => {
    if (!authenticationType) return;

    const hiddenKeys = formConfig
      .filter((field) => !isFieldVisible(field, { authenticationType }))
      .map((field) => field.key);

    if (hiddenKeys.length) {
      configForm.resetFields(hiddenKeys);
    }
  }, [authenticationType, configForm, formConfig]);

  useEffect(() => {
    if (!dbType) {
      requestSeqRef.current += 1;
      setFormConfig([]);
      setNeedInstall(false);
      setLoadErrMsg('');
      setLoading(false);
      configForm.resetFields();
      return;
    }

    loadFormConfig(dbType);

    return () => {
      /**
       * 组件卸载或 dbType 变化时，让旧请求失效。
       */
      requestSeqRef.current += 1;
    };
  }, [dbType, configForm, loadFormConfig]);

  const installPlugin = async () => {
    if (!dbType) {
      message.warning('请先选择数据源类型');
      return;
    }

    try {
      setInstalling(true);

      const resp = await HttpUtils.post<any>(`/api/v1/data-source/plugin/config/install?pluginType=${dbType}`, {});

      if (resp?.code === 0) {
        message.success('插件安装成功');
        await loadFormConfig(dbType);
        return;
      }
    } catch (e: any) {
    } finally {
      setInstalling(false);
    }
  };

  const renderFormItem = (field: any): React.ReactNode => {
    const syncFieldValue = (valueOrEvent: any) => {
      configForm.setFieldValue(field.key, getFormFieldValue(valueOrEvent));
    };

    const commonProps = {
      placeholder: field.placeholder,
      onChange: (valueOrEvent: any) => {
        syncFieldValue(valueOrEvent);
        setTimeout(() => {
          configForm.validateFields([field.key]).catch(() => {});
        }, 0);
      },
      onBlur: (event: any) => {
        // Browser password managers may update the visible input without
        // dispatching an input/change event.  Sync the DOM value when the
        // field loses focus so Form.getFieldsValue() sees what the user sees.
        if (['INPUT', 'PASSWORD', 'TEXTAREA'].includes(field.type)) {
          syncFieldValue(event);
        }
      },
    };

    if (field.key === 'driverLocation') {
      return <DriverLocationField field={field} dbType={dbType} configForm={configForm} />;
    }

    switch (field.type) {
      case 'INPUT':
        return <Input {...commonProps} />;

      case 'PASSWORD':
        return <Input.Password {...commonProps} autoComplete="new-password" />;

      case 'SELECT':
        return (
          <Select {...commonProps}>
            {field.options?.map((option: any) => (
              <Select.Option key={option.value} value={option.value}>
                {option.label}
              </Select.Option>
            ))}
          </Select>
        );

      case 'NUMBER':
        return <InputNumber {...commonProps} className="!w-full" />;

      case 'SWITCH':
        return <Switch {...commonProps} />;

      case 'TEXTAREA':
        return <Input.TextArea rows={4} {...commonProps} />;

      default:
        return <Input {...commonProps} />;
    }
  };

const renderFieldLabel = (field: any): React.ReactNode => {
    if (!field.description) {
      return field.label;
    }

    return (
      <span className="inline-flex items-center">
        {field.label}
        <Tooltip title={field.description}>
          <InfoCircleOutlined className="ml-1 text-slate-400" />
        </Tooltip>
      </span>
    );
  };

  const fieldRules = (field: any) => {
    const rules = transformRules(field?.rules);
    return rules.map((rule) => {
      if (rule.required && typeof rule.message === 'string' && /cannot be empty/i.test(rule.message)) {
        return { ...rule, message: `请输入${field.label || '该字段'}` };
      }
      return rule;
    });
  };

  if (loading) {
    return (
      <div className="datasource-form-panel flex min-h-[220px] items-center justify-center p-5">
        <div className="flex items-center gap-2.5 text-sm text-slate-500">
          <LoadingOutlined />
          <span>正在加载数据源配置...</span>
        </div>
      </div>
    );
  }

  return (
    <div className="datasource-form-panel p-5">
      {!hideBaseFields ? (
        <>
          <div className="mb-5">
            <h3 className={sectionTitleClass}>数据源信息</h3>
            <p className={sectionDescClass}>先填写基础信息，再补充当前数据源类型对应的连接参数。</p>
          </div>

          <Form form={form} layout="vertical">
            <Form.Item
              label={intl.formatMessage({
                id: 'pages.datasource.form.dsName',
                defaultMessage: 'DS Name',
              })}
              name="name"
              rules={[
                {
                  required: true,
                  message: intl.formatMessage({
                    id: 'pages.datasource.form.dsNameRequired',
                    defaultMessage: 'DS Name is required',
                  }),
                },
              ]}
            >
              <Input
                placeholder={intl.formatMessage({
                  id: 'pages.datasource.form.inputPlaceholder',
                  defaultMessage: 'Input...',
                })}
                maxLength={100}
              />
            </Form.Item>

            {/* Environment is not shown; create defaults to DEVELOP, edit keeps existing. */}
            <Form.Item name="environment" hidden>
              <Input type="hidden" />
            </Form.Item>

            <DataSourceUnitSelect form={form} onManageMasterData={onManageMasterData} />

            <Form.Item
              label={intl.formatMessage({
                id: 'pages.datasource.form.description',
                defaultMessage: 'Description',
              })}
              name="remark"
            >
              <TextArea
                placeholder={intl.formatMessage({
                  id: 'pages.datasource.form.inputPlaceholder',
                  defaultMessage: 'Input...',
                })}
                rows={4}
              />
            </Form.Item>

            <Form.Item name="connectionParams" hidden>
              <Input type="hidden" />
            </Form.Item>
          </Form>
        </>
      ) : null}

      {needInstall && (
        <div className="datasource-form-notice mb-5 px-4 py-3.5">
          <div className="mb-2.5 text-[13px] leading-[22px] text-slate-600">
            当前插件配置暂不可用，可能尚未安装。请先安装对应插件后，再继续填写连接参数。
          </div>

          {loadErrMsg ? <div className="mb-3 text-xs leading-5 text-slate-400">{loadErrMsg}</div> : null}

          <Button
            type="default"
            loading={installing}
            onClick={installPlugin}
            className="!h-[38px] !rounded-[10px] !px-4"
          >
            <span className="inline-flex items-center gap-2">
              <span>
                {intl.formatMessage({
                  id: 'pages.datasource.form.installPlugin',
                  defaultMessage: 'Install Plugin',
                })}
              </span>

              <span className="inline-flex items-center gap-1.5">
                <span>({dbType})</span>
                <DatabaseIcons dbType={dbType} height="18" width="18" />
              </span>
            </span>
          </Button>
        </div>
      )}

      <div className={hideBaseFields ? '' : 'mt-2 border-t border-[var(--st-color-divider)] pt-[18px]'}>
        <div className="mb-4">
          <h3 className={sectionTitleClass}>连接参数</h3>
          <p className={sectionDescClass}>根据当前数据源类型自动渲染配置项，建议优先填写必填字段。</p>
        </div>

        <Alert
          className="mb-4"
          type="info"
          showIcon
          message="连接测试通过仅表示网络与认证成功；账号若不具备读取库表元数据等相应权限，后续元数据扫描与探查可能失败。"
        />

        <Form
          form={configForm}
          component={false}
          labelCol={{ flex: '110px' }}
          wrapperCol={{ flex: '1' }}
          labelAlign="left"
        >
          {formConfig.map((field) => {
            if (!isFieldVisible(field, { authenticationType })) {
              return null;
            }

            if (field.type === 'CUSTOM_SELECT') {
              return <CustomKVList key={field.key} intl={intl} field={field} />;
            }

            return (
              <Form.Item
                key={field.key}
                label={renderFieldLabel(field)}
                name={field.key}
                preserve={false}
                rules={
                  field.key === 'password' && allowExistingPassword
                    ? fieldRules(field).filter((rule) => !rule.required)
                    : fieldRules(field)
                }
                validateTrigger={['onChange', 'onBlur']}
                className="!mb-[18px]"
              >
                {renderFormItem(field)}
              </Form.Item>
            );
          })}
        </Form>
      </div>
    </div>
  );
};

export default DynamicDataSourceForm;
