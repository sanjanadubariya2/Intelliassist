-- Daily usage per bot.
--
-- A rollup, not an event log. The questions a customer actually asks are
-- "how busy was my bot this month" and "how often could it not answer", and
-- both are answered by counters. Storing one row per message and aggregating on
-- read would cost far more and answer nothing extra - the messages table
-- already holds the detail if anyone needs it.

CREATE TABLE usage_records (
    id                 UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    bot_id             UUID        NOT NULL,

    -- Day bucket in UTC. Coarse on purpose: an hourly grain would multiply the
    -- row count by 24 to support a chart nobody has asked for.
    usage_date         DATE        NOT NULL,

    conversation_count INTEGER     NOT NULL DEFAULT 0,
    message_count      INTEGER     NOT NULL DEFAULT 0,

    -- Answers where retrieval found nothing relevant. The single most useful
    -- number here: it is the direct measure of gaps in a customer's corpus.
    ungrounded_count   INTEGER     NOT NULL DEFAULT 0,

    -- Only the synchronous endpoint reports token usage; streamed answers
    -- record zero, so this is a floor rather than an exact figure.
    token_count        BIGINT      NOT NULL DEFAULT 0,

    created_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at         TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT fk_usage_bot FOREIGN KEY (bot_id) REFERENCES bots (id) ON DELETE CASCADE,

    -- One row per bot per day. This is what makes the upsert below possible,
    -- and what stops concurrent requests creating duplicate buckets.
    CONSTRAINT uq_usage_bot_date UNIQUE (bot_id, usage_date),

    CONSTRAINT ck_usage_counts_non_negative CHECK (
        conversation_count >= 0 AND message_count >= 0
        AND ungrounded_count >= 0 AND token_count >= 0
    )
);

-- Every query is "this bot, over this date range".
CREATE INDEX ix_usage_bot_date ON usage_records (bot_id, usage_date DESC);

CREATE TRIGGER trg_usage_records_updated_at
    BEFORE UPDATE ON usage_records
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();

COMMENT ON COLUMN usage_records.ungrounded_count IS
    'Answers where retrieval found nothing. A rising number means the corpus has a gap.';
COMMENT ON COLUMN usage_records.token_count IS
    'A floor: streamed answers do not report token usage, so they contribute zero.';
