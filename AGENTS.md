# MoneyMate development

Read README.md and docs/ARCHITECTURE.md before changing authorization or synchronization.

- Keep production data and credentials out of this repository and frontend assets.
- Work on feature branches; preserve history. The repository began empty.
- Preserve stable entity and operation UUIDs, integer minor units, local calendar dates, server authorization, idempotency, versions, and tombstones.
- Every shared mutation must lock the trip before reading the current version. A participant name does not grant access.
- Test meaningful finance/sync changes with `cd frontend && npm test` and `npm run build:pages`.
- Test backend/security changes with the Maven wrapper `cd backend && ./mvnw verify` (Windows: `mvnw.cmd`). Tests use disposable native PostgreSQL, not Docker.
- The browser test launcher and pause marker are test-only. They must never enter production configuration or the production jar.
- Frontend browser tests: follow README section 8. Report actual coverage; do not claim a live ngrok tunnel, Pages deployment, or production database installation without verifying it.
- Use the existing per-user IndexedDB cache and retained-draft conflict flow; never silently discard pending writes on refresh or overwrite a concurrent edit.
- Only the frontend build directory may be published to GitHub Pages.
