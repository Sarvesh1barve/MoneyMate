# Backend API

All routes are under `/api`. JSON only. Authenticated routes require `Authorization: Bearer <memory-only-token>`. Every response is `Cache-Control: no-store`. Timestamps are UTC instants; transaction/expense dates are local calendar strings. UUIDs identify users, records, participants, trips, and operations.

| Method | Route | Behavior |
| --- | --- | --- |
| GET | `/health` | Public liveness |
| POST | `/auth/register` | `{email,password,name}`; returns token, user, expiry |
| POST | `/auth/login` | `{email,password}`; same session response |
| POST | `/auth/logout` | Revokes current bearer token |
| POST | `/auth/devices` | Authenticated recent sign-in; register P-256 device public key, maximum 10 per user |
| GET/DELETE | `/auth/devices`, `/auth/devices/{id}` | List or revoke only owned remembered devices |
| POST | `/auth/device/challenge`, `/auth/device/verify` | Public two-step signed challenge, one-time and origin-bound; returns 30-minute session |
| POST | `/auth/passkeys/options`, `/auth/passkeys` | Authenticated recent sign-in; start and finish WebAuthn registration |
| GET/DELETE | `/auth/passkeys`, `/auth/passkeys/{id}` | List or revoke only owned passkeys |
| POST | `/auth/passkey/options`, `/auth/passkey/verify` | Public two-step WebAuthn sign-in; returns 30-minute session |
| GET | `/me` | Current `{id,email,name}` |
| GET | `/sync` | Authorized snapshot: records, members, activity, server time |
| POST | `/sync` | Atomic idempotent versioned write |
| GET | `/trips/{trip}/suggestions` | Server-calculated transfers, members only |
| POST | `/trips/{trip}/invitations` | Owner only; `{participantId}` for a named participant or `{participantId:null}` for a new member → one-time token and expiry |
| POST | `/invitations/join` | Authenticated `{token}`; atomically links that account to the named participant or creates one, returning `tripId` |
| DELETE | `/trips/{trip}/members/{user}` | Owner only; revoke non-owner membership |

## Sync envelope

```json
{
  "operationId": "new UUID retained across all retries",
  "id": "stable entity UUID",
  "kind": "transaction",
  "tripId": null,
  "baseVersion": 0,
  "deleted": false,
  "body": {
    "type": "EXPENSE",
    "description": "Lunch",
    "amount": 12345,
    "currency": "INR",
    "date": "2026-10-01"
  }
}
```

`baseVersion: 0` creates; updates use the last accepted/expected version. The server derives owner ID. The response includes `id,kind,ownerId,tripId,version,deleted,body,updatedAt`. A new operation ID is required for a distinct write. A retry must preserve its exact payload. 409 version conflicts contain the permitted current record in `detail`. 403 indicates missing/revoked authorization. Network errors are distinct from 401 session expiry. The client keeps drafts for both.

## Bodies

- `account`: `name,currency,openingBalance` (signed integer minor units).
- `category`: `name,parentId?`; one optional parent.
- `transaction`: `type` (`EXPENSE/INCOME/TRANSFER`), `description,amount,currency,date,accountId?,toAccountId?,categoryId?,notes?`. Transfers require different owned accounts with matching currency.
- `budget`: `name,amount,currency,startDate,period` (`DAY/WEEK/MONTH/TRIP`), `categoryId?`, `budgetTripId?` (required for TRIP). The budget itself remains private; trip budget usage is total shared expense cost.
- `settings`: `theme` (`light/dark/system`), `currency`.
- `trip`: `name,currency,groupType,ownerParticipantId,ownerName`. The server creates the owner participant/membership in the same transaction. Trip and account currency are immutable.
- `participant`: `name`; envelope `tripId` required. Owner managed. Removal is denied while linked or referenced by active financial history.
- `expense`: `description,date,currency,amount,payerId,method,parts:[{participantId,value}],notes?`; envelope `tripId` required. Methods: `EQUAL`, `SELECTED`, `EXACT` (minor units), `PERCENT` (basis points totaling 10000), `SHARES` (nonnegative integer weights with positive total). Equal/selected use the listed participants. The server produces `allocations:[{participantId,amount}]` and `payers:[{participantId,amount}]`.
- `settlement`: `from,to,amount,currency,status`; envelope `tripId` required. Creation must be Pending and within current outstanding balances. Parties/amount are immutable. Mark Paid, then Confirmed; confirmation revalidates balances. Cancel rather than delete.

Soft delete by submitting the existing identity/scope with `deleted:true` and its expected version. The server retains the canonical body as a tombstone. Activity is server-generated, not writable by clients.
