import {
  getDataSourceConnectionErrorMessage,
  getDataSourceConnectionTestAdvice,
} from '../connectionError';

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

describe('getDataSourceConnectionTestAdvice', () => {
  it('suggests auth checks for credential failures', () => {
    const advice = getDataSourceConnectionTestAdvice('Access denied for user');
    expect(advice.some((item) => item.includes('用户名与密码'))).toBe(true);
    expect(advice.some((item) => item.includes('再次点击'))).toBe(true);
  });

  it('falls back to host/port checklist when reason is generic', () => {
    const advice = getDataSourceConnectionTestAdvice('unexpected failure');
    expect(advice[0]).toContain('主机、端口');
  });
});
