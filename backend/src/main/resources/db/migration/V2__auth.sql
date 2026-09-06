-- Tenancy and identity: organizations, users, and the membership that joins them.

-- ============================================================ organizations --
-- The tenant boundary. Every business resource in this system ultimately hangs
-- off an organization, and every authorization check reduces to "does the
-- caller's organization own this row".
CREATE TABLE organizations (
    id         UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    name       VARCHAR(200) NOT NULL,
    slug       VARCHAR(80)  NOT NULL,
    created_at TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ  NOT NULL DEFAULT now(),

    CONSTRAINT uq_organizations_slug UNIQUE (slug),
    -- URL-safe: lowercase alphanumerics and single hyphens, no leading or
    -- trailing hyphen. Enforced here as well as in the application, because a
    -- slug ends up in URLs and the database is the last line of defence.
    CONSTRAINT ck_organizations_slug_format
        CHECK (slug ~ '^[a-z0-9]+(-[a-z0-9]+)*$')
);

CREATE TRIGGER trg_organizations_updated_at
    BEFORE UPDATE ON organizations
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();

-- =================================================================== users --
CREATE TABLE users (
    id            UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    name          VARCHAR(200) NOT NULL,
    -- Stored already lowercased by the application. A plain unique constraint
    -- is then genuinely case-insensitive without needing citext, and the index
    -- stays usable for lookups.
    email         VARCHAR(320) NOT NULL,
    password_hash VARCHAR(100) NOT NULL,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),

    CONSTRAINT uq_users_email UNIQUE (email),
    CONSTRAINT ck_users_email_lowercase CHECK (email = lower(email))
);

CREATE TRIGGER trg_users_updated_at
    BEFORE UPDATE ON users
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();

COMMENT ON COLUMN users.password_hash IS
    'BCrypt hash. The plaintext password is never stored, logged or returned.';

-- ==================================================== organization_members --
-- Many-to-many by design: a user can belong to several organizations, which is
-- normal in B2B (an agency managing two clients, a consultant, a support
-- engineer). The role lives on the membership, not on the user, because the
-- same person can be an OWNER in one organization and a MEMBER in another.
CREATE TABLE organization_members (
    id              UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id UUID        NOT NULL,
    user_id         UUID        NOT NULL,
    role            VARCHAR(20) NOT NULL,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT fk_members_organization
        FOREIGN KEY (organization_id) REFERENCES organizations (id) ON DELETE CASCADE,
    CONSTRAINT fk_members_user
        FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE,

    -- One membership per user per organization; a second row would make "what
    -- is this user's role here" ambiguous.
    CONSTRAINT uq_members_org_user UNIQUE (organization_id, user_id),
    CONSTRAINT ck_members_role CHECK (role IN ('OWNER', 'ADMIN', 'MEMBER'))
);

-- Authorization resolves a membership on every authenticated request, so this
-- lookup is the hottest query in the system.
CREATE INDEX ix_members_user_id ON organization_members (user_id);
CREATE INDEX ix_members_organization_id ON organization_members (organization_id);
