-- Stores a JSON map of expert role names to their approval status and round number
-- Format: {"expertRoleName": {"status": "APPROVED"|"CHANGES_PROPOSED"|"CHANGES_REJECTED"|"NEEDS_CLARIFICATION", "round": N}}
ALTER TABLE suggestions ADD COLUMN expert_approval_tracker TEXT;
