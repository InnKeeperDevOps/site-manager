-- Add priority column to suggestions table.
-- IF NOT EXISTS guard ensures this is safe to run against the V1 schema
-- (which already includes the column) and against any existing database
-- that was baselined before this migration ran.
ALTER TABLE suggestions ADD COLUMN IF NOT EXISTS priority VARCHAR(10) NOT NULL DEFAULT 'MEDIUM';
