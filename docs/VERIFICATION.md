# Local verification report

## Trip expense ordering and filters: 2026-10-03

The Expenses tab now defaults to newest **addition** time, shows the local date and time each expense was added, and separates that from the expense's chosen calendar date. Search, payer, inclusive expense-date filters, and added-time/date/amount sorting work together. The list does not change its addition order when an expense is edited. A new server `created_at` column is immutable; migration V4 reconstructs earlier expense times from creation activity when available, otherwise uses their last known update timestamp. Pending offline entries show their local addition time until the authoritative server creation time arrives.

`npm.cmd test` passed **20/20** frontend tests; `backend\mvnw.cmd -q test` passed **20/20** tests (15 integration and 5 money) with disposable PostgreSQL and all four migrations. `npm.cmd run build:pages` passed. The 390 × 844 Chromium flow passed with two expenses: it checked default newest-added order, displayed added time, alternate expense-date order, payer/search/date filters, and the prior invite/split/remembered-sign-in/passkey flow. A custom-format backup of the production database was created before V4 and its archive catalog was readable; a full restore is still untested. Physical iPhone layout and time-zone display have not been tested.

## Trip and sign-in update: 2026-10-02

On the new `feat/trip-invites-device-login` branch, `npm.cmd test` passed **17/17** frontend tests and `npm.cmd run build:pages` built the `/MoneyMate/` bundle. `backend\mvnw.cmd -q test` passed **19/19** tests against disposable native PostgreSQL, including a general single-use invitation, remembered-device challenge replay/revocation, and real WebAuthn registration/assertion/replay/revocation. A 390 × 844 Chromium browser test passed: two trips could be created, a link opened in a second account and joined automatically, a ₹100 equal-split expense displayed “Bob pays Alice ₹50”, the remembered session survived reload, and a virtual platform passkey signed in after logout. The original comprehensive two-user/offline browser test also passed against the updated backend.

The current-user participant now defaults as the expense payer. The last browser rerun used a freshly rebuilt frontend and passed. Testing used port 8081 and a disposable database; production data was not used. A pre-migration custom-format production backup was made and its archive catalog read successfully, but a full restore remains untested. Physical iPhone Face ID/Touch ID, share sheet, and Pages deployment have not been verified in this local run.

The completed local checks below ran on Windows with Node 22.14.0, Java 21.0.12, Angular 21.2.25, Spring Boot 3.5.16, Maven 3.9.11, native test PostgreSQL 14.15, and Playwright Chromium 153. Tests used synthetic accounts and a disposable database, never a production database. Run logs were generated on 2026-10-01.

## Results

| Check | Result |
| --- | --- |
| `npm.cmd ci` | Passed; clean installation from lockfile |
| `npm.cmd test` | **15 passed**, none failed/skipped |
| `npm.cmd run build:pages` | Passed without Angular warnings/errors; about 398 kB initial assets, about 102 kB estimated compressed transfer |
| `backend\mvnw.cmd -B verify` | **16 passed** (11 PostgreSQL integration + 5 money tests); executable production jar built |
| `npm.cmd run test:e2e` | **1 comprehensive two-browser test passed**, final run about 27 seconds |
| Pages base path | Production build served and navigated under `/MoneyMate/`; generated service-worker asset URLs verified under this path |
| PWA offline shell | Service worker activated; full offline reload rendered cached application and personal data |
| Mobile layout | Chromium at 390 × 844; bottom navigation visible, no horizontal page overflow |
| Desktop layout | Screenshot inspected at 1440-pixel width |
| Deployment boundary | Workflow uploads only `frontend/dist/frontend/browser`; production jar does not contain BrowserTestServer, integration test classes, or embedded PostgreSQL |
| Repository hygiene | `git diff --cached --check` passed; environment files, local tools, databases, backups, dependencies, builds, browser traces/screenshots excluded from Git |

## Automated coverage

Frontend tests cover minor-unit parsing and currency precision, local calendar dates, account balances, transfers excluded from spending, future-dated entries, fixed daily/weekly/monthly budgets including month-end boundaries, category/subcategory and trip budgets, all split methods, deterministic rounding, large amounts, reduced settlements, confirmation semantics, local transfer validation, IndexedDB persistence/user separation, sequential edits, lost-response replay, conflict preservation/rebasing, revoked-trip cache removal, and tombstones.

Backend tests use real native PostgreSQL with both Flyway migrations. They verify registration/login/logout and `/me`, anonymous denial, disallowed CORS origins, login rate limiting, session expiry, private personal records and unauthorized account references, valid/invalid transfers and amounts, private trip-budget authorization, default categories, controlled invitations, expiry/single use, membership revocation, member permissions, split totals/currency enforcement, settlement permissions, operation-ID deduplication, changed-payload retry rejection, stale versions, tombstones, idempotent trip deletion, input-size limits, and concurrent writes. Separate concurrent-edit tests exercise both one user's private record and two distinct members updating the same trip expense; exactly one write succeeds per expected version.

