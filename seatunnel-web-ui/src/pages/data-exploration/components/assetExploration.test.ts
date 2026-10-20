import {
  assetTypeLabel,
  firstTabKey,
  formatPropertyValue,
  normalizeAssetType,
  sampleStatusCopy,
  schemaStatusCopy,
  tabsForAsset,
} from './assetExploration';

describe('asset exploration helpers', () => {
  it('accepts both API and OpenMetadata spellings', () => {
    expect(normalizeAssetType('searchIndex')).toBe('SEARCH_INDEX');
    expect(normalizeAssetType('SEARCH_INDEX')).toBe('SEARCH_INDEX');
    expect(normalizeAssetType('api-collection')).toBe('API_COLLECTION');
    expect(normalizeAssetType('unknown')).toBeUndefined();
    expect(assetTypeLabel('searchIndex')).toBe('索引');
    expect(assetTypeLabel('FILE')).toBe('文件');
  });

  it('chooses tabs by asset family', () => {
    expect(tabsForAsset('TOPIC').map((tab) => tab.key))
      .toEqual(['schema', 'sample', 'metrics']);
    expect(tabsForAsset('API_ENDPOINT').map((tab) => tab.key))
      .toEqual(['request', 'response']);
    expect(tabsForAsset('DIRECTORY')).toEqual([]);
    expect(firstTabKey('CONTAINER')).toBe('schema');
    expect(firstTabKey('API_COLLECTION')).toBeUndefined();
  });

  it('explains sample and schema states', () => {
    expect(sampleStatusCopy('DISABLED').title).toBe('样本采集未开启');
    expect(sampleStatusCopy('NEEDS_DATA_MODEL').title).toBe('需要结构化容器清单');
    expect(sampleStatusCopy('NOT_APPLICABLE').title).toBe('该文件不提供样本');
    expect(sampleStatusCopy('PERMISSION_DENIED').title).toBe('没有查看样本的权限');
    expect(schemaStatusCopy('NO_SCHEMA').title).toBe('尚未抽取到结构');
    expect(schemaStatusCopy('NOT_SUPPORTED').title).toBe('该类元数据不提供结构');
  });

  it('formats display-ready properties', () => {
    expect(formatPropertyValue({ value: 2048, displayType: 'SIZE' })).toBe('2.0 KB');
    expect(formatPropertyValue({ value: 1200, displayType: 'NUMBER' })).toBe('1,200');
    expect(formatPropertyValue({ value: ['DELETE', 'COMPACT'], displayType: 'LIST' }))
      .toBe('DELETE、COMPACT');
    expect(formatPropertyValue({ value: null, displayType: 'TEXT' })).toBe('—');
  });
});
