import { Cache } from './cache';
import { Entry, Operation } from './models';

/** Sequential replay preserves dependent edits; uncertain delivery always retries the same ID. */
export async function replay(
  cache: Cache,
  userId: string,
  send: (op: Operation) => Promise<Entry>,
  current = () => true,
) {
  const blocked = new Set<string>();
  for (const pending of await cache.pending(userId)) {
    if (!current()) return;
    if (pending.error || blocked.has(pending.operation.id)) {
      blocked.add(pending.operation.id);
      continue;
    }
    try {
      await cache.confirm(userId, pending, await send(pending.operation));
    } catch (error) {
      const e = error as { status?: number; message: string; detail?: Entry };
      if (e.status && [400, 403, 409].includes(e.status)) {
        await cache.fail(pending, e.message, e.detail?.id ? e.detail : undefined);
        blocked.add(pending.operation.id);
      } else throw error;
    }
  }
}
