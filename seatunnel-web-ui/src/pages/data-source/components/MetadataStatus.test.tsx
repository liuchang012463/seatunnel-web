import { metadataSyncTag } from './MetadataStatus';

describe('data-source metadata status labels', () => {
  it('uses product-facing labels rather than OpenMetadata or Airflow names', () => {
    expect(metadataSyncTag('READY').text).toBe('已就绪');
    expect(metadataSyncTag('DELETING').text).toBe('删除中');
    expect(metadataSyncTag('NOT_INITIALIZED').text).toBe('未初始化');
  });
});
