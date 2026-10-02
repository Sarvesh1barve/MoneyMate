CREATE UNIQUE INDEX membership_one_owner ON membership(trip_id) WHERE role='OWNER';
CREATE UNIQUE INDEX one_user_settings ON record(owner_id) WHERE kind='settings' AND deleted=false;
ALTER TABLE record ADD CONSTRAINT monetary_body CHECK (
 kind NOT IN ('transaction','expense','budget','settlement') OR (
  body ? 'amount' AND jsonb_typeof(body->'amount')='number'
  AND (body->>'amount')::numeric = trunc((body->>'amount')::numeric)
  AND (body->>'amount')::numeric BETWEEN 1 AND 9000000000000
 )
);
ALTER TABLE record ADD CONSTRAINT currency_body CHECK (
 kind IN ('category','participant') OR (body ? 'currency' AND (body->>'currency') ~ '^[A-Z]{3}$')
);
