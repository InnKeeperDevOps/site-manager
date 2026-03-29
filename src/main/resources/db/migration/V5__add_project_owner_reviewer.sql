-- Stores a JSON array of plan task indices locked by the Project Owner reviewer
ALTER TABLE suggestions ADD COLUMN owner_locked_plan_sections TEXT;
