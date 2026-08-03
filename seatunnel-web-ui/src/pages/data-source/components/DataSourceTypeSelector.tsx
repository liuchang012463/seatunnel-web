import React, { useMemo, useState } from 'react';
import { Button, Empty, Input } from 'antd';
import { SearchOutlined } from '@ant-design/icons';

import DatabaseIcons from '../icon/DatabaseIcons';
import { COMMON_DB_OPTIONS } from '../constants';
import type { DataSourceGroup } from '../types';

interface DataSourceTypeSelectorProps {
  dataSourceGroups: DataSourceGroup[];
  onSelect: (dbType: string) => void;
}

const DataSourceTypeSelector: React.FC<DataSourceTypeSelectorProps> = ({
  dataSourceGroups,
  onSelect,
}) => {
  const [query, setQuery] = useState('');
  const [selectedGroupName, setSelectedGroupName] = useState<string | null>(null);

  const keyword = query.trim().toLowerCase();

  const totalDatasourceCount = useMemo(() => {
    return dataSourceGroups.reduce(
      (total, group) => total + group.datasourceList.length,
      0,
    );
  }, [dataSourceGroups]);

  const flatDatasourceList = useMemo(() => {
    return dataSourceGroups.flatMap((group) =>
      group.datasourceList.map((item) => ({
        ...item,
        groupName: group.groupName,
        searchText: `${item.label} ${item.dbType} ${item.connectorType || ''} ${item.type || ''} ${group.groupName}`.toLowerCase(),
      })),
    );
  }, [dataSourceGroups]);

  const filteredDatasourceList = useMemo(() => {
    return flatDatasourceList.filter((item) => {
      const matchGroup =
        selectedGroupName === null || item.groupName === selectedGroupName;
      const matchKeyword = !keyword || item.searchText.includes(keyword);

      return matchGroup && matchKeyword;
    });
  }, [flatDatasourceList, keyword, selectedGroupName]);

  const suggestedDatasourceList = useMemo(() => {
    return COMMON_DB_OPTIONS.map((common) => {
      const matched = flatDatasourceList.find(
        (item) =>
          item.dbType === common.value ||
          item.dbType === common.label ||
          item.dbType?.toLowerCase() === common.value?.toLowerCase() ||
          item.dbType?.toLowerCase() === common.label?.toLowerCase(),
      );

      return {
        ...common,
        dbType: matched?.dbType || common.value,
        connectorType: matched?.connectorType,
        groupName: matched?.groupName,
      };
    }).filter((item) => item.dbType).slice(0, 3);;
  }, [flatDatasourceList]);

  const showSuggested = !keyword && suggestedDatasourceList.length > 0;

  return (
    <div className="datasource-type-selector flex flex-col gap-5">
      <div>
        <Input
          allowClear
          prefix={<SearchOutlined className="datasource-type-search-icon" />}
          placeholder="搜索数据源类型，例如 MySQL、PostgreSQL、Oracle..."
          value={query}
          className="datasource-type-search"
          onChange={(event) => setQuery(event.target.value)}
        />

        <div className="mt-4 flex flex-wrap items-center gap-2">
          <span className="datasource-type-filter-label">
            类型：
          </span>

          <Button
            type={selectedGroupName === null ? 'primary' : 'default'}
            size="small"
            className="datasource-type-filter-button"
            onClick={() => setSelectedGroupName(null)}
          >
            全部
            <span
              className={`datasource-type-filter-count${selectedGroupName === null ? ' is-active' : ''}`}
            >
              {totalDatasourceCount}
            </span>
          </Button>

          {dataSourceGroups.map((group) => {
            const active = selectedGroupName === group.groupName;

            return (
              <Button
                key={group.groupName}
                type={active ? 'primary' : 'default'}
                size="small"
                className="datasource-type-filter-button"
                onClick={() =>
                  setSelectedGroupName((prev) =>
                    prev === group.groupName ? null : group.groupName,
                  )
                }
              >
                {group.groupName}
                <span
                  className={`datasource-type-filter-count${active ? ' is-active' : ''}`}
                >
                  {group.datasourceList.length}
                </span>
              </Button>
            );
          })}
        </div>
      </div>

      {showSuggested && (
        <section className="datasource-type-suggested p-3">
          <div className="mb-3 flex items-center justify-between">
            <div className="datasource-type-section-title">
              常用数据源
            </div>
            <div className="datasource-type-section-meta">
              常用连接器，点击即可创建
            </div>
          </div>

          <div className="grid grid-cols-1 gap-3 sm:grid-cols-3">
            {suggestedDatasourceList.map((item) => (
              <button
                key={item.dbType}
                type="button"
                className="datasource-type-option group flex min-h-[66px] items-center gap-3 px-4 py-3 text-left"
                onClick={() => onSelect(item.dbType)}
              >
                <div
                  className="datasource-type-option-icon"
                >
                  <DatabaseIcons dbType={item.dbType} width="18px" height="18px" />
                </div>

                <div className="min-w-0 flex-1">
                  <div className="datasource-type-option-title truncate">
                    {item.label}
                  </div>
                  <div className="datasource-type-option-meta mt-1 truncate">
                    {item.connectorType || item.groupName || '快速选择'}
                  </div>
                </div>
              </button>
            ))}
          </div>
        </section>
      )}

      <section>
        <div className="mb-3 flex items-center justify-between">
          <div className="datasource-type-section-title">
            数据源类型
          </div>
          <div className="datasource-type-section-meta">
            {filteredDatasourceList.length} 个连接器
          </div>
        </div>

        {filteredDatasourceList.length === 0 ? (
          <div className="datasource-type-empty px-6 py-10 text-center">
            <Empty
              image={Empty.PRESENTED_IMAGE_SIMPLE}
              description="未找到匹配的数据源类型"
            />
          </div>
        ) : (
          <div className="grid grid-cols-1 gap-3 sm:grid-cols-2 xl:grid-cols-3">
            {filteredDatasourceList.map((item) => (
              <button
                key={`${item.groupName}-${item.dbType}-${item.connectorType || item.type || ''}`}
                type="button"
                className="datasource-type-option group relative flex min-h-[76px] items-center justify-between gap-3 px-4 py-3 text-left"
                onClick={() => onSelect(item.dbType)}
              >
                <div className="flex min-w-0 flex-1 items-center gap-3">
                  <div
                    className="datasource-type-option-icon"
                  >
                    <DatabaseIcons dbType={item.dbType} width="17px" height="17px" />
                  </div>

                  <div className="min-w-0 flex-1">
                    <div
                      className="datasource-type-option-title truncate"
                      title={item.label}
                    >
                      {item.label?.toUpperCase()}
                    </div>

                    <div className="datasource-type-option-meta mt-1 truncate">
                      {item.connectorType || item.type || '数据源连接器'}
                    </div>
                  </div>
                </div>

                <div
                  className="datasource-type-option-group max-w-[96px] shrink-0 truncate"
                  title={item.groupName}
                >
                  {item.groupName}
                </div>
              </button>
            ))}
          </div>
        )}
      </section>
    </div>
  );
};

export default DataSourceTypeSelector;
