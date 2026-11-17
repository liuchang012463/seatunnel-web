import { defineConfig } from '@playwright/test';

export default defineConfig({
  testDir: './tests/browser',
  testMatch: '**/*.e2e.ts',
  workers: 1,
  use: { baseURL: 'http://127.0.0.1:18081', locale: 'zh-CN' },
  webServer: {
    command: 'node tests/browser/serve-distribution.cjs',
    url: 'http://127.0.0.1:18081',
    timeout: 15000,
  },
});
