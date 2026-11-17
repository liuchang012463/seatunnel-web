import { test, expect } from '@playwright/test';

test('desktop dashboard retains topology height and separate rails', async ({ page }) => {
  for (const width of [1920, 1440, 1366, 1024]) {
    await page.setViewportSize({ width, height: 768 });
    await page.goto('/bi');
    await expect(page.locator('.topology-stage')).toBeAttached();
    const boxes = await page.evaluate(() => {
      const rect = (selector: string) => {
        const r = document.querySelector(selector)!.getBoundingClientRect();
        return { x: r.x, y: r.y, right: r.right, bottom: r.bottom, height: r.height };
      };
      return { left: rect('.left-rail'), center: rect('.center-stage'), right: rect('.right-rail'), topology: rect('.topology-stage') };
    });
    expect(boxes.topology.height).toBeGreaterThan(250);
    const overlap = (a: typeof boxes.left, b: typeof boxes.left) =>
      a.x < b.right && b.x < a.right && a.y < b.bottom && b.y < a.bottom;
    expect(overlap(boxes.left, boxes.center)).toBe(false);
    expect(overlap(boxes.right, boxes.center)).toBe(false);
  }
});

test('release proxy serves OpenAPI JSON and compresses JavaScript', async ({ page, request }) => {
  await page.goto('/open-api');
  await expect(page.getByText('健康检查', { exact: true }).first()).toBeVisible();
  const spec = await request.get('/v3/api-docs');
  expect(spec.headers()['content-type']).toContain('application/json');
  expect((await spec.json()).openapi).toBe('3.0.1');
  const script = await page.locator('script[src*="umi."]').getAttribute('src');
  const asset = await request.get(script!, { headers: { 'Accept-Encoding': 'gzip' } });
  expect(asset.headers()['content-encoding']).toBe('gzip');
});

test('late filter response cannot replace current rows', async ({ page }) => {
  let release!: () => void;
  let seen!: () => void;
  const gate = new Promise<void>(resolve => { release = resolve; });
  const requested = new Promise<void>(resolve => { seen = resolve; });
  await page.route('**/api/v1/job/batch-definition/page', async route => {
    if (route.request().postDataJSON().jobName === 'Review Alpha') { seen(); await gate; }
    await route.continue();
  });
  await page.goto('/sync/batch-link-up');
  await expect(page.getByText('Review Alpha', { exact: true })).toBeVisible();
  const input = page.getByPlaceholder('请输入任务名');
  await input.fill('Review Alpha');
  await page.getByRole('button', { name: '搜 索', exact: true }).click();
  await requested;
  await input.fill('Review Beta');
  await page.getByRole('button', { name: '搜 索', exact: true }).click();
  await expect(page.getByText('Review Alpha', { exact: true })).toHaveCount(0);
  await expect(page.getByText('Review Beta', { exact: true })).toBeVisible();
  const returned = page.waitForResponse(response => response.url().includes('/batch-definition/page')
    && response.request().postDataJSON()?.jobName === 'Review Alpha');
  release();
  await returned;
  await page.evaluate(() => new Promise(resolve => requestAnimationFrame(() => requestAnimationFrame(resolve))));
  await expect(input).toHaveValue('Review Beta');
  await expect(page.getByText('Review Alpha', { exact: true })).toHaveCount(0);
  await expect(page.getByText('Review Beta', { exact: true })).toBeVisible();
  // A task with no execution must not claim an automatic execution.
  await expect(page.locator('.task-metric-group__badge')).toHaveText('—');
});

test('create datasource hover text has sufficient contrast', async ({ page }) => {
  await page.goto('/data-source');
  const button = page.locator('.datasource-create-button');
  await button.hover();
  const ratio = await button.evaluate(element => {
    const style = getComputedStyle(element);
    const luminance = (rgb: string) => rgb.match(/[\d.]+/g)!.slice(0, 3).map(Number)
      .map(c => c / 255).map(c => c <= 0.04045 ? c / 12.92 : ((c + 0.055) / 1.055) ** 2.4)
      .reduce((sum, c, i) => sum + c * [0.2126, 0.7152, 0.0722][i], 0);
    const values = [luminance(style.color), luminance(style.backgroundColor)].sort((a, b) => b - a);
    return (values[0] + 0.05) / (values[1] + 0.05);
  });
  expect(ratio).toBeGreaterThanOrEqual(4.5);
});
