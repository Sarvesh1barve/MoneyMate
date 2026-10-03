import { Entry } from './models';

export type TripExpenseSort =
  | 'added-newest'
  | 'added-oldest'
  | 'date-newest'
  | 'date-oldest'
  | 'amount-highest'
  | 'amount-lowest';

export interface TripExpenseView {
  search: string;
  payerId: string;
  fromDate: string;
  toDate: string;
  sort: TripExpenseSort;
}

export function addedAt(entry: Entry): string {
  // Older cached records and pre-upgrade deduplicated replies may lack createdAt.
  return entry.createdAt || entry.updatedAt || '';
}

export function visibleTripExpenses(expenses: Entry[], view: TripExpenseView): Entry[] {
  const search = view.search.trim().toLocaleLowerCase();
  return expenses
    .filter(
      (entry) =>
        (!search ||
          `${entry.body['description'] || ''} ${entry.body['notes'] || ''}`
            .toLocaleLowerCase()
            .includes(search)) &&
        (!view.payerId || entry.body['payerId'] === view.payerId) &&
        (!view.fromDate || entry.body['date'] >= view.fromDate) &&
        (!view.toDate || entry.body['date'] <= view.toDate),
    )
    .sort((a, b) => {
      let result = 0;
      switch (view.sort) {
        case 'added-oldest':
          result = addedAt(a).localeCompare(addedAt(b));
          break;
        case 'date-newest':
          result = b.body['date'].localeCompare(a.body['date']);
          break;
        case 'date-oldest':
          result = a.body['date'].localeCompare(b.body['date']);
          break;
        case 'amount-highest':
          result = b.body['amount'] - a.body['amount'];
          break;
        case 'amount-lowest':
          result = a.body['amount'] - b.body['amount'];
          break;
        default:
          result = addedAt(b).localeCompare(addedAt(a));
      }
      // Stable results when two records share a timestamp/date/amount.
      return result || addedAt(b).localeCompare(addedAt(a)) || a.id.localeCompare(b.id);
    });
}
