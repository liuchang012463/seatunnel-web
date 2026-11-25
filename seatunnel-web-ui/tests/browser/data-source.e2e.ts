import { expect, test, type Page } from '@playwright/test';

const records = Array.from({ length: 13 }, (_, i) => ({
  id: i + 1,
  name: `数据源样例-${i + 1}`,
  dbType: 'MYSQL',
  jdbcUrl: i === 0 ? `jdbc:mysql://localhost:3306/${'dataset_'.repeat(12)}` : 'jdbc:short',
  unitName: '验收单位',
  businessSystemName: '验收系统',
  connStatus: 'CONNECTED_SUCCESS',
  status: 'ENABLED',
  sourceEnabled: true,
  sinkEnabled: true,
}));

async function mockDataSourceList(page: Page) {
  await page.route('**/api/v1/data-source/page', (route) => {
    const { name, pageNo, pageSize } = route.request().postDataJSON();
    const filtered = records.filter((record) => !name || record.name === name);
    const start = (pageNo - 1) * pageSize;
    return route.fulfill({
      json: {
        code: 0,
        data: { bizData: filtered.slice(start, start + pageSize), pagination: { pageNo, pageSize, total: filtered.length } },
      },
    });
  });
  await page.route('**/api/v1/data-source-units/active', (route) => route.fulfill({
    json: { code: 0, data: [{ id: 7, unitName: '验收单位' }] },
  }));
  await page.route('**/api/v1/business-systems/active?*', (route) => route.fulfill({
    json: { code: 0, data: [{ id: 9, systemName: '验收系统' }] },
  }));
}

test('数据源列表固定面板与分页，仅表体滚动，窄屏可访问行操作', async ({ page }) => {
  await mockDataSourceList(page);
  await page.setViewportSize({ width: 1440, height: 900 });
  await page.goto('/data-source');
  await page.getByText('列表', { exact: true }).click();

  const panel = page.locator('.datasource-catalog-panel--list');
  const header = panel.locator('.ant-table-header');
  const body = panel.locator('.ant-table-body');
  const pagination = panel.locator('.ant-pagination');
  await expect(pagination.getByText('共 13 条')).toBeVisible();
  await expect(body.locator('tr[data-row-key]')).toHaveCount(12);
  await expect(pagination).toBeInViewport({ ratio: 1 });
  const initial = {
    paginationTop: (await pagination.boundingBox())!.y,
    headerTop: (await header.boundingBox())!.y,
    panelHeight: (await panel.boundingBox())!.height,
  };

  const row = body.locator('tr[data-row-key]').first();
  await row.getByRole('button', { name: records[0].name, exact: true }).focus();
  await page.keyboard.press('Tab');
  const longAddress = row.locator('.datasource-catalog-name-url');
  await expect(longAddress).toBeFocused();
  await expect(page.getByRole('tooltip')).toHaveText(records[0].jdbcUrl);
  const shortAddress = body.locator('tr[data-row-key]').nth(1).locator('.datasource-catalog-name-url');
  await shortAddress.hover();
  await expect(shortAddress).not.toHaveAttribute('title');
  await expect(shortAddress).not.toHaveAttribute('tabindex', '0');

  await body.evaluate((element) => { element.scrollTop = element.scrollHeight; });
  await expect(body.getByText(records[11].name, { exact: true })).toBeInViewport();
  expect((await header.boundingBox())!.y).toBeCloseTo(initial.headerTop, 0);
  expect((await pagination.boundingBox())!.y).toBeCloseTo(initial.paginationTop, 0);
  await pagination.locator('.ant-pagination-next button').click();
  await expect(body.getByText(records[12].name, { exact: true })).toBeInViewport();
  expect((await pagination.boundingBox())!.y).toBeCloseTo(initial.paginationTop, 0);

  const search = page.getByRole('textbox', { name: '根据数据源名称搜索' });
  await search.fill(records[0].name);
  await expect(pagination.getByText('共 1 条')).toBeVisible();
  await expect(body.locator('tr[data-row-key]')).toHaveCount(1);
  expect((await panel.boundingBox())!.height).toBeCloseTo(initial.panelHeight, 0);
  expect((await pagination.boundingBox())!.y).toBeCloseTo(initial.paginationTop, 0);
  await search.fill('不存在的数据源');
  await expect(body.getByText('没有找到匹配的数据源')).toBeVisible();
  await expect(pagination.getByText('共 0 条')).toBeVisible();
  await expect(pagination.locator('.ant-pagination-next button')).toBeDisabled();
  expect((await panel.boundingBox())!.height).toBeCloseTo(initial.panelHeight, 0);
  expect((await pagination.boundingBox())!.y).toBeCloseTo(initial.paginationTop, 0);

  await page.setViewportSize({ width: 1280, height: 800 });
  const clear = body.getByRole('button', { name: '清除筛选' });
  await expect(clear).toBeInViewport({ ratio: 1 });
  await clear.click();
  await expect(pagination.getByText('共 13 条')).toBeVisible();
  const more = panel.getByRole('button', { name: `更多数据源操作 ${records[0].name}`, exact: true });
  await expect(more).toBeInViewport({ ratio: 1 });
  await more.click();
  await expect(page.getByRole('menuitem', { name: '停用' })).toBeVisible();
});

