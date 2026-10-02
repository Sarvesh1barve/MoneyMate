import { test } from 'node:test';
import assert from 'node:assert/strict';
import {
  balance,
  budgetSpent,
  minor,
  major,
  settlements,
  split,
  totals,
  tripBalances,
  validDate,
  validate,
} from '../src/app/finance';
import { Entry } from '../src/app/models';
const entry = (kind: string, id: string, body: any, tripId: string | null = null): Entry => ({
  id,
  kind: kind as any,
  ownerId: 'user',
  tripId,
  body,
  version: 1,
  deleted: false,
  updatedAt: '',
});
test('minor units preserve precision and currencies', () => {
  assert.equal(minor('12.34'), 1234);
  assert.equal(major(1234), '12.34');
  assert.equal(minor('100', 'JPY'), 100);
  assert.equal(minor('1.234', 'KWD'), 1234);
  assert.throws(() => minor('1.001'));
  assert.throws(() => minor('1.1', 'JPY'));
  assert.throws(() => minor('NaN'));
});
test('dates are validated as local calendar dates', () => {
  assert.ok(validDate('2024-02-29'));
  assert.ok(validDate('2026-10-01'));
  assert.equal(validDate('2025-02-29'), false);
  assert.equal(validDate('2026-13-01'), false);
});
test('balances include transfers but spending does not; future entries excluded', () => {
  const a = entry('account', 'a', { openingBalance: 10000, currency: 'INR' }),
    b = entry('account', 'b', { openingBalance: 0, currency: 'INR' });
  const rows = [
    entry('transaction', '1', {
      type: 'EXPENSE',
      amount: 1000,
      accountId: 'a',
      currency: 'INR',
      date: '2026-01-01',
    }),
    entry('transaction', '2', {
      type: 'INCOME',
      amount: 500,
      accountId: 'a',
      currency: 'INR',
      date: '2026-01-01',
    }),
    entry('transaction', '3', {
      type: 'TRANSFER',
      amount: 2000,
      accountId: 'a',
      toAccountId: 'b',
      currency: 'INR',
      date: '2026-01-01',
    }),
    entry('transaction', '4', {
      type: 'EXPENSE',
      amount: 9999,
      accountId: 'a',
      currency: 'INR',
      date: '2099-01-01',
    }),
  ];
  assert.equal(balance(a, rows, '2026-10-01'), 7500);
  assert.equal(balance(b, rows, '2026-10-01'), 2000);
  assert.deepEqual(totals(rows, 'INR', '2026-01-01', '2026-10-01'), {
    income: 500,
    spending: 1000,
  });
});
test('daily weekly monthly category and trip budgets', () => {
  const rows = [
    entry('transaction', '1', {
      type: 'EXPENSE',
      amount: 1000,
      currency: 'INR',
      categoryId: 'child',
      date: '2026-10-01',
    }),
    entry('transaction', '2', {
      type: 'EXPENSE',
      amount: 500,
      currency: 'INR',
      categoryId: 'other',
      date: '2026-10-03',
    }),
    entry('transaction', '3', {
      type: 'TRANSFER',
      amount: 999,
      currency: 'INR',
      date: '2026-10-01',
    }),
    entry('category', 'child', { parentId: 'parent' }),
    entry('expense', '4', { amount: 800 }, 'trip'),
  ];
  const budget = { startDate: '2026-10-01', currency: 'INR' };
  assert.equal(budgetSpent({ ...budget, period: 'DAY' }, rows), 1000);
  assert.equal(budgetSpent({ ...budget, period: 'WEEK' }, rows), 1500);
  assert.equal(budgetSpent({ ...budget, period: 'MONTH', categoryId: 'parent' }, rows), 1000);
  assert.equal(budgetSpent({ ...budget, period: 'TRIP', budgetTripId: 'trip' }, rows), 800);
});
test('split methods and deterministic largest remainders', () => {
  const equal = split(100, 'EQUAL', [
    { participantId: 'c', value: 0 },
    { participantId: 'b', value: 0 },
    { participantId: 'a', value: 0 },
  ]);
  assert.deepEqual(
    equal.map((p) => p.amount),
    [34, 33, 33],
  );
  assert.deepEqual(
    split(101, 'PERCENT', [
      { participantId: 'a', value: 2500 },
      { participantId: 'b', value: 7500 },
    ]).map((p) => p.amount),
    [25, 76],
  );
  assert.deepEqual(
    split(101, 'SHARES', [
      { participantId: 'a', value: 1 },
      { participantId: 'b', value: 2 },
    ]).map((p) => p.amount),
    [34, 67],
  );
  assert.deepEqual(split(100, 'SELECTED', [{ participantId: 'b', value: 1 }]), [
    { participantId: 'b', amount: 100 },
  ]);
  assert.equal(
    split(100, 'EXACT', [
      { participantId: 'a', value: 33 },
      { participantId: 'b', value: 67 },
    ]).reduce((s, p) => s + p.amount, 0),
    100,
  );
  assert.throws(() => split(100, 'EXACT', [{ participantId: 'a', value: 99 }]));
  assert.throws(() => split(100, 'PERCENT', [{ participantId: 'a', value: 99 }]));
  assert.throws(() => split(100, 'SHARES', [{ participantId: 'a', value: 0 }]));
});
test('every remainder is preserved for large expenses', () => {
  for (let amount = 1; amount < 300; amount++) {
    const allocations = split(amount, 'SHARES', [
      { participantId: 'a', value: 17 },
      { participantId: 'b', value: 19 },
      { participantId: 'c', value: 3 },
    ]);
    assert.equal(
      allocations.reduce((s, p) => s + p.amount, 0),
      amount,
    );
  }
  assert.equal(
    split(9_000_000_000_000, 'SHARES', [
      { participantId: 'a', value: 999999 },
      { participantId: 'b', value: 1 },
    ]).reduce((s, p) => s + p.amount, 0),
    9_000_000_000_000,
  );
});
test('only confirmed settlements change net balances', () => {
  const rows = [
    entry('participant', 'a', { name: 'Alice' }, 'trip'),
    entry('participant', 'b', { name: 'Bob' }, 'trip'),
    entry(
      'expense',
      'e',
      {
        payerId: 'a',
        amount: 10000,
        allocations: [
          { participantId: 'a', amount: 5000 },
          { participantId: 'b', amount: 5000 },
        ],
      },
      'trip',
    ),
  ];
  assert.deepEqual(settlements(tripBalances(rows, 'trip')), [{ from: 'b', to: 'a', amount: 5000 }]);
  const payment = entry(
    'settlement',
    's',
    { from: 'b', to: 'a', amount: 5000, status: 'Paid' },
    'trip',
  );
  assert.equal(settlements(tripBalances([...rows, payment], 'trip')).length, 1);
  payment.body.status = 'Confirmed';
  assert.deepEqual(settlements(tripBalances([...rows, payment], 'trip')), []);
});
test('local validation rejects invalid transfers before persistence', () => {
  assert.throws(() =>
    validate('transaction', {
      type: 'TRANSFER',
      description: 'Move',
      amount: 100,
      date: '2026-10-01',
      accountId: 'a',
      toAccountId: 'a',
    }),
  );
});
import { budgetEnd } from '../src/app/finance';
test('monthly budgets clamp month-end boundaries without date overflow', () => {
  assert.equal(budgetEnd({ startDate: '2026-01-01', period: 'MONTH' }), '2026-01-31');
  assert.equal(budgetEnd({ startDate: '2026-01-31', period: 'MONTH' }), '2026-02-27');
  assert.equal(budgetEnd({ startDate: '2024-01-31', period: 'MONTH' }), '2024-02-28');
});