The browser scenario registers Alice and Bob in separate contexts, checks personal-data isolation, creates a group, adds a named participant and accepts an invitation, writes shared expenses from both users, and verifies ₹30 owed after ₹100/₹40 equal-split expenses. It creates a stale offline draft, checks both drafts in conflict review, and explicitly accepts the latest server record. It then **stops the actual Spring context**, saves a ₹20 expense locally, restarts Spring against the same still-running PostgreSQL database, and verifies exactly one upload and a ₹40 settlement. It refreshes/re-authenticates, records Pending → Paid → Confirmed, removes Bob’s access and verifies that his trip disappears, checks mobile overflow, captures screenshots, and reloads the cached PWA with the browser fully offline. No JavaScript page errors were observed.

## Deployment follow-up: 2026-10-02

- Pushed commit `f36f22a` to `feat/self-hosted-multiuser` in `Sarvesh1barve/MoneyMate`, without rewriting history. The first branch became the remote default; `main` does not yet exist.
- [GitHub CI run 36947884127](https://github.com/Sarvesh1barve/MoneyMate/actions/runs/36947884127) passed both frontend and backend jobs.
- Enabled GitHub Pages with Actions, set the public API origin repository variable, and successfully completed [Pages run 36948217582](https://github.com/Sarvesh1barve/MoneyMate/actions/runs/36948217582). The published `/MoneyMate/` page returned HTTP 200 and rendered the sign-in screen in the browser.
- Installed official PostgreSQL 17.11 Windows binaries and initialized a persistent loopback-only cluster using SCRAM-SHA-256, with a restricted application role and ignored locally generated secrets. Started the production jar; both Flyway migrations applied successfully. Production contained zero users at this check.
- Started the authenticated ngrok HTTPS tunnel with request inspection disabled. Both local and public `/api/health` returned HTTP 200 with `{"status":"up"}`. Anonymous `/api/me` returned HTTP 401.
- Re-ran the background starter while services were running; it reused them without creating duplicate backend/database processes. Stop commands have not been exercised because services were requested to remain running. See [operations](OPERATIONS.md).

## What has not been verified

- The graphical PostgreSQL installer has not been exercised; this laptop uses the official native binary distribution instead. The documented `pg_dump`/`pg_restore` recovery procedure has **not** been executed. Restore into a new isolated database before relying on a backup.
- The two-user browser suite passed against the local test environment. It has not been repeated on two separate physical devices through the public tunnel; the public deployment checks covered page rendering, runtime configuration, CORS, and API availability.
- No native installed-PWA launch, iOS/Safari install flow, OS share sheet, clipboard permission flow, or third-party-cookie-blocking browser profile was manually tested. The authentication design uses no cookies.
- The automated browser scenario concentrates on the two-user trip, authorization, conflict, and offline flows. It is not an exhaustive browser test of every account/category/budget/theme form; those calculations and relevant backend validations are tested separately.

## Implementation limits

Sessions require re-login after refresh or their 30-minute expiry unless the user opts into remembered sign-in for up to 30 days on that browser. A passkey may also be used for fresh sign-in on a supported device. Cached data is available on a trusted device without local encryption. Password recovery/email verification, recurring transactions/budgets, bank integration, payment execution, multiple payer entry, currency conversion, instant realtime updates, and JSON import are not implemented. Sharing a settlement summary never grants access or changes payment status. Full snapshots and indefinitely retained operation IDs/tombstones are intended for a small group. See [architecture](ARCHITECTURE.md) for these choices and ownership rules.

## Main files

- `frontend/src/app/workspace.*`, `frontend/src/styles.css`: responsive screens, forms, navigation and themes.
- `frontend/src/app/finance.ts`, `models.ts`: integer-money calculations and models.
- `frontend/src/app/cache.ts`, `sync-engine.ts`, `store.ts`: per-user IndexedDB, outbox, auth connection and synchronization.
- `frontend/public/`, `ngsw-config.json`, `angular.json`: runtime public configuration, manifest, icons, service worker and build.
- `backend/src/main/java/com/moneymate/`: authentication/security, authorized domain writes, invitations and API.
- `backend/src/main/resources/db/migration/`: relational tables, typed financial records/views and constraints.
- `frontend/tests/`, `frontend/e2e/`, `backend/src/test/`: finance, persistence, authorization and browser tests.
- `.github/workflows/`: CI and frontend-only Pages publishing.
- `scripts/start-backend.ps1`, `scripts/start-local-stack.ps1`, `scripts/stop-local-stack.ps1`, `backend/.env.example`, Maven wrapper: local operation.
- `README.md`, `docs/ARCHITECTURE.md`, `docs/API.md`: exact commands, deployment, backup/recovery, ownership and API documentation.
