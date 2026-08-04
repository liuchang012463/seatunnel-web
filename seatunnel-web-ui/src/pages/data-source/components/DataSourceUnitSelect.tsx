import { PlusOutlined } from "@ant-design/icons";
import { AutoComplete, Input, Spin } from "antd";
import React, { useEffect, useMemo, useState } from "react";
import { fetchDataSourceUnits } from "../service";

interface DataSourceUnitSelectProps {
  value?: string;
  onChange?: (value: string) => void;
}

const DataSourceUnitSelect: React.FC<DataSourceUnitSelectProps> = ({
  value,
  onChange,
}) => {
  const [units, setUnits] = useState<string[]>([]);
  const [keyword, setKeyword] = useState("");
  const [loading, setLoading] = useState(false);

  useEffect(() => {
    let mounted = true;

    const loadUnits = async () => {
      try {
        setLoading(true);
        const response = await fetchDataSourceUnits();
        if (mounted && response.code === 0) {
          setUnits(
            Array.from(
              new Set(
                (response.data || [])
                  .map((unit) => unit?.trim())
                  .filter((unit): unit is string => Boolean(unit)),
              ),
            ),
          );
        }
      } catch (_) {
        // The field still accepts a new unit when the options request fails.
      } finally {
        if (mounted) {
          setLoading(false);
        }
      }
    };

    loadUnits();
    return () => {
      mounted = false;
    };
  }, []);

  const options = useMemo(() => {
    const normalizedKeyword = keyword.trim().toLowerCase();
    const filteredUnits = units.filter(
      (unit) => !normalizedKeyword || unit.toLowerCase().includes(normalizedKeyword),
    );
    const typedUnit = keyword.trim();

    if (typedUnit && !units.some((unit) => unit === typedUnit)) {
      return [
        {
          value: typedUnit,
          label: (
            <span className="inline-flex items-center gap-2">
              <PlusOutlined />
              创建“{typedUnit}”
            </span>
          ),
        },
        ...filteredUnits.map((unit) => ({ value: unit, label: unit })),
      ];
    }

    return filteredUnits.map((unit) => ({ value: unit, label: unit }));
  }, [keyword, units]);

  return (
    <AutoComplete
      value={value || ""}
      options={options}
      allowClear
      filterOption={false}
      onSearch={setKeyword}
      onChange={(nextValue) => onChange?.(nextValue)}
      onSelect={(nextValue) => onChange?.(nextValue)}
      className="w-full"
      notFoundContent={loading ? <Spin size="small" /> : null}
    >
      <Input
        maxLength={128}
        placeholder="选择或创建一个数据源单位"
        suffix={loading ? <Spin size="small" /> : null}
      />
    </AutoComplete>
  );
};

export default DataSourceUnitSelect;
