import { getDataSourceConnectionErrorMessage } from '../connectionError';

describe('getDataSourceConnectionErrorMessage', () => {
  it('keeps the backend reason and masks connection credentials', () => {
    expect(getDataSourceConnectionErrorMessage({
      response: { data: { message: '连接验证阶段失败：password=secret jdbc:postgresql://user:pwd@host/db' } },
    })).toBe('连接验证阶段失败：password=****** jdbc:postgresql://******@host/db');
  });

  it('uses a clear fallback when the request has no actionable reason', () => {
    expect(getDataSourceConnectionErrorMessage(new Error('Network Error')))
      .toBe('连接测试失败，请检查网络、主机、端口、数据库和账号配置。');
  });
});