test('数据源新增表单显示计数和行内提交结果，并阻止重复请求', async ({ page }) => {
  await mockDataSourceList(page);
  await page.route('**/api/v1/data-source/plugin/config?*', (route) => route.fulfill({
    json: { code: 0, data: { formFields: [] } },
  }));
  let requests = 0;
  let releaseFirst!: () => void;
  const firstResponse = new Promise<void>((resolve) => { releaseFirst = resolve; });
  await page.route('**/api/v1/data-source', async (route) => {
    if (++requests === 1) {
      await firstResponse;
      return route.fulfill({ json: { code: 2, msg: '验收样例名称已存在' } });
    }
    return route.fulfill({ json: { code: 0, data: true } });
  });

  await page.setViewportSize({ width: 1440, height: 900 });
  await page.goto('/data-source');
  const create = page.locator('.datasource-create-button');
  await create.click();
  const dialog = page.getByRole('dialog', { name: /新增数据源/ });
  await dialog.getByRole('button', { name: /^MYSQL/ }).click();
  const name = dialog.getByRole('textbox', { name: /数据源名称/ });
  const status = dialog.locator('.datasource-editor-submit-status');
  const submit = dialog.getByRole('button', { name: /完\s*成/ });
  await submit.click();
  await expect(status).toHaveText('请检查并补全标记的字段后重试。');
  expect(requests).toBe(0);

  await name.fill('测'.repeat(101));
  await expect(name).toHaveValue('测'.repeat(100));
  const count = dialog.locator('.ant-input-show-count-suffix');
  await expect(count).toHaveText(/100\s*\/\s*100/);
  await name.fill('验收样例');
  await expect(count).toHaveText(/4\s*\/\s*100/);
  await dialog.getByRole('combobox', { name: /数据源单位/ }).click();
  await page.getByText('验收单位', { exact: true }).last().click();
  await dialog.getByRole('combobox', { name: /业务系统/ }).click();
  await page.getByText('验收系统', { exact: true }).last().click();

  await submit.click();
  try {
    await expect(status).toHaveText('正在保存数据源…');
    await expect(submit).toBeDisabled();
    await submit.click({ force: true });
    await expect.poll(() => requests).toBe(1);
  } finally {
    releaseFirst();
  }
  await expect(status).toContainText('验收样例名称已存在');
  await expect(name).toHaveValue('验收样例');
  await expect(submit).toBeEnabled();

  await name.fill('另一条验收样例');
  await submit.click();
  await expect(status).toHaveAttribute('data-phase', 'success');
  await expect(dialog).toBeHidden();
  await expect(create).toBeFocused();
  expect(requests).toBe(2);
});
