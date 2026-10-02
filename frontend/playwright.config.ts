import { defineConfig } from '@playwright/test';
export default defineConfig({
  testDir: './e2e',
  timeout: 120000,
  expect: { timeout: 15000 },
  workers: 1,
  use: {
    actionTimeout: 15000,
    baseURL: 'http://localhost:4200/MoneyMate/',
    headless: true,
    screenshot: 'only-on-failure',
    trace: 'retain-on-failure',
  },
  webServer: {
    command: 'node scripts/preview.mjs',
    url: 'http://localhost:4200/MoneyMate/',
    reuseExistingServer: true,
    timeout: 30000,
  },
});
