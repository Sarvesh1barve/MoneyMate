# This laptop's deployed setup

Verified on 2026-10-02. The frontend is published at [https://sarvesh1barve.github.io/MoneyMate/](https://sarvesh1barve.github.io/MoneyMate/). Source is on the `feat/self-hosted-multiuser` branch, currently also the repository's default branch because the repository started empty. GitHub Pages serves only the Angular build.

## Running services

| Service | Local address | Persistent files |
| --- | --- | --- |
| PostgreSQL 17.11 | `127.0.0.1:5432` | `D:\MoneyMate\.local\postgres-data` |
| Spring Boot production jar | `127.0.0.1:8080` | `D:\MoneyMate\backend\target\moneymate-1.0.0.jar` |
| ngrok agent | `127.0.0.1:4040` (local status API) | Existing user ngrok configuration |

PostgreSQL binaries are in `D:\MoneyMate\.tools\postgresql-17.11\pgsql\bin`. This is a native local cluster, not a Windows service or a disposable test database. Database `moneymate` is owned by the restricted `moneymate` role. Both Flyway migrations have been applied. Production starts empty; test users/data have not been copied into it.

The ignored `backend\.env` holds database connection settings. The ignored `.local\postgres-admin.secret` holds the generated database administrator password. These files and the local data directory have restricted Windows permissions and must not be committed or shared. Credentials were generated randomly; the frontend receives only the public API origin. SCRAM-SHA-256 protects PostgreSQL password authentication. Database files and browser caches are not application-encrypted.

## Start or restart

From PowerShell on this already-configured laptop:

```powershell
cd D:\MoneyMate
.\scripts\start-local-stack.ps1 -WithTunnel
Invoke-RestMethod http://localhost:8080/api/health
```

The starter reads ignored `.local\runtime.json` and `backend\.env`, starts PostgreSQL if needed, and starts Java/ngrok as hidden background processes. An already-running MoneyMate backend is reused. Allow time for Spring startup before checking health. Successful health returns `status: up`. If a different application occupies port 8080, the script stops with an error.

The processes continue after closing this chat. They do **not** automatically start after reboot, and sleep or lost Internet connectivity interrupts remote access. Keep the laptop awake and connected when other users need synchronization. Review `.local\backend.log`, `.local\backend-error.log`, `.local\postgres.log`, and `.local\ngrok-error.log` if startup fails. Recorded Java/ngrok process IDs are in `.local\backend.pid` and `.local\ngrok.pid`.

To deliberately stop services later:

```powershell
cd D:\MoneyMate
.\scripts\stop-local-stack.ps1 -StopTunnel -StopDatabase
```

The stop script checks recorded process identities before stopping them. These services were left running at delivery. Do not launch the test-only browser backend on port 8080 while the production backend uses that port.

## Update an ngrok URL and deploy

The current HTTPS origin is stored in the GitHub repository Actions variable `MONEYMATE_API_URL`, not hard-coded in source. Inspect the running tunnel using:

```powershell
$tunnels = Invoke-RestMethod http://127.0.0.1:4040/api/tunnels
$tunnels.tunnels | Select-Object public_url, proto
```

An ephemeral origin can change when ngrok restarts. With the GitHub CLI authenticated to the repository, choose the HTTPS tunnel forwarding to port 8080 and update the public configuration:

```powershell
$apiOrigin = ($tunnels.tunnels | Where-Object {
    $_.proto -eq 'https' -and $_.config.addr -match ':8080/?$'
} | Select-Object -First 1).public_url
if (-not $apiOrigin) { throw 'No HTTPS tunnel forwarding to port 8080 was found.' }
gh variable set MONEYMATE_API_URL --repo Sarvesh1barve/MoneyMate --body $apiOrigin
gh workflow run pages.yml --repo Sarvesh1barve/MoneyMate --ref feat/self-hosted-multiuser
gh run list --repo Sarvesh1barve/MoneyMate --workflow pages.yml --limit 3
```

Wait for the workflow to succeed before reloading the frontend. Users who previously saved an explicit API override in Settings may need to update it manually. Review pending changes before changing the application's configured server; see the README's offline/cache limitations. No database passwords or backend configuration belong in Pages variables.

The backend permits `https://sarvesh1barve.github.io` and `http://localhost:4200` as CORS origins. The repository path is intentionally absent from CORS. ngrok request inspection is disabled. Verify the public endpoint without the ngrok browser interstitial:

```powershell
Invoke-RestMethod "$apiOrigin/api/health" -Headers @{ 'ngrok-skip-browser-warning' = 'true' }
```

Back up the database using the README's `pg_dump`/`pg_restore` procedure. Add the PostgreSQL bin path above to your shell PATH first. A successful health check is not a recovery test; a full restore has not yet been performed.
