import { Cache } from './cache';

export type Api = (path: string, method?: string, body?: any) => Promise<any>;
export interface RememberedDevice {
  id: string;
  userId: string;
  privateKey: CryptoKey;
  expiresAt: string;
}
export function encode(value: ArrayBuffer): string {
  return btoa(String.fromCharCode(...new Uint8Array(value)))
    .replace(/\+/g, '-')
    .replace(/\//g, '_')
    .replace(/=+$/, '');
}
export function decode(value: string): ArrayBuffer {
  return Uint8Array.from(atob(value.replace(/-/g, '+').replace(/_/g, '/')), (c) => c.charCodeAt(0))
    .buffer;
}
export class DeviceSignIn {
  constructor(
    private cache: Cache,
    private endpoint: () => string,
  ) {}
  private key() {
    return `device:${this.endpoint()}`;
  }
  read() {
    return this.cache.meta<RememberedDevice>(this.key());
  }
  forget() {
    return this.cache.deleteMeta(this.key());
  }
  async remember(api: Api, userId: string, name: string) {
    const pair = await crypto.subtle.generateKey({ name: 'ECDSA', namedCurve: 'P-256' }, false, [
      'sign',
      'verify',
    ]);
    const id = crypto.randomUUID();
    const publicKey = encode(await crypto.subtle.exportKey('spki', pair.publicKey));
    const result = await api('/auth/devices', 'POST', { id, publicKey, name });
    const device: RememberedDevice = {
      id,
      userId,
      privateKey: pair.privateKey,
      expiresAt: result.expiresAt,
    };
    try {
      await this.cache.setMeta(this.key(), device);
    } catch (error) {
      await api(`/auth/devices/${id}`, 'DELETE').catch(() => {});
      throw error;
    }
    return device;
  }
  async restore(api: Api, expectedUser?: string) {
    const device = await this.read();
    if (!device || (expectedUser && expectedUser !== device.userId)) return null;
    if (Date.parse(device.expiresAt) <= Date.now()) {
      await this.forget();
      return null;
    }
    const challenge = await api('/auth/device/challenge', 'POST', { deviceId: device.id });
    const signature = encode(
      await crypto.subtle.sign(
        { name: 'ECDSA', hash: 'SHA-256' },
        device.privateKey,
        new TextEncoder().encode(challenge.challenge),
      ),
    );
    const session = await api('/auth/device/verify', 'POST', {
      deviceId: device.id,
      challengeId: challenge.challengeId,
      signature,
    });
    if (session.user.id !== device.userId)
      throw Error('Remembered account did not match. Please sign in again.');
    return session;
  }
}

// Explicit binary conversion also supports Safari versions predating WebAuthn JSON helpers.
export async function passkeyCredential(options: any, create: boolean) {
  const publicKey = { ...options.publicKey, challenge: decode(options.publicKey.challenge) };
  if (create) {
    publicKey.user = { ...publicKey.user, id: decode(publicKey.user.id) };
    publicKey.excludeCredentials = (publicKey.excludeCredentials || []).map((c: any) => ({
      ...c,
      id: decode(c.id),
    }));
  } else if (publicKey.allowCredentials) {
    publicKey.allowCredentials = publicKey.allowCredentials.map((c: any) => ({
      ...c,
      id: decode(c.id),
    }));
  }
  const credential = (await (create
    ? navigator.credentials.create({ publicKey })
    : navigator.credentials.get({ publicKey }))) as PublicKeyCredential | null;
  if (!credential) throw Error('No passkey was selected. You can use your password instead.');
  const response = credential.response;
  const encoded: any = { clientDataJSON: encode(response.clientDataJSON) };
  if (create) {
    const registration = response as AuthenticatorAttestationResponse;
    encoded.attestationObject = encode(registration.attestationObject);
    encoded.transports = registration.getTransports?.() || [];
  } else {
    const assertion = response as AuthenticatorAssertionResponse;
    encoded.authenticatorData = encode(assertion.authenticatorData);
    encoded.signature = encode(assertion.signature);
    encoded.userHandle = assertion.userHandle ? encode(assertion.userHandle) : null;
  }
  return {
    id: credential.id,
    rawId: encode(credential.rawId),
    type: credential.type,
    response: encoded,
    clientExtensionResults: credential.getClientExtensionResults(),
  };
}
