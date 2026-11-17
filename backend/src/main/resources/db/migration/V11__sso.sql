-- Single sign-on with Google, GitHub and LinkedIn.
--
-- An account created through a provider has no password: it signs in only
-- through that provider until its owner sets one. The column therefore becomes
-- nullable, and password login treats a missing hash as a failed match.
ALTER TABLE users ALTER COLUMN password_hash DROP NOT NULL;

COMMENT ON COLUMN users.password_hash IS
    'BCrypt hash, or NULL for an account that only signs in through a provider. '
    'The plaintext password is never stored, logged or returned.';

-- ========================================================= user_identities --
-- Which provider account belongs to which user.
--
-- Keyed on the provider's own stable subject id, never on email: an email
-- address can change at the provider, and matching on it alone would let
-- whoever holds the address next take over the account.
CREATE TABLE user_identities (
    id         UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id    UUID         NOT NULL,
    provider   VARCHAR(20)  NOT NULL,
    subject    VARCHAR(255) NOT NULL,
    -- The address the provider reported at link time. Informational only.
    email      VARCHAR(320),
    created_at TIMESTAMPTZ  NOT NULL DEFAULT now(),

    CONSTRAINT fk_user_identities_user
        FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE,
    CONSTRAINT uq_user_identities_provider_subject UNIQUE (provider, subject),
    CONSTRAINT ck_user_identities_provider
        CHECK (provider IN ('GOOGLE', 'GITHUB', 'LINKEDIN'))
);

CREATE INDEX idx_user_identities_user ON user_identities (user_id);
