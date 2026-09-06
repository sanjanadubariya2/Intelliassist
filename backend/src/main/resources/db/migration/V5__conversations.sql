-- Conversations and messages.
--
-- All conversation state lives here. The AI service is stateless: it answers
-- one question against one knowledge base and remembers nothing between calls,
-- so threading, history and ordering are entirely this application's problem.

-- ============================================================ conversations --
CREATE TABLE conversations (
    id              UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    bot_id          UUID         NOT NULL,

    -- Who is talking. Opaque and caller-supplied for widget traffic (a browser
    -- session), null for dashboard testing. Deliberately not a user id: most
    -- conversations come from anonymous website visitors who have no account.
    visitor_id      VARCHAR(128),

    title           VARCHAR(200),
    status          VARCHAR(20)  NOT NULL DEFAULT 'ACTIVE',

    started_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    -- Denormalised from messages so the conversation list can sort by recency
    -- without aggregating over every message.
    last_message_at TIMESTAMPTZ,
    message_count   INTEGER      NOT NULL DEFAULT 0,

    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),

    CONSTRAINT fk_conversations_bot
        FOREIGN KEY (bot_id) REFERENCES bots (id) ON DELETE CASCADE,
    CONSTRAINT ck_conversations_status CHECK (status IN ('ACTIVE', 'CLOSED'))
);

-- The dashboard lists "this bot's conversations, most recent first".
CREATE INDEX ix_conversations_bot_recent
    ON conversations (bot_id, last_message_at DESC NULLS LAST);

CREATE INDEX ix_conversations_visitor ON conversations (bot_id, visitor_id)
    WHERE visitor_id IS NOT NULL;

CREATE TRIGGER trg_conversations_updated_at
    BEFORE UPDATE ON conversations
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();

-- ================================================================= messages --
CREATE TABLE messages (
    id              UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    conversation_id UUID        NOT NULL,
    role            VARCHAR(20) NOT NULL,
    content         TEXT        NOT NULL,

    -- Sources for an assistant message, as returned by the AI service. Stored
    -- as JSONB rather than a child table on purpose: a citation is a value
    -- owned entirely by one message, is always read with it, and is never
    -- queried on its own. A normalised table would add a join to every read
    -- and buy nothing.
    citations       JSONB       NOT NULL DEFAULT '[]',

    -- False when retrieval found nothing relevant. The answer is then a fixed
    -- refusal and the model was never called - worth recording, because "we
    -- could not answer" is the metric that tells you the corpus has a gap.
    grounded        BOOLEAN,
    model           VARCHAR(100),
    total_tokens    INTEGER,
    latency_ms      INTEGER,

    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT fk_messages_conversation
        FOREIGN KEY (conversation_id) REFERENCES conversations (id) ON DELETE CASCADE,
    CONSTRAINT ck_messages_role CHECK (role IN ('USER', 'ASSISTANT', 'SYSTEM')),
    CONSTRAINT ck_messages_content_not_blank CHECK (length(btrim(content)) > 0)
);

-- Transcripts are always read in order for one conversation.
CREATE INDEX ix_messages_conversation_created
    ON messages (conversation_id, created_at);

COMMENT ON COLUMN messages.citations IS
    'AI service citations: marker, document_id, filename, page_number, snippet, score.';
COMMENT ON COLUMN messages.grounded IS
    'Assistant messages only. False means retrieval found nothing and no model call was made.';
