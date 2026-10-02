import { DBSchema, IDBPDatabase, openDB } from 'idb';
import { Entry, Member, Operation, Pending, Snapshot, User } from './models';

interface CacheSchema extends DBSchema {
  records: {
    key: [string, string];
    value: { userId: string; id: string; entry: Entry };
    indexes: { user: string };
  };
  outbox: { key: [string, string]; value: Pending; indexes: { user: string } };
  meta: { key: string; value: any };
}
export class Cache {
  private db: Promise<IDBPDatabase<CacheSchema>>;
  constructor(name = 'moneymate-v1') {
    this.db = openDB<CacheSchema>(name, 1, {
      upgrade(db) {
        const r = db.createObjectStore('records', { keyPath: ['userId', 'id'] });
        r.createIndex('user', 'userId');
        const o = db.createObjectStore('outbox', { keyPath: ['userId', 'operationId'] });
        o.createIndex('user', 'userId');
        db.createObjectStore('meta');
      },
    });
  }
  async meta<T>(key: string): Promise<T | undefined> {
    return (await this.db).get('meta', key);
  }
  async setMeta(key: string, value: any) {
    return (await this.db).put('meta', value, key);
  }
  async records(user: string) {
    return (await (await this.db).getAllFromIndex('records', 'user', user)).map((r) => r.entry);
  }
  async pending(user: string) {
    return (await (await this.db).getAllFromIndex('outbox', 'user', user)).sort(
      (a, b) => a.sequence - b.sequence,
    );
  }
  async save(user: User, entry: Entry) {
    const db = await this.db,
      tx = db.transaction(['records', 'outbox', 'meta'], 'readwrite');
    const previous = await tx.objectStore('records').get([user.id, entry.id]);
    const baseVersion = previous?.entry.version || 0;
    const operation: Operation = {
      operationId: crypto.randomUUID(),
      id: entry.id,
      kind: entry.kind,
      tripId: entry.tripId,
      baseVersion,
      deleted: entry.deleted,
      body: entry.body,
    };
    const sequence = ((await tx.objectStore('meta').get('sequence')) || 0) + 1;
    await tx.objectStore('meta').put(sequence, 'sequence');
    const local = { ...entry, version: baseVersion + 1 };
    await tx.objectStore('records').put({ userId: user.id, id: entry.id, entry: local });
    await tx
      .objectStore('outbox')
      .put({ userId: user.id, operationId: operation.operationId, operation, sequence });
    if (entry.kind === 'trip' && !previous) {
      const participant: Entry = {
        id: entry.body['ownerParticipantId'],
        kind: 'participant',
        ownerId: user.id,
        tripId: entry.id,
        version: 1,
        deleted: false,
        body: { name: entry.body['ownerName'] },
        updatedAt: entry.updatedAt,
      };
      await tx
        .objectStore('records')
        .put({ userId: user.id, id: participant.id, entry: participant });
    }
    await tx.done;
  }
  async confirm(user: string, pending: Pending, server: Entry) {
    const tx = (await this.db).transaction(['outbox', 'records'], 'readwrite');
    await tx.objectStore('outbox').delete([user, pending.operationId]);
    const remaining = await tx.objectStore('outbox').index('user').getAll(user);
    if (!remaining.some((p) => p.operation.id === server.id))
      await tx.objectStore('records').put({ userId: user, id: server.id, entry: server });
    await tx.done;
  }
  async fail(p: Pending, error: string, server?: Entry) {
    await (await this.db).put('outbox', { ...p, error, server });
  }
  async reconcile(user: string, snapshot: Snapshot) {
    const tx = (await this.db).transaction(['records', 'outbox', 'meta'], 'readwrite');
    const pending = await tx.objectStore('outbox').index('user').getAll(user);
    const protectedIds = new Set(pending.map((p) => p.operation.id));
    const allowedTrips = new Set(
      snapshot.records.filter((e) => e.kind === 'trip').map((e) => e.id),
    );
    const pendingTrips = new Set(
      pending
        .filter((p) => p.operation.kind === 'trip' && p.operation.baseVersion === 0 && !p.error)
        .map((p) => p.operation.id),
    );
    for (const local of await tx.objectStore('records').index('user').getAll(user)) {
      const trip = local.entry.kind === 'trip' ? local.id : local.entry.tripId;
      if (trip && !allowedTrips.has(trip) && !pendingTrips.has(trip)) {
        await tx.objectStore('records').delete([user, local.id]);
        for (const p of pending.filter((p) => p.operation.id === local.id))
          await tx
            .objectStore('outbox')
            .put({
              ...p,
              error:
                'Trip access is unavailable. Your draft is retained for copying; it cannot be uploaded.',
            });
      } else if (!protectedIds.has(local.id) && !snapshot.records.some((e) => e.id === local.id))
        await tx.objectStore('records').delete([user, local.id]);
    }
    for (const entry of snapshot.records)
      if (!protectedIds.has(entry.id))
        await tx.objectStore('records').put({ userId: user, id: entry.id, entry });
    await tx.objectStore('meta').put(snapshot.members, `members:${user}`);
    await tx.objectStore('meta').put(snapshot.activity, `activity:${user}`);
    await tx.objectStore('meta').put(snapshot.serverTime, `lastSync:${user}`);
    await tx.done;
  }
  async resolve(user: string, id: string, server?: Entry) {
    const tx = (await this.db).transaction(['outbox', 'records'], 'readwrite');
    for (const p of await tx.objectStore('outbox').index('user').getAll(user))
      if (p.operation.id === id) await tx.objectStore('outbox').delete([user, p.operationId]);
    if (server) await tx.objectStore('records').put({ userId: user, id, entry: server });
    else await tx.objectStore('records').delete([user, id]);
    await tx.done;
  }
  async forget(user: string) {
    const tx = (await this.db).transaction(['records', 'outbox', 'meta'], 'readwrite');
    for (const r of await tx.objectStore('records').index('user').getAll(user))
      await tx.objectStore('records').delete([user, r.id]);
    for (const p of await tx.objectStore('outbox').index('user').getAll(user))
      await tx.objectStore('outbox').delete([user, p.operationId]);
    for (const key of ['members:', 'activity:', 'lastSync:'])
      await tx.objectStore('meta').delete(key + user);
    await tx.objectStore('meta').delete('lastUser');
    await tx.done;
  }
}
