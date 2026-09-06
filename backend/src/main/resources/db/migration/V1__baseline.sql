-- Baseline schema.
--
-- Phase 1 establishes only the extension and a shared trigger. The domain
-- tables arrive with the phases that own them, so each migration stays
-- reviewable and the history reads as the order things were actually built.
--
-- Migrations are never edited once applied. Flyway checksums them, so a change
-- to a migration that has already run makes the application refuse to start.
-- Corrections go in a new migration.

-- gen_random_uuid() lives here. Available in core Postgres from 13 onward, but
-- the extension is still required to expose it.
CREATE EXTENSION IF NOT EXISTS pgcrypto;

-- Keeps updated_at honest without every service remembering to set it. Doing
-- this in the database means a direct SQL fix during an incident is recorded
-- the same way an application write is.
CREATE OR REPLACE FUNCTION set_updated_at()
RETURNS TRIGGER AS $$
BEGIN
    NEW.updated_at = now();
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

COMMENT ON FUNCTION set_updated_at() IS
    'Trigger function: stamps updated_at on UPDATE. Attached per table.';
