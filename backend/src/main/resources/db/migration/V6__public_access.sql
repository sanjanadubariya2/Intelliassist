-- Public widget access: API keys and allowed domains.
--
-- These back a fundamentally different trust model from the dashboard. A
-- dashboard user holds a JWT and is a known person; a widget visitor is an
-- anonymous browser on a customer's website. The two must never share a
-- credential, which is why this is a separate mechanism rather than a
-- longer-lived JWT.

-- ================================================================ api_keys --
CREATE TABLE api_keys (
    id           UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    bot_id       UUID         NOT NULL,
    name         VARCHAR(120) NOT NULL,

    -- SHA-256 of the key. The raw value is shown once at creation and never
    -- again: storing it would mean a database read is enough to impersonate
    -- every widget, and there is no need to ever read it back.
    key_hash     VARCHAR(64)  NOT NULL,

    -- First few characters of the raw key, kept so a customer can tell their
    -- keys apart in a list without us storing anything usable.
    key_prefix   VARCHAR(16)  NOT NULL,

    last_used_at TIMESTAMPTZ,
    revoked_at   TIMESTAMPTZ,
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),

    CONSTRAINT fk_api_keys_bot
        FOREIGN KEY (bot_id) REFERENCES bots (id) ON DELETE CASCADE,

    -- Globally unique: the hash is the lookup key on every public request, and
    -- a collision would mean one key authenticating two bots.
    CONSTRAINT uq_api_keys_hash UNIQUE (key_hash)
);

CREATE INDEX ix_api_keys_bot ON api_keys (bot_id);

COMMENT ON COLUMN api_keys.key_hash IS
    'SHA-256 of the raw key. The raw key is never stored, logged or returned after creation.';

-- ========================================================= allowed_domains --
CREATE TABLE allowed_domains (
    id         UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    bot_id     UUID         NOT NULL,

    -- Hostname only, lowercased, no scheme or port. Stored normalised so the
    -- request-time comparison is a plain equality check rather than a parse.
    domain     VARCHAR(253) NOT NULL,

    created_at TIMESTAMPTZ  NOT NULL DEFAULT now(),

    CONSTRAINT fk_allowed_domains_bot
        FOREIGN KEY (bot_id) REFERENCES bots (id) ON DELETE CASCADE,
    CONSTRAINT uq_allowed_domains_bot_domain UNIQUE (bot_id, domain),
    CONSTRAINT ck_allowed_domains_lowercase CHECK (domain = lower(domain)),
    CONSTRAINT ck_allowed_domains_no_scheme CHECK (domain NOT LIKE '%://%')
);

CREATE INDEX ix_allowed_domains_bot ON allowed_domains (bot_id);

COMMENT ON TABLE allowed_domains IS
    'Origins permitted to use a bot. With none registered the widget is refused: fail closed.';
