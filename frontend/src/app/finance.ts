import { Allocation, Body, Entry, Part, Transfer } from './models';

export const MAX_MONEY = 9_000_000_000_000;
export function digits(currency = 'INR'): number {
  return (
    new Intl.NumberFormat('en-IN', { style: 'currency', currency }).resolvedOptions()
      .maximumFractionDigits ?? 2
  );
}
export function minor(input: string, currency = 'INR'): number {
  const precision = digits(currency);
  const clean = String(input).trim();
  if (
    !new RegExp(`^-?\\d+(?:\\.\\d{1,${precision || 1}})?$`).test(clean) ||
    (precision === 0 && clean.includes('.'))
  )
    throw Error(`Enter a valid amount with up to ${precision} decimal places.`);
  const negative = clean.startsWith('-'),
    [whole, fraction = ''] = clean.replace('-', '').split('.');
  const value = Number(whole) * 10 ** precision + Number(fraction.padEnd(precision, '0'));
  if (!Number.isSafeInteger(value) || value > MAX_MONEY)
    throw Error('Amount is outside the supported range.');
  return negative ? -value : value;
}
export function major(amount: number, currency = 'INR'): string {
  return (amount / 10 ** digits(currency)).toFixed(digits(currency));
}
export function money(amount: number, currency = 'INR'): string {
  return new Intl.NumberFormat('en-IN', { style: 'currency', currency }).format(
    amount / 10 ** digits(currency),
  );
}
export function today(): string {
  const d = new Date();
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`;
}
export function validDate(value: string): boolean {
  if (!/^\d{4}-\d{2}-\d{2}$/.test(value)) return false;
  const [y, m, d] = value.split('-').map(Number);
  const date = new Date(y, m - 1, d, 12);
  return date.getFullYear() === y && date.getMonth() === m - 1 && date.getDate() === d;
}
export function split(amount: number, method: string, input: Part[]): Allocation[] {
  if (!Number.isSafeInteger(amount) || amount <= 0 || amount > MAX_MONEY)
    throw Error('Enter a positive expense amount.');
  if (
    !input.length ||
    input.length > 100 ||
    new Set(input.map((p) => p.participantId)).size !== input.length
  )
    throw Error('Select unique participants.');
  const parts = [...input].sort((a, b) => a.participantId.localeCompare(b.participantId));
  if (parts.some((p) => !Number.isSafeInteger(p.value) || p.value < 0 || p.value > MAX_MONEY))
    throw Error('Invalid split value.');
  if (method === 'EXACT') {
    if (parts.reduce((s, p) => s + p.value, 0) !== amount)
      throw Error('Exact splits must equal the expense amount.');
    return parts.map((p) => ({ participantId: p.participantId, amount: p.value }));
  }
  if (!['EQUAL', 'SELECTED', 'PERCENT', 'SHARES'].includes(method))
    throw Error('Unknown split method.');
  const equal = ['EQUAL', 'SELECTED'].includes(method);
  const weights = parts.map((p) => (equal ? 1 : p.value));
  const total = weights.reduce((s, n) => s + n, 0);
  if (
    !total ||
    (!equal && weights.some((n) => n > 1_000_000)) ||
    (method === 'PERCENT' && total !== 10000)
  )
    throw Error('Percentages must total 100%; shares must be positive in total.');
  const result = parts.map((p, i) => ({
    participantId: p.participantId,
    amount: Number((BigInt(amount) * BigInt(weights[i])) / BigInt(total)),
    remainder: (BigInt(amount) * BigInt(weights[i])) % BigInt(total),
  }));
  const order = [...result].sort((a, b) =>
    a.remainder === b.remainder
      ? a.participantId.localeCompare(b.participantId)
      : a.remainder > b.remainder
        ? -1
        : 1,
  );
  const leftover = amount - result.reduce((sum, p) => sum + p.amount, 0);
  for (let i = 0; i < leftover; i++) order[i].amount++;
  return result.map(({ participantId, amount }) => ({ participantId, amount }));
}
export function active(entries: Entry[], kind: string): Entry[] {
  return entries.filter((e) => e.kind === kind && !e.deleted);
}
export function balance(account: Entry, entries: Entry[], date = today()): number {
  return active(entries, 'transaction')
    .filter((t) => t.body['currency'] === account.body['currency'] && t.body['date'] <= date)
    .reduce((sum, t) => {
      const b = t.body;
      if (b['type'] === 'TRANSFER')
        return (
          sum +
          (b['toAccountId'] === account.id ? b['amount'] : 0) -
          (b['accountId'] === account.id ? b['amount'] : 0)
        );
      return (
        sum +
        (b['accountId'] === account.id ? (b['type'] === 'INCOME' ? b['amount'] : -b['amount']) : 0)
      );
    }, account.body['openingBalance']);
}
export function totals(entries: Entry[], currency = 'INR', start = '0000-01-01', end = today()) {
  return active(entries, 'transaction')
    .filter(
      (e) => e.body['currency'] === currency && e.body['date'] >= start && e.body['date'] <= end,
    )
    .reduce(
      (t, e) => {
        if (e.body['type'] === 'INCOME') t.income += e.body['amount'];
        if (e.body['type'] === 'EXPENSE') t.spending += e.body['amount'];
        return t;
      },
      { income: 0, spending: 0 },
    );
}
export function budgetEnd(b: Body): string {
  const [y, m, d] = b['startDate'].split('-').map(Number);
  const end = new Date(y, m - 1, d, 12);
  if (b['period'] === 'MONTH') {
    const lastDayOfNextMonth = new Date(y, m + 1, 0, 12).getDate();
    end.setDate(1);
    end.setMonth(end.getMonth() + 1);
    end.setDate(Math.min(d, lastDayOfNextMonth) - 1);
  }
  if (b['period'] === 'WEEK') end.setDate(end.getDate() + 6);
  return `${end.getFullYear()}-${String(end.getMonth() + 1).padStart(2, '0')}-${String(end.getDate()).padStart(2, '0')}`;
}
export function budgetSpent(b: Body, entries: Entry[]): number {
  if (b['period'] === 'TRIP')
    return active(entries, 'expense')
      .filter((e) => e.tripId === b['budgetTripId'])
      .reduce((s, e) => s + e.body['amount'], 0);
  return active(entries, 'transaction')
    .filter(
      (e) =>
        e.body['type'] === 'EXPENSE' &&
        e.body['currency'] === b['currency'] &&
        e.body['date'] >= b['startDate'] &&
        e.body['date'] <= budgetEnd(b) &&
        (!b['categoryId'] ||
          e.body['categoryId'] === b['categoryId'] ||
          entries.find((c) => c.id === e.body['categoryId'])?.body['parentId'] === b['categoryId']),
    )
    .reduce((s, e) => s + e.body['amount'], 0);
}
export function tripBalances(entries: Entry[], tripId: string) {
  const map = new Map<string, { id: string; paid: number; owed: number; net: number }>();
  active(entries, 'participant')
    .filter((e) => e.tripId === tripId)
    .forEach((p) => map.set(p.id, { id: p.id, paid: 0, owed: 0, net: 0 }));
  for (const e of active(entries, 'expense').filter((e) => e.tripId === tripId)) {
    const payer = map.get(e.body['payerId']);
    if (payer) payer.paid += e.body['amount'];
    for (const p of e.body['allocations'] || []) {
      const row = map.get(p.participantId);
      if (row) row.owed += p.amount;
    }
  }
  for (const p of map.values()) p.net = p.paid - p.owed;
  for (const e of active(entries, 'settlement').filter(
    (e) => e.tripId === tripId && e.body['status'] === 'Confirmed',
  )) {
    const from = map.get(e.body['from']),
      to = map.get(e.body['to']);
    if (from) from.net += e.body['amount'];
    if (to) to.net -= e.body['amount'];
  }
  return [...map.values()];
}
export function settlements(net: { id: string; net: number }[]): Transfer[] {
  const rows = net.map((p) => ({ ...p }));
  if (rows.reduce((s, r) => s + r.net, 0) !== 0) throw Error('Trip balances do not reconcile.');
  const result: Transfer[] = [];
  while (true) {
    const from = rows
      .filter((r) => r.net < 0)
      .sort((a, b) => a.net - b.net || a.id.localeCompare(b.id))[0];
    const to = rows
      .filter((r) => r.net > 0)
      .sort((a, b) => b.net - a.net || a.id.localeCompare(b.id))[0];
    if (!from || !to) break;
    const amount = Math.min(-from.net, to.net);
    result.push({ from: from.id, to: to.id, amount });
    from.net += amount;
    to.net -= amount;
  }
  return result;
}
export function validate(kind: string, b: Body) {
  if (
    ['account', 'category', 'budget', 'trip', 'participant'].includes(kind) &&
    (!String(b['name'] || '').trim() || String(b['name']).length > 80)
  )
    throw Error('Enter a name (up to 80 characters).');
  if (
    ['transaction', 'expense'].includes(kind) &&
    (!String(b['description'] || '').trim() || String(b['description']).length > 160)
  )
    throw Error('Enter a description (up to 160 characters).');
  if (
    ['transaction', 'expense', 'budget', 'settlement'].includes(kind) &&
    (!Number.isSafeInteger(b['amount']) || b['amount'] <= 0 || b['amount'] > MAX_MONEY)
  )
    throw Error('Enter a positive amount.');
  if (['transaction', 'expense'].includes(kind) && !validDate(b['date']))
    throw Error('Enter a valid calendar date.');
  if (kind === 'budget' && !validDate(b['startDate'])) throw Error('Enter a valid starting date.');
  if (
    kind === 'transaction' &&
    b['type'] === 'TRANSFER' &&
    (!b['accountId'] || !b['toAccountId'] || b['accountId'] === b['toAccountId'])
  )
    throw Error('Choose two different accounts for a transfer.');
  if (kind === 'expense') {
    b['allocations'] = split(b['amount'], b['method'], b['parts']);
    b['payers'] = [{ participantId: b['payerId'], amount: b['amount'] }];
    if (!b['payerId']) throw Error('Choose a payer.');
  }
}
