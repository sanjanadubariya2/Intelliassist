-- Everything the dashboard design needs that the schema could not express.
--
-- Three separate things, in one migration because they ship as one feature set:
--   1. widget appearance, so the Customization screen has somewhere to save to
--   2. conversation resolution, so "Resolved" and "Escalated" mean something
--   3. internal notes, so an operator can annotate a transcript

-- ---------------------------------------------------------------- appearance
--
-- Columns on `bots` rather than a separate table: it is exactly one row per
-- bot, always read with the bot, and never queried on its own. A join table
-- would buy nothing and cost a join on the widget's hot path.

ALTER TABLE bots
    -- Hex, validated below. Stored as text rather than an integer because it
    -- is only ever rendered, and every consumer wants "#7C5AED".
    ADD COLUMN primary_color     VARCHAR(7)   NOT NULL DEFAULT '#7C5AED',

    ADD COLUMN widget_position   VARCHAR(20)  NOT NULL DEFAULT 'BOTTOM_RIGHT',

    -- Shown before the visitor has said anything, so it is the one piece of
    -- copy every visitor reads.
    ADD COLUMN welcome_message   VARCHAR(500) NOT NULL
        DEFAULT 'Hi there! 👋 How can I help you today?',

    -- Shown when retrieval finds nothing. Configurable because "I don't know"
    -- should sound like the customer's brand, not like ours.
    ADD COLUMN fallback_message  VARCHAR(500) NOT NULL
        DEFAULT 'Sorry, I don''t have information about that. Would you like to talk to a human?',

    ADD COLUMN launcher_icon     VARCHAR(20)  NOT NULL DEFAULT 'CHAT';

ALTER TABLE bots
    -- #RGB or #RRGGBB. Rejecting a bad colour at the database keeps the widget
    -- from ever rendering a broken style attribute on a customer's site.
    ADD CONSTRAINT ck_bots_primary_color
        CHECK (primary_color ~ '^#[0-9A-Fa-f]{3}([0-9A-Fa-f]{3})?$'),

    ADD CONSTRAINT ck_bots_widget_position
        CHECK (widget_position IN ('BOTTOM_RIGHT', 'BOTTOM_LEFT', 'TOP_RIGHT', 'TOP_LEFT')),

    ADD CONSTRAINT ck_bots_launcher_icon
        CHECK (launcher_icon IN ('CHAT', 'MESSAGE', 'HELP', 'SPARKLE'));

COMMENT ON COLUMN bots.fallback_message IS
    'Shown when retrieval finds nothing. Not a prompt - the AI service decides to abstain, this is only the wording.';

-- --------------------------------------------------------------- resolution
--
-- Deliberately separate from `status`. Those answer different questions:
-- status is whether the conversation is still open, resolution is whether the
-- visitor got what they came for. A conversation can be CLOSED and ESCALATED
-- at the same time, and collapsing them would make that unrepresentable.

ALTER TABLE conversations
    ADD COLUMN resolution VARCHAR(20) NOT NULL DEFAULT 'UNRESOLVED';

ALTER TABLE conversations
    ADD CONSTRAINT ck_conversations_resolution
        CHECK (resolution IN ('UNRESOLVED', 'RESOLVED', 'ESCALATED'));

-- The dashboard filters on it constantly, and always within one bot.
CREATE INDEX ix_conversations_bot_resolution
    ON conversations (bot_id, resolution, last_message_at DESC);

COMMENT ON COLUMN conversations.resolution IS
    'Whether the visitor got what they needed. Independent of status, which is only whether the conversation is still open.';

-- -------------------------------------------------------------------- notes
--
-- Operator annotations on a transcript. Never shown to the visitor and never
-- sent to the AI service - they are staff commentary, often about the visitor.

CREATE TABLE conversation_notes (
    id              UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    conversation_id UUID        NOT NULL,

    -- Who wrote it. Kept when the user is deleted, because a note whose author
    -- vanished is still evidence; the column goes null rather than the row.
    author_id       UUID,
    author_name     VARCHAR(255) NOT NULL,

    body            VARCHAR(2000) NOT NULL,

    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT fk_note_conversation FOREIGN KEY (conversation_id)
        REFERENCES conversations (id) ON DELETE CASCADE,
    CONSTRAINT fk_note_author FOREIGN KEY (author_id)
        REFERENCES users (id) ON DELETE SET NULL,

    CONSTRAINT ck_note_body_not_blank CHECK (length(trim(body)) > 0)
);

CREATE INDEX ix_notes_conversation ON conversation_notes (conversation_id, created_at);

COMMENT ON TABLE conversation_notes IS
    'Internal operator notes. Never returned by the public API and never sent to the AI service.';
