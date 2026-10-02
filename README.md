# MoneyMate

An Angular PWA for private personal finances and shared trips, backed by Spring Boot and PostgreSQL on your own laptop.

**© 2026 Sarvesh Barve. All rights reserved.** The application renders the current year dynamically. This ownership notice does not assert a registered trademark.

## What is implemented

- Email/password registration and login, BCrypt password hashing, 30-minute bearer sessions, rate limits, logout, `/api/me`, strict CORS, and server-side ownership checks.
- Private income, expenses, transfers, optional accounts, calculated balances, default/custom categories and subcategories, fixed daily/weekly/monthly and category/trip budgets, dashboard charts, search/filter/sort/edit/duplicate/soft delete, future-dated upcoming entries, and light/dark/system themes.
- Shared trips/groups/events/households, named participants, owner-managed single-use invitations, membership revocation, expense editing, five split methods, deterministic rounding, balances, reduced settlement suggestions, Pending/Paid/Confirmed/Cancelled records, and activity history.
- Per-user IndexedDB caches, atomic local writes/outbox, operation-ID deduplication, optimistic versions, tombstones, retained conflict drafts with explicit resolution, automatic polling/reconnect sync and Sync Now.
- GitHub Pages `/MoneyMate/` build, installable PWA shell, mobile bottom navigation, runtime API configuration, tests and a frontend-only deployment workflow.

The repository was empty when inspected. There was no previous UI, storage code, integration, framework, test suite, or deployment to preserve. This implementation was created on `feat/self-hosted-multiuser`. See [architecture and ownership rules](docs/ARCHITECTURE.md), [API reference](docs/API.md), and [verification results](docs/VERIFICATION.md).

## 1. Frontend development

Prerequisites: Node **22.14 or a newer supported Node 22 release**, npm, Java **21**, and PostgreSQL **17** for your durable database. Maven is downloaded by the committed wrapper; Docker is not required. Tests launch a disposable native PostgreSQL instance automatically and do not use your real database.

In PowerShell:

```powershell
cd D:\MoneyMate\frontend
npm.cmd ci
npm.cmd test
npm.cmd start
```

Open `http://localhost:4200`. In **Connect your backend**, save `http://localhost:8080`. Production endpoints must use HTTPS. The sign-in form stays disabled until an API origin is configured.

## 2. Native PostgreSQL setup

