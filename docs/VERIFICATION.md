# Local verification report

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

## What has not been verified or deployed

- No permanent PostgreSQL 17 installation or production database/credentials was configured. The disposable test database is not your authoritative financial database.
- Native PostgreSQL installer steps and the documented `pg_dump`/`pg_restore` recovery procedure have **not** been executed here. Those tools are not included in the embedded test runtime. Restore into a new isolated database before relying on a backup.
- ngrok 3.39.9 is installed, but no authenticated HTTPS tunnel was started, and no remote two-device/tunnel test was performed.
- GitHub Pages has not been enabled or published, the feature branch has not been pushed, and workflows have not run on GitHub. At initial inspection, the remote repository had no commits or actual `main` branch. The README explains initial-branch and Pages setup.
- No native installed-PWA launch, iOS/Safari install flow, OS share sheet, clipboard permission flow, or third-party-cookie-blocking browser profile was manually tested. The authentication design uses no cookies.
- The automated browser scenario concentrates on the two-user trip, authorization, conflict, and offline flows. It is not an exhaustive browser test of every account/category/budget/theme form; those calculations and relevant backend validations are tested separately.

## Implementation limits

Sessions require re-login after refresh or their 30-minute expiry. Cached data is available on a trusted device without local encryption. Password recovery/email verification, recurring transactions/budgets, bank integration, payment execution, multiple payer entry, currency conversion, instant realtime updates, and JSON import are not implemented. Sharing a settlement summary never grants access or changes payment status. Full snapshots and indefinitely retained operation IDs/tombstones are intended for a small group. See [architecture](ARCHITECTURE.md) for these choices and ownership rules.

## Main files

- `frontend/src/app/workspace.*`, `frontend/src/styles.css`: responsive screens, forms, navigation and themes.
- `frontend/src/app/finance.ts`, `models.ts`: integer-money calculations and models.
- `frontend/src/app/cache.ts`, `sync-engine.ts`, `store.ts`: per-user IndexedDB, outbox, auth connection and synchronization.
- `frontend/public/`, `ngsw-config.json`, `angular.json`: runtime public configuration, manifest, icons, service worker and build.
- `backend/src/main/java/com/moneymate/`: authentication/security, authorized domain writes, invitations and API.
- `backend/src/main/resources/db/migration/`: relational tables, typed financial records/views and constraints.
- `frontend/tests/`, `frontend/e2e/`, `backend/src/test/`: finance, persistence, authorization and browser tests.
- `.github/workflows/`: CI and frontend-only Pages publishing.
- `scripts/start-backend.ps1`, `backend/.env.example`, Maven wrapper: local operation.
- `README.md`, `docs/ARCHITECTURE.md`, `docs/API.md`: exact commands, deployment, backup/recovery, ownership and API documentation.
