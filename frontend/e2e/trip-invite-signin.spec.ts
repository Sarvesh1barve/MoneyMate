import { test, expect } from '@playwright/test';
const api = `http://localhost:${process.env['MM_TEST_PORT'] || '8080'}`;

test('mobile can create two trips, invite by link, explain a split, and restore sign-in with a passkey', async ({
  browser,
}) => {
  const ownerContext = await browser.newContext({ viewport: { width: 390, height: 844 } });
  const guestContext = await browser.newContext({ viewport: { width: 390, height: 844 } });
  const owner = await ownerContext.newPage(),
    guest = await guestContext.newPage();
  const cdp = await ownerContext.newCDPSession(owner);
  await cdp.send('WebAuthn.enable');
  await cdp.send('WebAuthn.addVirtualAuthenticator', {
    options: {
      protocol: 'ctap2',
      transport: 'internal',
      hasResidentKey: true,
      hasUserVerification: true,
      isUserVerified: true,
      automaticPresenceSimulation: true,
    },
  });
  const suffix = Date.now();
  await owner.goto('./');
  await owner.getByLabel('API endpoint', { exact: true }).fill(api);
  await owner.getByRole('button', { name: 'Save endpoint' }).click();
  await owner.getByRole('button', { name: 'New here? Create an account' }).click();
  await owner.getByLabel('Your name').fill('Alice');
  await owner.getByLabel('Email').fill(`alice-flow-${suffix}@test.invalid`);
  await owner.getByLabel('Password').fill('MoneyMate-flow-password!');
  await owner.getByLabel('Keep me signed in on this device for 30 days').check();
  await owner.getByRole('button', { name: /^Create account/ }).click();
  await expect(owner.getByRole('heading', { name: 'A clearer picture.' })).toBeVisible();
  await owner.locator('.mobile-nav').getByRole('link', { name: 'Settings' }).click();
  await owner.getByLabel('Passkey name').fill('Virtual fingerprint');
  await owner.getByRole('button', { name: 'Add a passkey' }).click();
  await expect(owner.getByText('Passkey added.')).toBeVisible();
  await owner.locator('.mobile-nav').getByRole('link', { name: 'Trips' }).click();
  for (const name of ['Goa trip', 'Jaipur trip']) {
    await expect(owner.getByRole('button', { name: '＋ Create group' })).toBeVisible();
    await owner.getByRole('button', { name: '＋ Create group' }).click();
    const dialog = owner.getByRole('dialog');
    await dialog.getByLabel('Name', { exact: true }).fill(name);
    await dialog.getByRole('button', { name: 'Save trip' }).click();
    await expect(dialog).not.toBeVisible();
    await owner.getByRole('button', { name: 'Sync now', exact: true }).click();
    await expect(owner.locator('.status')).toHaveText('Synced', { timeout: 30000 });
  }
  await expect(owner.getByRole('button', { name: '＋ New trip' })).toBeVisible();
  await expect(owner.getByRole('button', { name: 'Goa trip' })).toBeVisible();
  await expect(owner.getByRole('button', { name: 'Jaipur trip' })).toBeVisible();
  await owner.getByRole('button', { name: 'Goa trip' }).click();
  await owner.locator('.trip-header').getByRole('button', { name: 'Invite someone ↗' }).click();
  const link = await owner.getByLabel('Single-use invitation · expires in 24 hours').inputValue();
  await guest.goto(link);
  await expect(guest.getByRole('heading', { name: 'You have a trip invitation' })).toBeVisible();
  await guest.getByLabel('API endpoint', { exact: true }).fill(api);
  await guest.getByRole('button', { name: 'Save endpoint' }).click();
  await guest.getByRole('button', { name: 'New here? Create an account' }).click();
  await guest.getByLabel('Your name').fill('Bob');
  await guest.getByLabel('Email').fill(`bob-flow-${suffix}@test.invalid`);
  await guest.getByLabel('Password').fill('MoneyMate-flow-password!');
  await guest.getByRole('button', { name: /^Create account/ }).click();
  await expect(guest.getByRole('heading', { name: 'Goa trip', exact: true })).toBeVisible({
    timeout: 30000,
  });
  await expect(guest.getByRole('heading', { name: 'You have a trip invitation' })).toHaveCount(0);
  await owner.getByRole('button', { name: 'Sync now', exact: true }).click();
  await expect(owner.locator('.status')).toHaveText('Synced', { timeout: 30000 });
  await owner.locator('.trip-header').getByRole('button', { name: '＋ Add expense' }).click();
  const expense = owner.getByRole('dialog');
  await expense.getByLabel('Description').fill('Shared dinner');
  await expense.getByLabel('Amount', { exact: true }).fill('100.00');
  await expense.getByLabel('Date').fill('2026-10-02');
  await expense.getByRole('button', { name: 'Save expense' }).click();
  await owner.getByRole('button', { name: 'Sync now', exact: true }).click();
  await expect(owner.locator('.payment-line')).toContainText('Bob pays Alice');
  await expect(owner.locator('.payment-line')).toContainText('₹50.00');
  expect(await owner.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
  await owner.getByRole('tab', { name: 'Expenses' }).click();
  await owner.getByText('Who owes what for this expense?').click();
  await expect(owner.getByText('owes Alice')).toBeVisible();
  await expect(owner.getByText('own share')).toBeVisible();
  await owner.locator('.trip-header').getByRole('button', { name: '＋ Add expense' }).click();
  await expense.getByLabel('Description').fill('Hotel');
  await expense.getByLabel('Amount', { exact: true }).fill('40.00');
  await expense.getByLabel('Date').fill('2026-10-01');
  await expense.getByLabel('Paid by').selectOption({ label: 'Bob' });
  await expense.getByRole('button', { name: 'Save expense' }).click();
  await owner.getByRole('button', { name: 'Sync now', exact: true }).click();
  await expect(owner.locator('.status')).toHaveText('Synced', { timeout: 30000 });
  const list = owner.locator('.trip-expense-list');
  const rows = list.locator('.transaction-row');
  await expect(rows).toHaveCount(2);
  await expect(rows.nth(0)).toContainText('Hotel');
  await expect(rows.nth(1)).toContainText('Shared dinner');
  await expect(rows.nth(0).locator('.expense-added')).toContainText(/Added .+, \d{1,2}:\d{2}/);
  await list.getByLabel('Sort').selectOption('date-newest');
  await expect(rows.nth(0)).toContainText('Shared dinner');
  await list.getByLabel('Paid by').selectOption({ label: 'Bob' });
  await expect(rows).toHaveCount(1);
  await expect(rows.first()).toContainText('Hotel');
  await list.getByRole('button', { name: 'Clear filters' }).click();
  await list.getByLabel('Search expenses').fill('dinner');
  await expect(rows).toHaveCount(1);
  await expect(rows.first()).toContainText('Shared dinner');
  await list.getByRole('button', { name: 'Clear filters' }).click();
  await list.getByText('Filter by expense date').click();
  await list.getByLabel('From').fill('2026-10-02');
  await expect(rows).toHaveCount(1);
  await expect(rows.first()).toContainText('Shared dinner');
  await owner.reload();
  await expect(owner.getByRole('heading', { name: 'Goa trip', exact: true })).toBeVisible();
  await expect(owner.locator('.status')).toHaveText('Synced', { timeout: 30000 });
  await owner.locator('.mobile-nav').getByRole('link', { name: 'Settings' }).click();
  owner.once('dialog', (dialog) => dialog.accept());
  await owner.getByRole('button', { name: 'Sign out & clear local data' }).click();
  await expect(owner.getByRole('button', { name: 'Sign in with a passkey' })).toBeVisible();
  await owner.getByRole('button', { name: 'Sign in with a passkey' }).click();
  await expect(owner.getByRole('heading', { name: 'Your workspace, your way.' })).toBeVisible({
    timeout: 30000,
  });
  await ownerContext.close();
  await guestContext.close();
});
