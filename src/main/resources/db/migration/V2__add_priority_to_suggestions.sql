-- Add priority column to suggestions table.
-- Flyway ensures this migration runs exactly once per database.
ALTER TABLE suggestions ADD COLUMN priority VARCHAR(10) NOT NULL DEFAULT 'MEDIUM';