Install PostgreSQL 17 using the [official Windows installer link](https://www.postgresql.org/download/windows/). Keep the database listening on localhost, port 5432. Remember the administrator password you set. Add its `bin` directory to this shell’s PATH:

```powershell
$env:Path = 'C:\Program Files\PostgreSQL\17\bin;' + $env:Path
psql -h localhost -U postgres -d postgres
```

At the `psql` prompt, create a non-superuser role and its database. `\password` securely prompts for your chosen database password:

```sql
CREATE ROLE moneymate LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE;
\password moneymate
CREATE DATABASE moneymate OWNER moneymate;
\q
```

Keep `pg_hba.conf` host authentication set to `scram-sha-256`. Do not publish PostgreSQL through ngrok or open port 5432 to other computers. Use an up-to-date supported PostgreSQL minor release. The automated suite has been run against native PostgreSQL 14.15 supplied by the test-only embedded runtime; a production PostgreSQL 17 installation has not been performed on this laptop.

## 3. Backend configuration and startup

```powershell
cd D:\MoneyMate
Copy-Item backend\.env.example backend\.env
notepad backend\.env
```

Replace `DB_PASSWORD` with the password chosen above. `.env` values are literal unquoted values, one per line. The start script reads this ignored file without executing it. It refuses the example password. Alternatively, set `DB_URL`, `DB_USER`, and `DB_PASSWORD` directly as process environment variables. Do not put them in frontend configuration or GitHub Pages variables.

```powershell
cd D:\MoneyMate\backend
.\mvnw.cmd -B verify
cd ..
.\scripts\start-backend.ps1
```

Flyway applies migrations on startup, before serving the API. Check:

```powershell
Invoke-RestMethod http://localhost:8080/api/health
```

To package and run the production jar with environment variables already set:

```powershell
cd D:\MoneyMate\backend
.\mvnw.cmd -B package
java -jar target\moneymate-1.0.0.jar
```

The backend binds to `127.0.0.1` by default. Its only required secret is the PostgreSQL password; bearer sessions are random and stored hashed, so there is no signing key to invent or hard-code. Passwords, tokens, database files, backups, and local environment files must remain outside Git.

## 4. HTTPS ngrok tunnel

Install ngrok using its [official setup guide](https://ngrok.com/docs/getting-started/), authenticate it to your own ngrok account, and keep its authtoken out of this repository. Start the backend, then in another terminal:

```powershell
ngrok http 8080 --inspect=false
```

`--inspect=false` disables ngrok’s local request inspection so financial payloads and authorization headers are not retained there. Use the **HTTPS** forwarding origin in the application’s Settings. Do not add `/api` or `/MoneyMate/` to it. For example, the input shape is `https://your-endpoint.ngrok.app`; the application adds `/api` itself.

If your account has an available stable endpoint, use the endpoint shown in your dashboard:

```powershell
ngrok http 8080 --url https://YOUR-ASSIGNED-ENDPOINT --inspect=false
```

Stable endpoint availability and terms depend on your ngrok account and plan; this project does not assume a particular free-plan entitlement. The tunnel provider terminates public HTTPS. The final hop to Spring is HTTP on laptop loopback.

Keep both the backend and tunnel running while invited users need remote sync. Laptop sleep, tunnel termination, or connectivity loss makes the API unavailable. Cached users can continue locally. New users/devices cannot register or download data while the server is offline.

## 5. CORS and public frontend API configuration

The supplied backend default is:

```text
CORS_ORIGINS=http://localhost:4200,https://sarvesh1barve.github.io
```

An origin contains **no repository path**. Restart Spring after changing this variable. Do not set wildcard origins. Requests use an Authorization header and `credentials: omit`, so blocked third-party cookies do not prevent authentication.

Users can set/replace the API origin in the app. Changing servers requires resolving pending operations and clears the current local workspace. For a default origin in a local build:

```powershell
cd D:\MoneyMate\frontend
node scripts/set-api.mjs https://YOUR-ASSIGNED-ENDPOINT
npm.cmd run build:pages
```

This changes only public `frontend/public/config.json`. Keep temporary tunnel origins out of commits unless deliberately sharing them. The endpoint is public information, not a credential. The configurable application name is the `appName` property in the same file; update manifest names too if deliberately renaming the installed app.

## 6. GitHub Pages

The workflow uploads **only** `frontend/dist/frontend/browser`; no backend, database, test credentials, or local data is published. Angular hash routing works without a server-side fallback; URLs look like:

`https://sarvesh1barve.github.io/MoneyMate/#/trips`

In the repository’s **Settings → Pages**, choose **GitHub Actions** as the build source. Optionally set repository **Actions variable** `MONEYMATE_API_URL` to the HTTPS ngrok origin. This is not a secret. With no variable, the app asks each user to configure the origin.

After reviewing and committing the implementation, push the feature branch:

```powershell
cd D:\MoneyMate
git push -u origin feat/self-hosted-multiuser
```

This repository initially has no `main` commit. Decide which reviewed commit becomes its initial default `main` branch in GitHub before relying on automatic main-branch deployment. Do not force-push. Once `main` exists, normal merges/pushes to `main` trigger Pages deployment; **Frontend to GitHub Pages → Run workflow** can also publish a selected reviewed branch after Pages/environment permissions permit it. Publishing is not part of the local test run.

Local production preview, including `/MoneyMate/`, manifest and service worker:

```powershell
cd D:\MoneyMate\frontend
npm.cmd run build:pages
node scripts/preview.mjs
```

Open `http://localhost:4200/MoneyMate/`. Installation is offered by supporting browsers after the shell is cached; service workers require localhost or HTTPS. On a shared device, sign out to clear financial caches. No local encryption is claimed.

## 7. Two-user walkthrough

1. With Spring, PostgreSQL, and (for remote users) ngrok running, open the frontend in two separate browser profiles.
2. Configure the same API origin. Register Alice and Bob using different emails and passwords of at least 12 characters. No email is sent.
3. Alice adds a personal expense. Sync Bob: Alice’s personal entry must not appear.
4. Alice creates a group and adds Bob as a named participant. This alone grants no access.
5. Alice chooses **Participants → Invite**, shares the single-use link/code manually, and Bob uses **Join a trip** after signing in.
6. Alice adds ₹100 paid by Alice, split equally. Bob adds ₹40 paid by Bob, split equally. Sync both: Bob owes Alice ₹30.
7. Stop Spring, add a ₹20 expense as Alice locally, and observe **Server unavailable** with the draft visible. Restart Spring and Sync Now: it appears exactly once for both; Bob now owes ₹40.
8. Record a settlement as Pending, explicitly mark Paid, then confirm receipt as Alice. Only confirmation clears the balance.
9. Refresh: cached entries persist. Sign in again to synchronize because credentials are memory-only. Remove Bob’s membership as owner and sync Bob; that trip disappears and writes are denied.

## 8. Automated browser verification

The test-only launcher starts a disposable PostgreSQL cluster and Spring at localhost:8080. It has no real financial data. It watches `.local/pause-api` to let the browser test close/restart the actual Spring context while preserving PostgreSQL. Never use this launcher as your production backend.

Terminal A:

```powershell
cd D:\MoneyMate\backend
.\mvnw.cmd test-compile org.codehaus.mojo:exec-maven-plugin:3.5.0:java '-Dexec.mainClass=com.moneymate.BrowserTestServer' '-Dexec.classpathScope=test'
```

Terminal B, after `/api/health` responds:

```powershell
cd D:\MoneyMate\frontend
npm.cmd ci
npx.cmd playwright install chromium
npm.cmd run build:pages
npm.cmd run test:e2e
```

The test serves the real production build under `/MoneyMate/`, opens isolated browser contexts, and checks private data, invitations, shared expenses, settlement totals, real backend interruption/restart, refresh persistence, mobile layout and the offline service-worker shell. See [verification results](docs/VERIFICATION.md) for the precise result and remaining deployment checks.

## 9. Backup and restore

Use PostgreSQL’s matching-version `pg_dump`/`pg_restore` tools. Keep backups outside the source checkout, on an encrypted drive with restricted access. They contain all users’ private data and password hashes. A backup is not proven until restored into an isolated database and checked.

```powershell
New-Item -ItemType Directory -Force D:\MoneyMateBackups
pg_dump -h localhost -U moneymate -d moneymate -Fc -f D:\MoneyMateBackups\moneymate.dump -W
```

This takes a transactionally consistent custom-format backup while normal operations continue. Choose a fresh dated filename each time; the example filename would be overwritten on subsequent runs. Use `-W` to prompt; do not put passwords on command lines. Automate retention only after choosing a backup location and policy.

Restore into a **new** database, never over the live one as a first step:

```powershell
createdb -h localhost -U postgres -O moneymate moneymate_restore -W
pg_restore -h localhost -U moneymate -d moneymate_restore --no-owner --no-privileges --exit-on-error D:\MoneyMateBackups\moneymate.dump -W
psql -h localhost -U moneymate -d moneymate_restore -W
```

In `psql`, invalidate restored sessions and check counts before testing with a separate Spring instance:

```sql
DELETE FROM auth_session;
SELECT kind, count(*) FROM record GROUP BY kind;
SELECT count(*) FROM app_user;
SELECT count(*) FROM membership;
\q
```

Stop the live backend before switching its `DB_URL` to the recovered database. Keep the original database until recovery is validated. A database restore can move server versions backward; keep browser pending drafts, review conflicts, and do not indiscriminately clear outboxes. The personal JSON export is a convenience, not a full multi-user backup. Import is intentionally not exposed.

## Limitations and operational expectations

This is a small laptop-hosted application, with 30-second refresh rather than instant realtime. Password recovery, email verification, automatic session renewal, recurring budgets, attachments, cross-currency conversions, and bank/payment integration are not implemented. Money supports one payer per expense; its stored payer-array model allows a later extension. Settlement suggestions reduce transfers but do not claim a globally minimal transfer count. Full snapshots and indefinitely retained tombstones/operation IDs favor safety for a small group over large-scale efficiency.

GitHub Pages activation/deployment, a real ngrok tunnel, native production database installation, and backup/restore commands require the operator’s accounts/configuration and are reported separately from local automated verification. No remote hosting or tunnel is claimed to be live just because local tests pass.
