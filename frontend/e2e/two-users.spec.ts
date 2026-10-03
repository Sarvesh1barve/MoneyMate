import { test, expect, Page } from '@playwright/test';
import { writeFile, unlink, mkdir } from 'node:fs/promises';
import path from 'node:path';
const password = 'MoneyMate-test-password!';
const testPort = process.env['MM_TEST_PORT'] || '8080';
const apiOrigin = `http://localhost:${testPort}`;
const marker = path.resolve(`../.local/pause-api-${testPort}`);
async function register(page: Page, email: string, name: string) {
  await page.goto('./');
  await page.getByLabel('API endpoint', { exact: true }).fill(apiOrigin);
  await page.getByRole('button', { name: 'Save endpoint', exact: true }).click();
  await page.getByRole('button', { name: 'New here? Create an account' }).click();
  await page.getByLabel('Your name', { exact: true }).fill(name);
  await page.getByLabel('Email', { exact: true }).fill(email);
  await page.getByLabel('Password', { exact: true }).fill(password);
  await page.getByRole('button', { name: /^Create account/ }).click();
  await expect(page.getByRole('heading', { name: 'A clearer picture.' })).toBeVisible();
  await synced(page);
}
async function synced(page: Page) {
  await page.getByRole('button', { name: 'Sync now', exact: true }).click();
  await expect(page.locator('.status')).toHaveText('Synced', { timeout: 30000 });
}
async function navigate(page: Page, id: string) {
  await page.locator('.sidebar nav').getByRole('link', { name: id, exact: false }).click();
}
async function expense(page: Page, description: string, amount: string, payer?: string) {
  await page.getByRole('button', { name: '＋ Add expense', exact: true }).click();
  const d = page.getByRole('dialog');
  await d.getByLabel('Description', { exact: true }).fill(description);
  await d.getByLabel('Amount', { exact: true }).fill(amount);
  if (payer) await d.getByLabel('Paid by').selectOption({ label: payer });
  await d.getByRole('button', { name: 'Save expense', exact: true }).click();
  await expect(d).not.toBeVisible();
}
test('two users, private money, shared trip, revocation, real server interruption, and offline PWA', async ({
  browser,
}) => {
  const contextA = await browser.newContext(),
    contextB = await browser.newContext();
  const a = await contextA.newPage(),
    b = await contextB.newPage();
  const errors: string[] = [];
  a.on('pageerror', (e) => errors.push(e.message));
  b.on('pageerror', (e) => errors.push(e.message));
  const suffix = Date.now();
  const alice = `alice-${suffix}@test.invalid`,
    bob = `bob-${suffix}@test.invalid`;
  await register(a, alice, 'Alice');
  await register(b, bob, 'Bob');
  await a.getByRole('button', { name: '＋ Add transaction', exact: true }).click();
  let d = a.getByRole('dialog');
  await d.getByLabel('Description', { exact: true }).fill('Alice private lunch');
  await d.getByLabel('Amount', { exact: true }).fill('120.00');
  await d.getByRole('button', { name: 'Save transaction', exact: true }).click();
  await synced(a);
  await synced(b);
  await expect(b.getByText('Alice private lunch')).toHaveCount(0);
  await navigate(a, 'Trips');
  await a.getByRole('button', { name: '＋ Create group', exact: true }).click();
  d = a.getByRole('dialog');
  await d.getByLabel('Name', { exact: true }).fill('Goa weekend');
  await d.getByRole('button', { name: 'Save trip', exact: true }).click();
  await synced(a);
  await a.getByRole('tab', { name: 'Participants' }).click();
  await a.getByRole('button', { name: '＋ Add participant', exact: true }).click();
  d = a.getByRole('dialog');
  await d.getByLabel('Name', { exact: true }).fill('Bob');
  await d.getByRole('button', { name: 'Save participant', exact: true }).click();
  await synced(a);
  await a.getByRole('button', { name: 'Invite', exact: true }).click();
  const invitation = await a.getByLabel('Single-use invitation · expires in 24 hours').inputValue();
  await navigate(b, 'Trips');
  await b.getByText('Have an invitation? Join a trip', { exact: true }).click();
  await b.getByLabel('Invitation link or code').fill(invitation);
  await b.getByRole('button', { name: 'Join trip', exact: true }).click();
  await expect(b.getByRole('heading', { name: 'Goa weekend', exact: true })).toBeVisible();
  await expense(a, 'Shared dinner', '100.00', 'Alice');
  await synced(a);
  await expense(b, 'Shared breakfast', '40.00', 'Bob');
  await synced(b);
  await synced(a);
  await a.getByRole('tab', { name: 'Overview', exact: true }).click();
  await expect(a.locator('.settlement-suggestion')).toContainText('Bob → Alice');
  await expect(a.locator('.settlement-suggestion')).toContainText('₹30.00');
  // Stop the actual Spring context while its PostgreSQL database remains running.
  await b.getByRole('tab', { name: 'Expenses', exact: true }).click();
  await contextB.setOffline(true);
  await b
    .locator('.transaction-row')
    .filter({ hasText: 'Shared dinner' })
    .getByRole('button', { name: 'Edit', exact: true })
    .click();
  await b.getByRole('dialog').getByLabel('Description', { exact: true }).fill('Bob dinner draft');
  await b.getByRole('dialog').getByRole('button', { name: 'Save expense', exact: true }).click();
  await a.getByRole('tab', { name: 'Expenses', exact: true }).click();
  await a
    .locator('.transaction-row')
    .filter({ hasText: 'Shared dinner' })
    .getByRole('button', { name: 'Edit', exact: true })
    .click();
  await a.getByRole('dialog').getByLabel('Description', { exact: true }).fill('Server dinner');
  await a.getByRole('dialog').getByRole('button', { name: 'Save expense', exact: true }).click();
  await synced(a);
  await contextB.setOffline(false);
  await b.getByRole('button', { name: 'Sync now', exact: true }).click();
  await expect(b.locator('.status')).toHaveText('Conflict needs review');
  await navigate(b, 'Settings');
  await expect(b.locator('.conflict')).toContainText('Bob dinner draft');
  await expect(b.locator('.conflict')).toContainText('Server dinner');
  await b
    .getByRole('button', { name: 'Use latest server version / discard draft', exact: true })
    .click();
  await synced(b);
  await navigate(b, 'Trips');
  await a.getByRole('tab', { name: 'Overview', exact: true }).click();
  await mkdir(path.dirname(marker), { recursive: true });
  await writeFile(marker, 'pause');
  await expect
    .poll(
      async () => {
        try {
          await fetch(`${apiOrigin}/api/health`);
          return false;
        } catch {
          return true;
        }
      },
      { timeout: 30000 },
    )
    .toBe(true);
  try {
    await expense(a, 'Offline chai', '20.00', 'Alice');
    await expect(a.locator('.status')).toHaveText('Server unavailable');
    await a.getByRole('tab', { name: 'Expenses', exact: true }).click();
    await expect(a.getByText('Offline chai', { exact: true })).toHaveCount(1);
    await expect(a.getByRole('heading', { name: 'Goa weekend', exact: true })).toBeVisible();
  } finally {
    await unlink(marker);
  }
  await expect
    .poll(
      async () => {
        try {
          return (await fetch(`${apiOrigin}/api/health`)).ok;
        } catch {
          return false;
        }
      },
      { timeout: 30000 },
    )
    .toBe(true);
  await synced(a);
  await synced(a);
  await synced(b);
  await b.getByRole('tab', { name: 'Expenses', exact: true }).click();
  await expect(b.getByText('Offline chai', { exact: true })).toHaveCount(1);
  await a.reload();
  await expect(a.getByRole('heading', { name: 'Goa weekend', exact: true })).toBeVisible();
  await expect(a.locator('.status')).toContainText('Offline workspace');
  await a.getByRole('button', { name: 'Sign in', exact: true }).click();
  await a.getByLabel('Email', { exact: true }).fill(alice);
  await a.getByLabel('Password', { exact: true }).fill(password);
  await a
    .locator('.auth-card form')
    .getByRole('button', { name: /Sign in/ })
    .click();
  await synced(a);
  await a.getByRole('tab', { name: 'Overview', exact: true }).click();
  await expect(a.locator('.settlement-suggestion')).toContainText('₹40.00');
  await a.getByRole('button', { name: 'Record as Pending', exact: true }).click();
  await synced(a);
  await a.getByRole('tab', { name: 'Settlements', exact: true }).click();
  await a.getByRole('button', { name: 'Mark Paid', exact: true }).click();
  await synced(a);
  await a.getByRole('button', { name: 'Confirm receipt', exact: true }).click();
  await synced(a);
  await a.getByRole('tab', { name: 'Overview', exact: true }).click();
  await expect(a.getByRole('heading', { name: 'All square.' })).toBeVisible();
  await a.getByRole('tab', { name: 'Participants', exact: true }).click();
  a.once('dialog', (dialog) => dialog.accept());
  await a.getByRole('button', { name: 'Remove access', exact: true }).click();
  await synced(a);
  await synced(b);
  await expect(b.getByRole('heading', { name: 'Goa weekend', exact: true })).toHaveCount(0);
  await expect(b.getByRole('heading', { name: 'A trip starts with a group.' })).toBeVisible();
  await a.getByRole('tab', { name: 'Overview', exact: true }).click();
  await a.setViewportSize({ width: 390, height: 844 });
  await expect(a.locator('.mobile-nav')).toBeVisible();
  expect(await a.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
  await a.screenshot({ path: 'test-results/mobile-trip.png', fullPage: true });
  await a.setViewportSize({ width: 1440, height: 1000 });
  await navigate(a, 'Home');
  await a.screenshot({ path: 'test-results/desktop-home.png', fullPage: true });
  await a.evaluate(async () => {
    await navigator.serviceWorker.ready;
  });
  await a.reload();
  await a.evaluate(async () => {
    await navigator.serviceWorker.ready;
  });
  await contextA.setOffline(true);
  await a.reload();
  await expect(a.getByRole('heading', { name: 'A clearer picture.' })).toBeVisible();
  await expect(a.getByText('Alice private lunch', { exact: true })).toBeVisible();
  await contextA.setOffline(false);
  expect(errors).toEqual([]);
  await contextA.close();
  await contextB.close();
});
