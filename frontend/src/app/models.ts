export type Kind =
  | 'account'
  | 'category'
  | 'transaction'
  | 'budget'
  | 'settings'
  | 'trip'
  | 'participant'
  | 'expense'
  | 'settlement';
export type Body = Record<string, any>;
export interface Entry {
  id: string;
  kind: Kind;
  ownerId: string;
  tripId: string | null;
  version: number;
  deleted: boolean;
  body: Body;
  createdAt: string;
  updatedAt: string;
}
export interface Operation {
  operationId: string;
  id: string;
  kind: Kind;
  tripId: string | null;
  baseVersion: number;
  deleted: boolean;
  body: Body;
}
export interface Pending {
  userId: string;
  operationId: string;
  operation: Operation;
  sequence: number;
  error?: string;
  server?: Entry;
}
export interface Member {
  tripId: string;
  userId: string;
  participantId: string;
  role: 'OWNER' | 'MEMBER';
  name: string;
}
export interface User {
  id: string;
  email: string;
  name: string;
}
export interface Snapshot {
  records: Entry[];
  members: Member[];
  activity: Body[];
  serverTime: string;
}
export interface Part {
  participantId: string;
  value: number;
}
export interface Allocation {
  participantId: string;
  amount: number;
}
export interface Transfer {
  from: string;
  to: string;
  amount: number;
}
