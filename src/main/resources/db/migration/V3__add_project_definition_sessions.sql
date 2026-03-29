-- Project definition sessions table for managing AI-assisted project definition workflows
CREATE TABLE IF NOT EXISTS project_definition_sessions (
    id                   INTEGER PRIMARY KEY AUTOINCREMENT,
    status               VARCHAR(50)  NOT NULL,
    conversation_history TEXT,
    generated_content    TEXT,
    claude_session_id    VARCHAR(255),
    created_at           TIMESTAMP,
    completed_at         TIMESTAMP,
    branch_name          VARCHAR(255),
    pr_url               VARCHAR(500),
    pr_number            VARCHAR(50),
    error_message        TEXT
);
