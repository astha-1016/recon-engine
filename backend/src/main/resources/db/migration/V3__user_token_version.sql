-- Sessions end when a user's role or password changes: tokens carry this number and are rejected when it no longer matches.
ALTER TABLE app_users ADD COLUMN IF NOT EXISTS token_version INTEGER NOT NULL DEFAULT 0;
