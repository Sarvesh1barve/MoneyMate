CREATE TABLE app_user (
 id UUID PRIMARY KEY, email VARCHAR(254) NOT NULL UNIQUE, display_name VARCHAR(80) NOT NULL,
 password_hash VARCHAR(100) NOT NULL, created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE TABLE auth_session (
 token_hash CHAR(64) PRIMARY KEY, user_id UUID NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
 expires_at TIMESTAMPTZ NOT NULL, created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX session_expiry ON auth_session(expires_at);
-- Stable typed, versioned records share one synchronization envelope.
CREATE TABLE record (
 id UUID PRIMARY KEY, kind VARCHAR(20) NOT NULL CHECK(kind IN ('account','category','transaction','budget','settings','trip','participant','expense','settlement')),
 owner_id UUID NOT NULL REFERENCES app_user(id), trip_id UUID REFERENCES record(id),
 version BIGINT NOT NULL CHECK(version > 0), deleted BOOLEAN NOT NULL DEFAULT false,
 body JSONB NOT NULL CHECK(jsonb_typeof(body) = 'object'), updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
 CHECK ((kind IN ('participant','expense','settlement') AND trip_id IS NOT NULL) OR (kind NOT IN ('participant','expense','settlement') AND trip_id IS NULL))
);
CREATE INDEX record_owner ON record(owner_id,kind);
CREATE INDEX record_trip ON record(trip_id);
CREATE TABLE membership (
 trip_id UUID NOT NULL REFERENCES record(id), user_id UUID NOT NULL REFERENCES app_user(id),
 participant_id UUID NOT NULL REFERENCES record(id), role VARCHAR(10) NOT NULL CHECK(role IN ('OWNER','MEMBER')),
 PRIMARY KEY(trip_id,user_id), UNIQUE(trip_id,participant_id)
);
CREATE TABLE invitation (
 token_hash CHAR(64) PRIMARY KEY, trip_id UUID NOT NULL REFERENCES record(id),
 participant_id UUID NOT NULL REFERENCES record(id), created_by UUID NOT NULL REFERENCES app_user(id),
 expires_at TIMESTAMPTZ NOT NULL, used_at TIMESTAMPTZ
);
CREATE TABLE sync_operation (
 user_id UUID NOT NULL REFERENCES app_user(id), operation_id UUID NOT NULL,
 fingerprint CHAR(64) NOT NULL, result JSONB NOT NULL, created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
 PRIMARY KEY(user_id,operation_id)
);
CREATE TABLE activity (
 id BIGSERIAL PRIMARY KEY, trip_id UUID NOT NULL REFERENCES record(id), actor_id UUID NOT NULL REFERENCES app_user(id),
 action VARCHAR(60) NOT NULL, record_id UUID, created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX activity_trip ON activity(trip_id,id);
-- Explicit domain projections for administration/reporting; application access is always authorized.
CREATE VIEW personal_accounts AS SELECT * FROM record WHERE kind='account';
CREATE VIEW transactions AS SELECT * FROM record WHERE kind='transaction';
CREATE VIEW categories AS SELECT * FROM record WHERE kind='category';
CREATE VIEW budgets AS SELECT * FROM record WHERE kind='budget';
CREATE VIEW trips AS SELECT * FROM record WHERE kind='trip';
CREATE VIEW participants AS SELECT * FROM record WHERE kind='participant';
CREATE VIEW expenses AS SELECT * FROM record WHERE kind='expense';
CREATE VIEW settlements AS SELECT * FROM record WHERE kind='settlement';
CREATE VIEW expense_splits AS SELECT id AS expense_id, trip_id, split FROM record, LATERAL jsonb_array_elements(body->'allocations') split WHERE kind='expense';
