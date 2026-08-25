import { Form, Select, Spin } from 'antd';
import React, { useEffect, useMemo, useRef, useState } from 'react';
import { fetchBusinessSystemOptions, fetchDataSourceUnitOptions, unwrapMasterDataList } from '../service';
import type { BusinessSystemOption, DataSourceEntityId, DataSourceFormValues, DataSourceUnitOption } from '../types';

interface DataSourceUnitSelectProps {
  form: ReturnType<typeof Form.useForm<DataSourceFormValues>>[0];
}

const normalizeId = (value?: DataSourceEntityId | null) =>
  value === undefined || value === null ? undefined : String(value);

const uniqueById = <T extends { id: DataSourceEntityId }>(items: T[]) => {
  const seen = new Set<string>();
  return items.filter((item) => {
    const id = normalizeId(item.id);
    if (!id || seen.has(id)) return false;
    seen.add(id);
    return true;
  });
};

/**
 * Renders the owning-unit and business-system fields used by the data-source
 * form. Business systems are loaded only after a unit has been selected.
 */
const DataSourceUnitSelect: React.FC<DataSourceUnitSelectProps> = ({ form }) => {
  const [units, setUnits] = useState<DataSourceUnitOption[]>([]);
  const [businessSystems, setBusinessSystems] = useState<BusinessSystemOption[]>([]);
  const [unitLoading, setUnitLoading] = useState(false);
  const [businessSystemLoading, setBusinessSystemLoading] = useState(false);
  const unitId = Form.useWatch('unitId', form) as DataSourceEntityId | undefined;
  const previousUnitIdRef = useRef<string | undefined>();

  useEffect(() => {
    let mounted = true;

    const loadUnits = async () => {
      try {
        setUnitLoading(true);
        const response = await fetchDataSourceUnitOptions();
        if (mounted && response.code === 0) {
          setUnits(uniqueById(unwrapMasterDataList(response)));
        }
      } catch (_) {
        if (mounted) setUnits([]);
      } finally {
        if (mounted) setUnitLoading(false);
      }
    };

    loadUnits();
    return () => {
      mounted = false;
    };
  }, []);

  useEffect(() => {
    const normalizedUnitId = normalizeId(unitId);
    const previousUnitId = previousUnitIdRef.current;

    if (previousUnitId !== undefined && previousUnitId !== normalizedUnitId) {
      form.setFieldValue('businessSystemId', undefined);
    }
    previousUnitIdRef.current = normalizedUnitId;

    if (!normalizedUnitId) {
      setBusinessSystems([]);
      setBusinessSystemLoading(false);
      return;
    }

    let mounted = true;
    const loadBusinessSystems = async () => {
      try {
        setBusinessSystemLoading(true);
        const response = await fetchBusinessSystemOptions(normalizedUnitId);
        if (mounted && response.code === 0) {
          setBusinessSystems(uniqueById(unwrapMasterDataList(response)));
        }
      } catch (_) {
        if (mounted) setBusinessSystems([]);
      } finally {
        if (mounted) setBusinessSystemLoading(false);
      }
    };

    loadBusinessSystems();
    return () => {
      mounted = false;
    };
  }, [form, unitId]);

  const unitOptions = useMemo(
    () =>
      units.map((unit) => ({
        value: String(unit.id),
        label: unit.unitCode ? `${unit.unitName}（${unit.unitCode}）` : unit.unitName,
      })),
    [units],
  );

  const businessSystemOptions = useMemo(
    () =>
      businessSystems.map((system) => ({
        value: String(system.id),
        label: system.systemCode ? `${system.systemName}（${system.systemCode}）` : system.systemName,
      })),
    [businessSystems],
  );

  return (
    <div className="grid grid-cols-1 gap-4 md:grid-cols-2">
      <Form.Item label="数据源单位" name="unitId" rules={[{ required: true, message: '请选择数据源单位' }]}>
        <Select
          allowClear
          showSearch
          optionFilterProp="label"
          options={unitOptions}
          loading={unitLoading}
          placeholder="请选择数据源单位"
          notFoundContent={unitLoading ? <Spin size="small" /> : '暂无可用单位'}
        />
      </Form.Item>

      <Form.Item label="业务系统" name="businessSystemId" rules={[{ required: true, message: '请选择业务系统' }]}>
        <Select
          allowClear
          showSearch
          optionFilterProp="label"
          options={businessSystemOptions}
          loading={businessSystemLoading}
          disabled={!normalizeId(unitId)}
          placeholder={normalizeId(unitId) ? '请选择业务系统' : '请先选择数据源单位'}
          notFoundContent={businessSystemLoading ? <Spin size="small" /> : '暂无可用业务系统'}
        />
      </Form.Item>
    </div>
  );
};

export default DataSourceUnitSelect;
