-- A general invitation creates the accepting user's participant atomically.
ALTER TABLE invitation ALTER COLUMN participant_id DROP NOT NULL;
ALTER TABLE invitation ADD COLUMN accepted_by UUID REFERENCES app_user(id);

CREATE TABLE remembered_device (
 id UUID PRIMARY KEY, user_id UUID NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
 public_key TEXT NOT NULL, origin VARCHAR(300) NOT NULL, name VARCHAR(80) NOT NULL,
 created_at TIMESTAMPTZ NOT NULL DEFAULT now(), expires_at TIMESTAMPTZ NOT NULL,
 last_used_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
ALTER TABLE auth_session ADD COLUMN device_id UUID REFERENCES remembered_device(id) ON DELETE CASCADE;
ALTER TABLE auth_session ADD COLUMN strong_auth BOOLEAN NOT NULL DEFAULT true;
CREATE INDEX device_user ON remembered_device(user_id);

CREATE TABLE passkey (
 credential_id TEXT PRIMARY KEY, user_id UUID NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
 rp_id VARCHAR(253) NOT NULL, public_key_cose TEXT NOT NULL, signature_count BIGINT NOT NULL,
 name VARCHAR(80) NOT NULL, created_at TIMESTAMPTZ NOT NULL DEFAULT now(), last_used_at TIMESTAMPTZ,
 backup_eligible BOOLEAN NOT NULL, backed_up BOOLEAN NOT NULL,
 CHECK(signature_count >= 0)
);
CREATE INDEX passkey_user ON passkey(user_id);

CREATE TABLE signin_challenge (
 id UUID PRIMARY KEY, kind VARCHAR(20) NOT NULL, user_id UUID REFERENCES app_user(id) ON DELETE CASCADE,
 origin VARCHAR(300) NOT NULL, payload TEXT NOT NULL, expires_at TIMESTAMPTZ NOT NULL
);
CREATE INDEX signin_challenge_expiry ON signin_challenge(expires_at);
