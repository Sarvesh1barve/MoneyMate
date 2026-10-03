ALTER TABLE record ADD COLUMN created_at TIMESTAMPTZ;

-- Earlier expenses have an activity entry from their original creation. For
-- other existing records, the previous schema only retained the last edit.
UPDATE record r
SET created_at = COALESCE(
  (SELECT MIN(a.created_at) FROM activity a
   WHERE r.kind = 'expense' AND a.record_id = r.id AND a.action = 'Created expense'),
  r.updated_at
);

ALTER TABLE record ALTER COLUMN created_at SET DEFAULT now();
ALTER TABLE record ALTER COLUMN created_at SET NOT NULL;
