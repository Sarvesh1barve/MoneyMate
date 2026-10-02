import { Injectable, signal } from '@angular/core';
import { Cache } from './cache';
import { Body, Entry, Kind, Member, Pending, Snapshot, User } from './models';
import { validate } from './finance';
import { replay } from './sync-engine';
import { DeviceSignIn, passkeyCredential } from './device-signin';

export class RequestError extends Error {
  constructor(
    public status: number,
    message: string,
    public detail?: any,
  ) {
    super(message);
  }
}
@Injectable({ providedIn: 'root' })
export class Store {
  readonly cache = new Cache();
  readonly user = signal<User | null>(null);
  readonly entries = signal<Entry[]>([]);
  readonly pending = signal<Pending[]>([]);
  readonly members = signal<Member[]>([]);
  readonly activity = signal<Body[]>([]);
  readonly lastSync = signal('');
  readonly state = signal('Setting up');
  readonly busy = signal(false);
  readonly apiUrl = signal('');
  readonly appName = signal('MoneyMate');
  readonly authenticated = signal(false);
  readonly ready = signal(false);
  readonly remembered = signal(false);
  readonly authNotice = signal('');
  readonly devices = signal<any[]>([]);
  readonly passkeys = signal<any[]>([]);
  readonly incomingInvite = signal(sessionStorage.getItem('moneymate-invite') || '');
  readonly joining = signal(false);
  readonly deviceSignIn = new DeviceSignIn(this.cache, () => this.apiUrl());
  private token = '';
  private expiresAt = 0;
  private authEpoch = 0;
  private renewal?: Promise<boolean>;
  private channel = new BroadcastChannel('moneymate-session');
  constructor() {
    this.channel.onmessage = (event) => {
      if (event.data?.logout === this.user()?.id) {
        this.authEpoch++;
        this.token = '';
        this.remembered.set(false);
        this.authenticated.set(false);
        this.user.set(null);
        this.entries.set([]);
        this.pending.set([]);
        this.members.set([]);
        this.activity.set([]);
      }
    };
  }
  async init() {
    try {
      const config = await fetch('config.json', { cache: 'no-store' }).then((r) => r.json());
      this.appName.set(config.appName || 'MoneyMate');
      this.apiUrl.set(localStorage.getItem('moneymate-api') || config.apiUrl || '');
    } catch {
      this.apiUrl.set(localStorage.getItem('moneymate-api') || '');
    }
    this.user.set((await this.cache.meta<User>('lastUser')) || null);
    await this.load();
    this.state.set(this.user() ? 'Offline workspace · sign in to sync' : 'Sign in to begin');
    try {
      this.remembered.set(!!(await this.deviceSignIn.read()));
      if (this.remembered()) await this.restoreSession();
    } catch (e) {
      if (e instanceof RequestError && e.status === 0) this.state.set('Server unavailable');
    }
    this.ready.set(true);
    if (this.authenticated()) void this.sync();
    window.addEventListener('online', () => void this.sync());
    setInterval(() => {
      if (document.visibilityState === 'visible' && (this.token || this.remembered()))
        void this.sync();
    }, 30000);
  }
  async configure(value: string) {
    const url = new URL(value.trim());
    if (
      url.protocol !== 'https:' &&
      !(url.protocol === 'http:' && ['localhost', '127.0.0.1'].includes(url.hostname))
    )
      throw Error('Use HTTPS, or HTTP on localhost for development.');
    if (url.username || url.password || url.search || url.hash || url.pathname !== '/')
      throw Error('Enter the API origin only, such as https://your-endpoint.ngrok.app.');
    if (this.pending().length)
      throw Error('Sync or resolve pending changes before changing servers.');
    if (this.user()) await this.logout();
    this.apiUrl.set(url.origin);
    localStorage.setItem('moneymate-api', url.origin);
    this.state.set('Server endpoint saved');
  }
  async request(path: string, method = 'GET', body?: any, renew = true): Promise<any> {
    if (
      renew &&
      this.user() &&
      this.remembered() &&
      (!this.token || this.expiresAt < Date.now() + 30000)
    )
      await this.restoreSession();
    if (!this.apiUrl()) throw new RequestError(0, 'Set the backend HTTPS endpoint in Settings.');
    let response: Response;
    try {
      response = await fetch(`${this.apiUrl()}/api${path}`, {
        method,
        headers: {
          'Content-Type': 'application/json',
          'ngrok-skip-browser-warning': 'true',
          ...(this.token ? { Authorization: `Bearer ${this.token}` } : {}),
        },
        body: body === undefined ? undefined : JSON.stringify(body),
        credentials: 'omit',
        cache: 'no-store',
        signal: AbortSignal.timeout(10000),
      });
    } catch {
      throw new RequestError(
        0,
        'Server unavailable. Check the laptop and ngrok tunnel. Your changes are saved on this device.',
      );
    }
    const data = await response
      .json()
      .catch(() => ({ message: 'Unexpected server response. Check the API endpoint.' }));
    if (response.status === 401 && renew && this.remembered() && (await this.restoreSession()))
      return this.request(path, method, body, false);
    if (!response.ok)
      throw new RequestError(response.status, data.message || 'Request failed.', data.detail);
    return data;
  }
  async login(email: string, password: string, name: string, register: boolean, remember = false) {
    const session = await this.request(
      register ? '/auth/register' : '/auth/login',
      'POST',
      {
        email,
        password,
        name,
      },
      false,
    );
    await this.finishLogin(session, remember);
  }
  private async acceptSession(session: any) {
    this.token = session.token;
    this.expiresAt = Date.parse(session.expiresAt);
    this.authenticated.set(true);
    this.user.set(session.user);
    this.entries.set([]);
    this.pending.set([]);
    this.members.set([]);
    this.activity.set([]);
    await this.cache.setMeta('lastUser', session.user);
    await this.load();
  }
  private async finishLogin(session: any, remember: boolean) {
    this.authEpoch++;
    const oldDevice = await this.deviceSignIn.read();
    await this.deviceSignIn.forget();
    this.remembered.set(false);
    await this.acceptSession(session);
    if (oldDevice && oldDevice.userId === session.user.id) {
      const oldId = oldDevice.id;
      await this.request(`/auth/devices/${oldId}`, 'DELETE').catch(() => {});
    }
    this.authNotice.set('');
    if (remember) {
      try {
        await this.deviceSignIn.remember(
          (...args) => this.request(...args),
          session.user.id,
          'My browser',
        );
        this.remembered.set(true);
      } catch {
        this.authNotice.set(
          'Signed in for this session. Remembered sign-in could not be enabled on this browser.',
        );
      }
    }
    await this.sync();
  }
  async restoreSession(): Promise<boolean> {
    if (this.renewal) return this.renewal;
    const epoch = this.authEpoch;
    this.renewal = (async () => {
      try {
        const session = await this.deviceSignIn.restore(
          (p, m, b) => this.request(p, m, b, false),
          this.user()?.id,
        );
        if (epoch !== this.authEpoch) return false;
        if (!session) {
          this.remembered.set(false);
          return false;
        }
        await this.acceptSession(session);
        this.state.set('Connected');
        return true;
      } catch (e) {
        if (epoch === this.authEpoch && e instanceof RequestError && e.status === 401) {
          await this.deviceSignIn.forget();
          this.remembered.set(false);
          this.token = '';
          this.authenticated.set(false);
          this.state.set('Remembered sign-in expired · sign in again');
          return false;
        }
        throw e;
      } finally {
        this.renewal = undefined;
      }
    })();
    return this.renewal;
  }
  async passkeyLogin(remember: boolean) {
    const start = await this.request('/auth/passkey/options', 'POST', {}, false);
    const credential = await passkeyCredential(start.options, false);
    const session = await this.request(
      '/auth/passkey/verify',
      'POST',
      { challengeId: start.challengeId, credential },
      false,
    );
    await this.finishLogin(session, remember);
  }
  async addPasskey(name: string) {
    const start = await this.request('/auth/passkeys/options', 'POST', {});
    const credential = await passkeyCredential(start.options, true);
    await this.request('/auth/passkeys', 'POST', {
      challengeId: start.challengeId,
      credential,
      name: name || 'My passkey',
    });
    await this.loadSignInMethods();
  }
  async loadSignInMethods() {
    const [devices, passkeys] = await Promise.all([
      this.request('/auth/devices'),
      this.request('/auth/passkeys'),
    ]);
    this.devices.set(devices);
    this.passkeys.set(passkeys);
  }
  async removeSignInMethod(kind: 'devices' | 'passkeys', id: string) {
    await this.request(`/auth/${kind}/${encodeURIComponent(id)}`, 'DELETE');
    if (kind === 'devices' && (await this.deviceSignIn.read())?.id === id) {
      await this.deviceSignIn.forget();
      this.remembered.set(false);
      this.token = '';
      this.authenticated.set(false);
      this.state.set('Device sign-in removed · sign in again');
      this.devices.set(this.devices().filter((d) => d.id !== id));
      return;
    }
    await this.loadSignInMethods();
  }
  async logout() {
    const user = this.user();
    if (!user) return;
    this.authEpoch++;
    await navigator.locks.request(`moneymate-sync:${user.id}`, async () => {
      try {
        await this.request('/auth/logout', 'POST', {}, false);
      } catch {}
      await this.deviceSignIn.forget();
      this.remembered.set(false);
      this.devices.set([]);
      this.passkeys.set([]);
      this.token = '';
      this.authenticated.set(false);
      await this.cache.forget(user.id);
      this.channel.postMessage({ logout: user.id });
      this.user.set(null);
      this.entries.set([]);
      this.pending.set([]);
      this.members.set([]);
      this.activity.set([]);
      this.lastSync.set('');
      this.state.set('Signed out · local data cleared');
    });
  }
  async load() {
    const user = this.user();
    if (!user) return;
    const [entries, pending, members, activity, lastSync] = await Promise.all([
      this.cache.records(user.id),
      this.cache.pending(user.id),
      this.cache.meta<Member[]>(`members:${user.id}`),
      this.cache.meta<Body[]>(`activity:${user.id}`),
      this.cache.meta<string>(`lastSync:${user.id}`),
    ]);
    if (this.user()?.id !== user.id) return;
    this.entries.set(entries);
    this.pending.set(pending);
    this.members.set(members || []);
    this.activity.set(activity || []);
    this.lastSync.set(lastSync || '');
  }
  async save(
    kind: Kind,
    body: Body,
    tripId: string | null = null,
    existing?: Entry,
    startSync = true,
  ) {
    const user = this.user();
    if (!user) throw Error('Sign in before creating your workspace.');
    validate(kind, body);
    const entry: Entry = {
      id: existing?.id || crypto.randomUUID(),
      kind,
      ownerId: existing?.ownerId || user.id,
      tripId,
      version: existing?.version || 0,
      deleted: false,
      body: structuredClone(body),
      updatedAt: new Date().toISOString(),
    };
    await this.cache.save(user, entry);
    await this.load();
    this.state.set('Pending changes');
    if (startSync) void this.sync();
    return entry;
  }
  async remove(entry: Entry) {
    const user = this.user();
    if (!user) return;
    await this.cache.save(user, { ...entry, deleted: true });
    await this.load();
    this.state.set('Pending changes');
    void this.sync();
  }
  async sync() {
    if (this.busy() || !this.user()) return;
    if (!this.token && this.remembered()) {
      try {
        await this.restoreSession();
      } catch {
        this.state.set('Server unavailable');
        return;
      }
    }
    if (!this.token) {
      this.state.set(
        this.pending().length
          ? 'Pending changes · sign in to sync'
          : 'Offline workspace · sign in to sync',
      );
      return;
    }
    const user = this.user()!;
    this.busy.set(true);
    try {
      await navigator.locks.request(`moneymate-sync:${user.id}`, async () => {
        if (this.user()?.id !== user.id) return;
        await replay(
          this.cache,
          user.id,
          (op) => this.request('/sync', 'POST', op),
          () => this.user()?.id === user.id,
        );
        if (this.user()?.id !== user.id) return;
        const snapshot: Snapshot = await this.request('/sync');
        if (this.user()?.id !== user.id) return;
        await this.cache.reconcile(user.id, snapshot);
        await this.load();
        this.state.set(
          this.pending().some((p) => p.error)
            ? 'Conflict needs review'
            : this.pending().length
              ? 'Pending changes'
              : 'Synced',
        );
      });
    } catch (e) {
      if (e instanceof RequestError && e.status === 401) {
        this.token = '';
        this.authenticated.set(false);
        this.state.set('Session expired · sign in to sync');
      } else this.state.set('Server unavailable');
    } finally {
      await this.load();
      this.busy.set(false);
    }
  }
  async resolve(p: Pending, keepMine: boolean) {
    const user = this.user();
    if (!user) return;
    if (!this.authenticated()) throw Error('Sign in and refresh before resolving conflicts.');
    const snapshot: Snapshot = await this.request('/sync');
    const latest = snapshot.records.find((e) => e.id === p.operation.id);
    if (keepMine && (!latest || latest.deleted))
      throw Error(
        'This record is deleted or access was revoked. Copy your draft before discarding it.',
      );
    const local = this.entries().find((e) => e.id === p.operation.id);
    await this.cache.resolve(user.id, p.operation.id, latest);
    if (keepMine && local) await this.cache.save(user, { ...local, version: latest!.version });
    await this.load();
    await this.sync();
  }
  async join(token: string) {
    const result = await this.request('/invitations/join', 'POST', { token });
    await this.sync();
    return result.tripId as string;
  }
  async invite(trip: string, participant: string | null = null) {
    return this.request(`/trips/${trip}/invitations`, 'POST', { participantId: participant });
  }
  async revoke(trip: string, user: string) {
    await this.request(`/trips/${trip}/members/${user}`, 'DELETE');
    await this.sync();
  }
}
