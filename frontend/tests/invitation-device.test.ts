import 'fake-indexeddb/auto';
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { webcrypto } from 'node:crypto';
import { Cache } from '../src/app/cache';
import { DeviceSignIn, decode, encode } from '../src/app/device-signin';
import { invitationLink, invitationToken } from '../src/app/invitations';

test('invitation links keep the /MoneyMate/ path and reject malformed codes', () => {
  const token = 'A'.repeat(43);
  const link = invitationLink(token, {
    origin: 'https://sarvesh1barve.github.io',
    pathname: '/MoneyMate/',
  });
  assert.equal(link, `https://sarvesh1barve.github.io/MoneyMate/#/trips?invite=${token}`);
  assert.equal(invitationToken(link), token);
  assert.throws(() => invitationToken('https://attacker.example/#/trips?invite=short'));
});

test('remembered device signs a challenge without exposing a persistent bearer token', async () => {
  Object.defineProperty(globalThis, 'crypto', { value: webcrypto, configurable: true });
  const database = crypto.randomUUID();
  const cache = new Cache(database);
  const flow = new DeviceSignIn(cache, () => 'https://backend.example');
  let publicKey = '';
  let id = '';
  const api = async (path: string, _method?: string, body?: any): Promise<any> => {
    if (path === '/auth/devices') {
      id = body.id;
      publicKey = body.publicKey;
      return { expiresAt: new Date(Date.now() + 86400000).toISOString() };
    }
    if (path === '/auth/device/challenge')
      return {
        challengeId: 'one',
        challenge: `MoneyMate device sign-in\n${body.deviceId}\nhttps://frontend.example\nnonce`,
      };
    if (path === '/auth/device/verify') {
      const key = await crypto.subtle.importKey(
        'spki',
        decode(publicKey),
        { name: 'ECDSA', namedCurve: 'P-256' },
        false,
        ['verify'],
      );
      const valid = await crypto.subtle.verify(
        { name: 'ECDSA', hash: 'SHA-256' },
        key,
        decode(body.signature),
        new TextEncoder().encode(
          `MoneyMate device sign-in\n${id}\nhttps://frontend.example\nnonce`,
        ),
      );
      assert.equal(valid, true);
      return { token: 'short-lived', user: { id: 'alice' } };
    }
    throw Error('Unexpected endpoint');
  };
  await flow.remember(api, 'alice', 'Test browser');
  const reopened = new DeviceSignIn(new Cache(database), () => 'https://backend.example');
  // The stored key is non-extractable and remains scoped to this backend origin.
  assert.equal((await flow.read())?.privateKey.extractable, false);
  assert.equal((await reopened.restore(api, 'alice'))?.user.id, 'alice');
  assert.equal(await flow.restore(api, 'bob'), null);
  await flow.forget();
  assert.equal(await flow.read(), undefined);
  assert.equal(encode(decode(publicKey)), publicKey);
});
