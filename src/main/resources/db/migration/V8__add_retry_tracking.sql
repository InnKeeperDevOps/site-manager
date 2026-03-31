ALTER TABLE plan_tasks ADD COLUMN retry_count INTEGER NOT NULL DEFAULT 0;
ALTER TABLE plan_tasks ADD COLUMN failure_reason VARCHAR(1000);
ALTER TABLE suggestions ADD COLUMN failure_reason VARCHAR(1000);
