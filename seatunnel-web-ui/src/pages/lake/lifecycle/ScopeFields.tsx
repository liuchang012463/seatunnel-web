import { fetchBusinessSystemOptions, fetchDataSourceUnitOptions, unwrapMasterDataList } from '@/pages/data-source/service';
import type { BusinessSystemOption, DataSourceUnitOption } from '@/pages/data-source/types';
import { fetchPhysicalSources, normalizeLakePage } from '@/services/lake';
import type { LakePhysicalDataSource } from '@/services/lake';
import { Alert, Button, Collapse, Form, Select } from 'antd';
import type { FormInstance } from 'antd';
import { useEffect, useState } from 'react';

/** Use the same source and ownership master data as datasource management. */
export default function ScopeFields({ form, open }: { form: FormInstance; open: boolean }) {
  const [sources, setSources] = useState<LakePhysicalDataSource[]>([]);
  const [units, setUnits] = useState<DataSourceUnitOption[]>([]);
  const [systems, setSystems] = useState<BusinessSystemOption[]>([]);
  const [error, setError] = useState('');
  const type = Form.useWatch('dataSourceType', form);
  const unitId = Form.useWatch('unitId', form);
  const systemId = Form.useWatch('businessSystemId', form);
  const load = async () => {
    setError('');
    try {
      const [sourceResult, unitResult] = await Promise.all([fetchPhysicalSources({ pageNo: 1, pageSize: 1000 }), fetchDataSourceUnitOptions()]);
      if (sourceResult.code !== 0) throw new Error('数据源读取失败');
      const unitList = unwrapMasterDataList(unitResult);
      const systemLists = await Promise.all(unitList.map(async (unit) => unwrapMasterDataList(await fetchBusinessSystemOptions(unit.id))));
      setSources(normalizeLakePage(sourceResult.data).data);
      setUnits(unitList);
      setSystems(systemLists.flat());
    } catch (e: any) { setError(e.message || '适用范围选项读取失败'); }
  };
  useEffect(() => { if (open) void load(); }, [open]);
  const same = (left: unknown, right: unknown) => String(left) === String(right);
  const sourceOptions = sources.filter((source) => (!type || type === source.dbType)
    && (!unitId || same(unitId, source.unitId)) && (!systemId || same(systemId, source.businessSystemId)));
  return <Collapse style={{ marginBottom: 24 }} items={[{ key: 'scope', forceRender: true, label: '适用范围（默认全部数据源）', children: <>
    {error && <Alert type="error" message={error} action={<Button onClick={load}>重试</Button>} />}
    <Form.Item name="dataSourceType" label="数据类型" extra="使用系统已有的数据源类型。留空表示不限，多个条件同时满足。">
      <Select allowClear showSearch placeholder="全部类型" options={[...new Set(sources.map((source) => source.dbType).filter(Boolean))].map((value) => ({ value, label: value }))}
        onChange={() => form.setFieldValue('sourceDataSourceId', undefined)} /></Form.Item>
    <Form.Item name="unitId" label="单位"><Select allowClear showSearch optionFilterProp="label" placeholder="全部单位"
      options={units.map((unit) => ({ value: Number(unit.id), label: unit.unitName }))}
      onChange={() => form.setFieldsValue({ businessSystemId: undefined, sourceDataSourceId: undefined })} /></Form.Item>
    <Form.Item name="businessSystemId" label="业务系统"><Select allowClear showSearch optionFilterProp="label" placeholder="全部业务系统"
      options={systems.filter((system) => !unitId || same(system.unitId, unitId)).map((system) => ({ value: Number(system.id), label: system.systemName }))}
      onChange={() => form.setFieldValue('sourceDataSourceId', undefined)} /></Form.Item>
    <Form.Item name="sourceDataSourceId" label="数据源"><Select allowClear showSearch optionFilterProp="label" placeholder="全部匹配的数据源"
      options={sourceOptions.map((source) => ({ value: source.sourceDataSourceId, label: source.sourceDataSourceName }))} /></Form.Item>
  </> }]} />;
}
