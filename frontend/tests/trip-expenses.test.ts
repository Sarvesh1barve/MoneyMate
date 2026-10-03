import { test } from 'node:test';
import assert from 'node:assert/strict';
import { Entry } from '../src/app/models';
import { TripExpenseView, visibleTripExpenses } from '../src/app/trip-expenses';

const expense = (
  id: string,
  description: string,
  date: string,
  added: string,
  amount: number,
  payerId: string,
): Entry => ({
  id,
  kind: 'expense',
  ownerId: 'alice',
  tripId: 'goa',
  version: 1,
  deleted: false,
  body: { description, date, amount, payerId, notes: '' },
  createdAt: added,
  updatedAt: added,
});

const older = expense('1', 'Dinner', '2026-10-03', '2026-10-01T10:00:00Z', 10000, 'alice');
const middle = expense('2', 'Hotel', '2026-10-01', '2026-10-02T10:00:00Z', 20000, 'bob');
const newer = expense('3', 'Taxi', '2026-10-02', '2026-10-03T10:00:00Z', 5000, 'alice');
const all = [middle, older, newer];
const view: TripExpenseView = {
  search: '',
  payerId: '',
  fromDate: '',
  toDate: '',
  sort: 'added-newest',
};
const ids = (entries: Entry[]) => entries.map((entry) => entry.id);

test('trip expenses show newest additions first, even after an older expense is edited', () => {
  const edited = { ...older, updatedAt: '2026-10-04T10:00:00Z' };
  assert.deepEqual(ids(visibleTripExpenses([edited, middle, newer], view)), ['3', '2', '1']);
  assert.deepEqual(ids(visibleTripExpenses(all, { ...view, sort: 'added-oldest' })), [
    '1',
    '2',
    '3',
  ]);
});

test('trip expense sorting by expense date and amount differs from addition time', () => {
  assert.deepEqual(ids(visibleTripExpenses(all, { ...view, sort: 'date-newest' })), [
    '1',
    '3',
    '2',
  ]);
  assert.deepEqual(ids(visibleTripExpenses(all, { ...view, sort: 'amount-highest' })), [
    '2',
    '1',
    '3',
  ]);
});

test('trip expense filters combine search, payer, and inclusive expense dates', () => {
  assert.deepEqual(ids(visibleTripExpenses(all, { ...view, payerId: 'alice' })), ['3', '1']);
  assert.deepEqual(ids(visibleTripExpenses(all, { ...view, search: ' hOtEl ' })), ['2']);
  assert.deepEqual(
    ids(visibleTripExpenses(all, { ...view, fromDate: '2026-10-02', toDate: '2026-10-02' })),
    ['3'],
  );
  assert.deepEqual(visibleTripExpenses(all, { ...view, payerId: 'bob', search: 'taxi' }), []);
});
