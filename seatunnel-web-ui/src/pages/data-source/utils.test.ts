import { buildSubmitPayload } from './utils';

describe('data-source submit payload', () => {
  it('submits businessSystemId and omits legacy/free-text ownership fields', () => {
    const payload = buildSubmitPayload(
      'MYSQL',
      {
        name: 'warehouse',
        unitId: '10',
        businessSystemId: '20',
        environment: 'DEVELOP',
        remark: 'owned by portal',
      },
      { host: 'localhost', port: 3306 },
    );

    expect(payload).toMatchObject({
      dbType: 'MYSQL',
      name: 'warehouse',
      businessSystemId: '20',
      environment: 'DEVELOP',
    });
    expect(payload).not.toHaveProperty('unitId');
    expect(payload).not.toHaveProperty('dataSourceUnit');
  });

  it('defaults missing environment to DEVELOP while preserving PROD/TEST', () => {
    const missing = buildSubmitPayload(
      'MYSQL',
      {
        name: 'new-ds',
        businessSystemId: '20',
        environment: '',
        remark: '',
      },
      { host: 'localhost' },
    );
    expect(missing.environment).toBe('DEVELOP');

    const prod = buildSubmitPayload(
      'MYSQL',
      {
        name: 'prod-ds',
        businessSystemId: '20',
        environment: 'PROD',
        remark: '',
      },
      { host: 'localhost' },
    );
    expect(prod.environment).toBe('PROD');
  });
});
