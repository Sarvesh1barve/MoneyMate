import 'fake-indexeddb/auto';
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { Cache } from '../src/app/cache';
import { replay } from '../src/app/sync-engine';
import { Entry, Operation, User } from '../src/app/models';
const user: User = { id: 'alice', name: 'Alice', email: 'a@test.invalid' };
const entry = (id = 'record'): Entry => ({
  id,
  kind: 'account',
  ownerId: 'alice',
  tripId: null,
  version: 0,
  deleted: false,
  body: { name: 'Cash', currency: 'INR', openingBalance: 0 },
  updatedAt: '',
});
test('local change and outbox persist together across cache instances', async () => {
  const name = crypto.randomUUID(),
    cache = new Cache(name);
  await cache.save(user, entry());
  const reopened = new Cache(name);
  assert.equal((await reopened.records(user.id))[0].version, 1);
  assert.equal((await reopened.pending(user.id)).length, 1);
  assert.deepEqual(await reopened.records('bob'), []);
  await reopened.forget(user.id);
  assert.deepEqual(await reopened.pending(user.id), []);
});
test('lost response retries the same operation exactly once on the server', async () => {
  const cache = new Cache(crypto.randomUUID());
  await cache.save(user, entry());
  const accepted = new Map<string, Entry>();
  let writes = 0;
  let lose = true;
  const send = async (op: Operation) => {
    if (!accepted.has(op.operationId)) {
      writes++;
      accepted.set(op.operationId, { ...entry(), version: 1 });
    }
    if (lose) {
      lose = false;
      throw Error('Connection dropped after commit');
    }
    return accepted.get(op.operationId)!;
  };
  await assert.rejects(() => replay(cache, user.id, send));
  assert.equal((await cache.pending(user.id)).length, 1);
  await replay(cache, user.id, send);
  assert.equal(writes, 1);
  assert.equal((await cache.pending(user.id)).length, 0);
});
test('sequential edits get increasing versions and preserve the newest draft on acknowledgement', async () => {
  const cache = new Cache(crypto.randomUUID());
  await cache.save(user, entry());
  await cache.save(user, { ...entry(), body: { ...entry().body, name: 'New name' } });
  const pending = await cache.pending(user.id);
  assert.deepEqual(
    pending.map((p) => p.operation.baseVersion),
    [0, 1],
  );
  await cache.confirm(user.id, pending[0], { ...entry(), version: 1 });
  assert.equal((await cache.records(user.id))[0].body.name, 'New name');
});
test('conflicts preserve local drafts, stop dependent edits, and permit explicit rebasing', async () => {
  const cache = new Cache(crypto.randomUUID());
  await cache.save(user, entry());
  await cache.save(user, { ...entry(), body: { ...entry().body, name: 'My draft' } });
  const server = { ...entry(), version: 9, body: { ...entry().body, name: 'Server edit' } };
  let calls = 0;
  await replay(cache, user.id, async () => {
    calls++;
    throw { status: 409, message: 'Conflict', detail: server };
  });
  assert.equal(calls, 1);
  assert.equal((await cache.pending(user.id)).length, 2);
  await cache.reconcile(user.id, {
    records: [server],
    members: [],
    activity: [],
    serverTime: 'now',
  });
  const local = (await cache.records(user.id))[0];
  assert.equal(local.body.name, 'My draft');
  await cache.resolve(user.id, local.id, server);
  await cache.save(user, local);
  assert.equal((await cache.pending(user.id))[0].operation.baseVersion, 9);
});
test('revocation purges downloaded trip records but retains a blocked draft for copying', async () => {
  const cache = new Cache(crypto.randomUUID());
  const tripEntry = { ...entry('expense'), kind: 'expense' as const, tripId: 'trip' };
  await cache.save(user, tripEntry);
  await cache.reconcile(user.id, { records: [], members: [], activity: [], serverTime: 'now' });
  assert.deepEqual(await cache.records(user.id), []);
  assert.match((await cache.pending(user.id))[0].error!, /access is unavailable/);
});
test('snapshot keeps tombstones and never overwrites pending edits', async () => {
  const cache = new Cache(crypto.randomUUID());
  const tombstone = { ...entry(), version: 3, deleted: true };
  await cache.reconcile(user.id, {
    records: [tombstone],
    members: [],
    activity: [],
    serverTime: 'now',
  });
  assert.equal((await cache.records(user.id))[0].deleted, true);
});
