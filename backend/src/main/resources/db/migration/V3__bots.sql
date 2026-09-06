-- Bots: a configurable assistant belonging to one organization.

CREATE TABLE bots (
    id               UUID         PRIMARY KEY DEFAULT gen_random_uuid(),

    -- The tenant boundary. Every query against this table filters on it, and
    -- ON DELETE CASCADE means removing an organization removes its bots rather
    -- than orphaning rows that no one can reach or clean up.
    organization_id  UUID         NOT NULL,

    name             VARCHAR(120) NOT NULL,
    description      VARCHAR(1000),
    status           VARCHAR(20)  NOT NULL DEFAULT 'ACTIVE',
    default_language VARCHAR(10)  NOT NULL DEFAULT 'en',

    -- Application-level customisation, stored but not yet sent anywhere: the
    -- frozen AI service contract has no parameter for a per-bot prompt. See
    -- backend/docs/AI_INTEGRATION_CONTRACT.md section 3.4.
    system_prompt    TEXT,

    created_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),

    CONSTRAINT fk_bots_organization
        FOREIGN KEY (organization_id) REFERENCES organizations (id) ON DELETE CASCADE,

    -- Unique per organization, not globally: two different customers may both
    -- have a bot called "Support", and forbidding that would leak the fact
    -- that another tenant already used the name.
    CONSTRAINT uq_bots_organization_name UNIQUE (organization_id, name),

    CONSTRAINT ck_bots_status CHECK (status IN ('ACTIVE', 'INACTIVE')),
    CONSTRAINT ck_bots_name_not_blank CHECK (length(btrim(name)) > 0)
);

-- Every listing is "the bots of one organization, newest first", so the index
-- covers both the filter and the sort.
CREATE INDEX ix_bots_organization_created ON bots (organization_id, created_at DESC);

CREATE TRIGGER trg_bots_updated_at
    BEFORE UPDATE ON bots
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();

COMMENT ON COLUMN bots.system_prompt IS
    'Reserved. The AI service accepts no per-request system prompt today.';
