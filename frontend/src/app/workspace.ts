import { Component, ElementRef, ViewChild, effect, inject, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { ActivatedRoute, RouterLink, RouterLinkActive } from '@angular/router';
import { Store } from './store';
import { Body, Entry, Kind, Pending, Transfer } from './models';
import * as finance from './finance';

@Component({
  selector: 'mm-workspace',
  imports: [CommonModule, FormsModule, RouterLink, RouterLinkActive],
  templateUrl: './workspace.html',
})
export class Workspace {
  readonly s = inject(Store);
  readonly route = inject(ActivatedRoute);
  readonly f = finance;
  readonly year = new Date().getFullYear();
  readonly page = signal('home');
  readonly notice = signal('');
  readonly formError = signal('');
  readonly selectedTrip = signal('');
  readonly tripTab = signal('overview');
  readonly saving = signal(false);
  readonly nav = [
    { id: 'home', label: 'Home', icon: '◈' },
    { id: 'transactions', label: 'Transactions', icon: '↔' },
    { id: 'accounts', label: 'Accounts', icon: '▣' },
    { id: 'budgets', label: 'Budgets', icon: '◴' },
    { id: 'trips', label: 'Trips', icon: '⌁' },
    { id: 'insights', label: 'Insights', icon: '▥' },
    { id: 'settings', label: 'Settings', icon: '⚙' },
  ];
  register = false;
  email = '';
  password = '';
  name = '';
  endpoint = '';
  search = '';
  filter = 'ALL';
  sort = 'newest';
  currencyFilter = '';
  categoryFilter = '';
  fromDate = '';
  toDate = '';
  joinCode = '';
  invitation = '';
  authOpen = false;
  draft: Body = {};
  editing?: Entry;
  kind: Kind = 'transaction';
  amountText = '';
  splitValues: Record<string, string> = {};
  selectedParts: Record<string, boolean> = {};
  @ViewChild('editor') editor!: ElementRef<HTMLDialogElement>;
  constructor() {
    this.route.data.subscribe((d) => this.page.set(d['page']));
    this.route.queryParamMap.subscribe((p) => {
      if (p.get('invite')) {
        this.joinCode = p.get('invite')!;
        this.notice.set('Sign in, then join this trip using the invitation below.');
      }
    });
    effect(() => {
      document.documentElement.dataset['theme'] = this.settings()?.body['theme'] || 'system';
      document.title = this.s.appName();
    });
  }
  rows(kind: string) {
    return finance.active(this.s.entries(), kind);
  }
  settings() {
    return this.rows('settings')[0];
  }
  currentTheme(): string {
    return this.settings()?.body['theme'] || 'system';
  }
  currency() {
    return this.settings()?.body['currency'] || 'INR';
  }
  amount(n: number, currency = this.currency()) {
    return finance.money(n, currency);
  }
  label(id: string) {
    return this.s.entries().find((e) => e.id === id)?.body['name'] || 'Unassigned';
  }
  transactions() {
    return this.rows('transaction')
      .filter(
        (e) =>
          (this.filter === 'ALL' || e.body['type'] === this.filter) &&
          (!this.currencyFilter || e.body['currency'] === this.currencyFilter) &&
          (!this.categoryFilter || e.body['categoryId'] === this.categoryFilter) &&
          (!this.fromDate || e.body['date'] >= this.fromDate) &&
          (!this.toDate || e.body['date'] <= this.toDate) &&
          `${e.body['description']} ${e.body['notes'] || ''} ${this.label(e.body['categoryId'])}`
            .toLowerCase()
            .includes(this.search.toLowerCase()),
      )
      .sort((a, b) =>
        this.sort === 'amount'
          ? b.body['amount'] - a.body['amount']
          : this.sort === 'oldest'
            ? a.body['date'].localeCompare(b.body['date'])
            : b.body['date'].localeCompare(a.body['date']),
      );
  }
  totals() {
    return finance.totals(this.s.entries(), this.currency());
  }
  totalBalance() {
    return this.rows('account')
      .filter((a) => a.body['currency'] === this.currency())
      .reduce((s, a) => s + finance.balance(a, this.s.entries()), 0);
  }
  upcoming() {
    return this.rows('transaction')
      .filter((e) => e.body['date'] > finance.today())
      .sort((a, b) => a.body['date'].localeCompare(b.body['date']))
      .slice(0, 5);
  }
  spending() {
    const sums = new Map<string, number>();
    for (const e of this.rows('transaction').filter(
      (e) =>
        e.body['type'] === 'EXPENSE' &&
        e.body['currency'] === this.currency() &&
        e.body['date'] <= finance.today(),
    )) {
      const name = this.label(e.body['categoryId']);
      sums.set(name, (sums.get(name) || 0) + e.body['amount']);
    }
    const max = Math.max(1, ...sums.values());
    return [...sums]
      .sort((a, b) => b[1] - a[1])
      .map(([name, amount]) => ({ name, amount, width: (amount / max) * 100 }));
  }
  trip() {
    return this.rows('trip').find((e) => e.id === this.selectedTrip()) || this.rows('trip')[0];
  }
  tripRows(kind: string) {
    return this.rows(kind).filter((e) => e.tripId === this.trip()?.id);
  }
  tripTotal() {
    return this.tripRows('expense').reduce((s, e) => s + e.body['amount'], 0);
  }
  tripCurrency() {
    return this.trip()?.body['currency'] || this.currency();
  }
  balances() {
    return this.trip() ? finance.tripBalances(this.s.entries(), this.trip()!.id) : [];
  }
  suggestions() {
    return finance.settlements(this.balances());
  }
  owner() {
    return this.trip()?.ownerId === this.s.user()?.id;
  }
  member(participant: string) {
    return this.s
      .members()
      .find((m) => m.tripId === this.trip()?.id && m.participantId === participant);
  }
  pending(id: string) {
    return this.s.pending().some((p) => p.operation.id === id);
  }
  conflicts() {
    return this.s
      .pending()
      .filter((p) => p.error)
      .filter((p, i, all) => all.findIndex((x) => x.operation.id === p.operation.id) === i);
  }
  progress(b: Body) {
    return Math.min(
      100,
      Math.round((finance.budgetSpent(b, this.s.entries()) / b['amount']) * 100),
    );
  }
  async action(fn: () => Promise<unknown>, success = '') {
    this.notice.set('');
    try {
      await fn();
      if (success) this.notice.set(success);
    } catch (e) {
      this.notice.set(e instanceof Error ? e.message : 'The action could not be completed.');
    }
  }
  async authenticate() {
    this.saving.set(true);
    await this.action(async () => {
      await this.s.login(this.email, this.password, this.name, this.register);
      this.password = '';
      this.authOpen = false;
    }, 'Your workspace is ready.');
    this.saving.set(false);
  }
  async logout() {
    if (
      !confirm(
        this.s.pending().length
          ? 'You have pending changes. Signing out deletes this device’s cache and unsynced changes. Continue?'
          : 'Sign out and clear this device’s cached financial data?',
      )
    )
      return;
    await this.action(() => this.s.logout());
  }
  open(kind: Kind, entry?: Entry, duplicate = false) {
    this.kind = kind;
    this.editing = duplicate ? undefined : entry;
    this.formError.set('');
    this.draft = entry
      ? structuredClone(entry.body)
      : {
          currency: this.currency(),
          date: finance.today(),
          startDate: finance.today(),
          type: 'EXPENSE',
          period: 'MONTH',
          method: 'EQUAL',
          groupType: 'Trip',
          openingBalance: 0,
        };
    this.amountText = entry
      ? finance.major(
          entry.body[kind === 'account' ? 'openingBalance' : 'amount'] || 0,
          entry.body['currency'] || this.currency(),
        )
      : '';
    if (kind === 'expense' || kind === 'settlement') this.draft['currency'] = this.tripCurrency();
    if (kind === 'expense') {
      this.draft['payerId'] ||= this.tripRows('participant')[0]?.id;
      this.selectedParts = {};
      this.splitValues = {};
      for (const p of this.tripRows('participant')) {
        const old = this.draft['parts']?.find((x: any) => x.participantId === p.id);
        this.selectedParts[p.id] = entry ? !!old : true;
        this.splitValues[p.id] = old
          ? this.draft['method'] === 'EXACT'
            ? finance.major(old.value, this.tripCurrency())
            : this.draft['method'] === 'PERCENT'
              ? (old.value / 100).toString()
              : old.value.toString()
          : '1';
      }
    }
    this.editor.nativeElement.showModal();
  }
  close() {
    this.editor.nativeElement.close();
  }
  async submit() {
    this.formError.set('');
    this.saving.set(true);
    try {
      const b = structuredClone(this.draft);
      if (['transaction', 'expense', 'budget'].includes(this.kind))
        b['amount'] = finance.minor(this.amountText, b['currency']);
      if (this.kind === 'account')
        b['openingBalance'] = finance.minor(this.amountText || '0', b['currency']);
      if (this.kind === 'trip' && !this.editing) {
        b['ownerParticipantId'] = crypto.randomUUID();
        b['ownerName'] = this.s.user()!.name;
      }
      if (this.kind === 'expense')
        b['parts'] = this.tripRows('participant')
          .filter((p) => this.selectedParts[p.id])
          .map((p) => ({
            participantId: p.id,
            value: ['EQUAL', 'SELECTED'].includes(b['method'])
              ? 1
              : b['method'] === 'EXACT'
                ? finance.minor(this.splitValues[p.id], b['currency'])
                : b['method'] === 'PERCENT'
                  ? finance.minor(this.splitValues[p.id], 'INR')
                  : Number(this.splitValues[p.id]),
          }));
      const entry = await this.s.save(
        this.kind,
        b,
        ['expense', 'participant', 'settlement'].includes(this.kind) ? this.trip()!.id : null,
        this.editing,
      );
      if (this.kind === 'trip') this.selectedTrip.set(entry.id);
      this.close();
      this.notice.set('Saved on this device. Changes synchronize when the server is reachable.');
    } catch (e) {
      this.formError.set((e as Error).message);
    } finally {
      this.saving.set(false);
    }
  }
  remove(entry: Entry) {
    if (confirm(`Delete this ${entry.kind}? Its deletion will synchronize to the server.`))
      void this.action(() => this.s.remove(entry), 'Deleted.');
  }
  async share(text: string) {
    if (navigator.share) {
      try {
        await navigator.share({ title: this.s.appName(), text });
        return;
      } catch (e) {
        if ((e as Error).name === 'AbortError') return;
      }
    }
    await navigator.clipboard.writeText(text);
    this.notice.set('Copied to clipboard.');
  }
  async invite(participant: string) {
    await this.action(async () => {
      await this.s.sync();
      const result = await this.s.invite(this.trip()!.id, participant);
      this.invitation = `${location.origin}${location.pathname}#/trips?invite=${result.token}`;
    }, 'Invitation is valid for 24 hours and can be used once.');
  }
  async join() {
    await this.action(async () => {
      let code = this.joinCode.trim();
      if (code.includes('invite=')) code = code.split('invite=')[1].split('&')[0];
      await this.s.join(code);
      this.joinCode = '';
    }, 'Joined the shared trip.');
  }
  async revoke(user: string) {
    if (
      confirm(
        'Remove this member’s access? Their named participant and history remain. Previously downloaded data cannot be remotely erased from an offline device.',
      )
    )
      await this.action(() => this.s.revoke(this.trip()!.id, user), 'Member access revoked.');
  }
  async recordSettlement(t: Transfer) {
    await this.action(
      () =>
        this.s.save(
          'settlement',
          { ...t, currency: this.tripCurrency(), status: 'Pending' },
          this.trip()!.id,
        ),
      'Settlement recorded as Pending.',
    );
  }
  async settlementStatus(e: Entry, status: string) {
    await this.action(
      () => this.s.save('settlement', { ...e.body, status }, e.tripId, e),
      `Settlement saved as ${status}.`,
    );
  }
  canStatus(e: Entry, status: string) {
    const member = this.s
      .members()
      .find((m) => m.tripId === e.tripId && m.userId === this.s.user()?.id);
    return this.owner() || member?.participantId === e.body[status === 'Confirmed' ? 'to' : 'from'];
  }
  settlementExists(t: Transfer) {
    return this.tripRows('settlement').some(
      (e) =>
        ['Pending', 'Paid'].includes(e.body['status']) &&
        e.body['from'] === t.from &&
        e.body['to'] === t.to,
    );
  }
  summary() {
    const text = `${this.trip()!.body['name']} · ${this.s.appName()}\n${this.balances()
      .map(
        (b) =>
          `${this.label(b.id)}: paid ${this.amount(b.paid, this.tripCurrency())}, share ${this.amount(b.owed, this.tripCurrency())}, net ${this.amount(b.net, this.tripCurrency())}`,
      )
      .join('\n')}\nSuggested settlements:\n${this.suggestions()
      .map(
        (t) =>
          `${this.label(t.from)} → ${this.label(t.to)}: ${this.amount(t.amount, this.tripCurrency())}`,
      )
      .join('\n')}\nThis summary does not grant access to the trip.`;
    void this.action(() => this.share(text));
  }
  async saveSettings(theme: string, currency: string) {
    await this.action(() => this.s.save('settings', { theme, currency }, null, this.settings()));
  }
  configure() {
    void this.action(() => this.s.configure(this.endpoint), 'Server endpoint saved.');
  }
  shareInvitation() {
    void this.action(() => this.share(this.invitation));
  }
  copyDraft(p: Pending) {
    void this.action(() => this.share(JSON.stringify(p.operation.body, null, 2)));
  }
  async resolve(p: Pending, keep: boolean) {
    if (
      keep &&
      !confirm(
        'Apply your draft over the latest server version? Review both versions before continuing.',
      )
    )
      return;
    await this.action(() => this.s.resolve(p, keep));
  }
  export() {
    const blob = new Blob(
      [
        JSON.stringify(
          {
            exportedAt: new Date().toISOString(),
            user: this.s.user()?.email,
            personal: this.s.entries().filter((e) => !e.tripId && e.kind !== 'trip'),
            pending: this.s
              .pending()
              .filter((p) => !p.operation.tripId && p.operation.kind !== 'trip'),
          },
          null,
          2,
        ),
      ],
      { type: 'application/json' },
    );
    const url = URL.createObjectURL(blob),
      a = document.createElement('a');
    a.href = url;
    a.download = `MoneyMate-personal-${finance.today()}.json`;
    a.click();
    URL.revokeObjectURL(url);
  }
}
