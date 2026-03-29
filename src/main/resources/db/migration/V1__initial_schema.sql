-- Initial schema for Site Suggestion Platform
-- Uses CREATE TABLE IF NOT EXISTS so this migration is safe to run against existing databases.

CREATE TABLE IF NOT EXISTS user_groups (
    id      INTEGER PRIMARY KEY AUTOINCREMENT,
    name    VARCHAR(100) NOT NULL UNIQUE,
    can_create_suggestions     INTEGER NOT NULL DEFAULT 0,
    can_vote                   INTEGER NOT NULL DEFAULT 0,
    can_reply                  INTEGER NOT NULL DEFAULT 0,
    can_approve_deny_suggestions INTEGER NOT NULL DEFAULT 0,
    can_manage_settings        INTEGER NOT NULL DEFAULT 0,
    can_manage_users           INTEGER NOT NULL DEFAULT 0
);

CREATE TABLE IF NOT EXISTS app_users (
    id            INTEGER PRIMARY KEY AUTOINCREMENT,
    username      VARCHAR(255) NOT NULL UNIQUE,
    password_hash VARCHAR(255) NOT NULL,
    role          VARCHAR(50)  NOT NULL,
    created_at    TIMESTAMP    NOT NULL,
    group_id      INTEGER REFERENCES user_groups(id),
    approved      INTEGER NOT NULL DEFAULT 0,
    denied        INTEGER NOT NULL DEFAULT 0
);

CREATE TABLE IF NOT EXISTS site_settings (
    id                           INTEGER PRIMARY KEY AUTOINCREMENT,
    allow_anonymous_suggestions  INTEGER NOT NULL DEFAULT 1,
    allow_voting                 INTEGER NOT NULL DEFAULT 1,
    target_repo_url              VARCHAR(255),
    suggestion_timeout_minutes   INTEGER NOT NULL DEFAULT 1440,
    require_approval             INTEGER NOT NULL DEFAULT 1,
    site_name                    VARCHAR(255) DEFAULT 'Site Suggestion Platform',
    github_token                 VARCHAR(255),
    claude_model                 VARCHAR(255),
    claude_model_expert          VARCHAR(255),
    claude_max_turns_expert      INTEGER,
    slack_webhook_url            VARCHAR(255),
    auto_merge_pr                INTEGER NOT NULL DEFAULT 0,
    require_registration_approval INTEGER NOT NULL DEFAULT 0,
    registrations_enabled        INTEGER NOT NULL DEFAULT 1
);

CREATE TABLE IF NOT EXISTS suggestions (
    id                            INTEGER PRIMARY KEY AUTOINCREMENT,
    title                         VARCHAR(255) NOT NULL,
    description                   TEXT,
    status                        VARCHAR(50)  NOT NULL DEFAULT 'DRAFT',
    author_id                     INTEGER,
    author_name                   VARCHAR(255),
    plan_summary                  TEXT,
    plan_display_summary          TEXT,
    current_phase                 VARCHAR(255),
    claude_session_id             VARCHAR(255),
    up_votes                      INTEGER NOT NULL DEFAULT 0,
    down_votes                    INTEGER NOT NULL DEFAULT 0,
    working_directory             VARCHAR(255),
    pending_clarification_questions TEXT,
    pr_url                        VARCHAR(255),
    pr_number                     INTEGER,
    changelog_entry               TEXT,
    expert_review_step            INTEGER,
    expert_review_round           INTEGER,
    expert_review_notes           TEXT,
    expert_review_plan_changed    INTEGER,
    total_expert_review_rounds    INTEGER,
    expert_review_changed_domains TEXT,
    created_at                    TIMESTAMP NOT NULL,
    updated_at                    TIMESTAMP NOT NULL,
    last_activity_at              TIMESTAMP
);

CREATE TABLE IF NOT EXISTS suggestion_messages (
    id           INTEGER PRIMARY KEY AUTOINCREMENT,
    suggestion_id INTEGER NOT NULL,
    sender_type  VARCHAR(50)  NOT NULL,
    sender_name  VARCHAR(255),
    content      TEXT         NOT NULL,
    created_at   TIMESTAMP    NOT NULL
);

CREATE TABLE IF NOT EXISTS votes (
    id                INTEGER PRIMARY KEY AUTOINCREMENT,
    suggestion_id     INTEGER NOT NULL,
    voter_identifier  VARCHAR(255) NOT NULL,
    vote_value        INTEGER NOT NULL,
    created_at        TIMESTAMP NOT NULL,
    UNIQUE(suggestion_id, voter_identifier)
);

CREATE TABLE IF NOT EXISTS plan_tasks (
    id                  INTEGER PRIMARY KEY AUTOINCREMENT,
    suggestion_id       INTEGER NOT NULL,
    task_order          INTEGER NOT NULL,
    title               VARCHAR(255) NOT NULL,
    description         TEXT,
    display_title       VARCHAR(255),
    display_description TEXT,
    status              VARCHAR(50) NOT NULL DEFAULT 'PENDING',
    estimated_minutes   INTEGER,
    started_at          TIMESTAMP,
    completed_at        TIMESTAMP
);
