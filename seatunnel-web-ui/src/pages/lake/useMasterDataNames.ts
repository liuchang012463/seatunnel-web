import { useEffect, useMemo, useState } from 'react';
import {
  fetchBusinessSystemOptions,
  fetchDataSourceUnitOptions,
  unwrapMasterDataList,
} from '@/pages/data-source/service';
import type { BusinessSystemOption, DataSourceUnitOption } from '@/pages/data-source/types';

export interface MasterDataNames {
  unitNameByCode: (code?: string) => string | undefined;
  systemNameByCode: (code?: string) => string | undefined;
}

/**
 * 湖域页面把 unitCode/systemCode 翻译成主数据名称（审计 G9）：
 * lake 接口只回编码，这里拉一次主数据做 code→名称 映射，查不到回退编码。
 */
export const useMasterDataNames = (): MasterDataNames => {
  const [units, setUnits] = useState<DataSourceUnitOption[]>([]);
  const [systems, setSystems] = useState<BusinessSystemOption[]>([]);

  useEffect(() => {
    let cancelled = false;
    const load = async () => {
      try {
        const unitResponse = await fetchDataSourceUnitOptions();
        if (cancelled) return;
        const unitList = unwrapMasterDataList(unitResponse);
        setUnits(unitList);
        // 业务系统按单位挂载，逐单位拉取后合并（接口要求 unitId）。
        const systemLists = await Promise.all(
          unitList.map((unit) =>
            fetchBusinessSystemOptions(unit.id)
              .then((response) => unwrapMasterDataList(response))
              .catch(() => []),
          ),
        );
        if (cancelled) return;
        setSystems(systemLists.flat());
      } catch {
        // 名称映射失败时回退显示编码，不打断页面
      }
    };
    void load();
    return () => {
      cancelled = true;
    };
  }, []);

  return useMemo(
    () => ({
      unitNameByCode: (code?: string) =>
        (code && units.find((unit) => unit.unitCode === code)?.unitName) || code,
      systemNameByCode: (code?: string) =>
        (code && systems.find((system) => system.systemCode === code)?.systemName) || code,
    }),
    [units, systems],
  );
};
