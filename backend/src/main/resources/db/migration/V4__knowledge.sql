-- Knowledge bases and documents.
--
-- Both tables hold *references* to the AI service, not copies of its work.
-- Chunks, embeddings and vector indexes live only on the Python side; storing
-- them here as well would create two sources of truth that inevitably drift.

-- ========================================================= knowledge_bases --
CREATE TABLE knowledge_bases (
    id                  UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    bot_id              UUID         NOT NULL,
    name                VARCHAR(200) NOT NULL,

    -- The AI service's own knowledge base id. This is the join between the two
    -- systems, and losing it would orphan a corpus that we can no longer query
    -- or delete.
    ai_knowledge_base_id UUID        NOT NULL,

    -- What we sent as external_ref, kept so the link can be re-established by
    -- listing the AI service's knowledge bases if this row is ever lost.
    external_ref        VARCHAR(255) NOT NULL,

    created_at          TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ  NOT NULL DEFAULT now(),

    CONSTRAINT fk_knowledge_bases_bot
        FOREIGN KEY (bot_id) REFERENCES bots (id) ON DELETE CASCADE,

    -- One knowledge base per bot. The document endpoints are addressed by bot
    -- (POST /bots/{botId}/documents) and name no knowledge base, so a second
    -- one would make "which corpus does this upload go to" ambiguous.
    CONSTRAINT uq_knowledge_bases_bot UNIQUE (bot_id),
    CONSTRAINT uq_knowledge_bases_ai_id UNIQUE (ai_knowledge_base_id)
);

CREATE TRIGGER trg_knowledge_bases_updated_at
    BEFORE UPDATE ON knowledge_bases
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();

-- ================================================================ documents --
CREATE TABLE documents (
    id                UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    knowledge_base_id UUID         NOT NULL,

    -- Sanitised for storage and display; original_filename is what the user
    -- uploaded and is shown back to them.
    filename          VARCHAR(255) NOT NULL,
    original_filename VARCHAR(255) NOT NULL,
    content_type      VARCHAR(100) NOT NULL,
    size_bytes        BIGINT       NOT NULL,

    -- Null only in the window between our row being written and the AI service
    -- accepting the upload. A row that stays null means the handoff failed.
    ai_document_id    UUID,

    status            VARCHAR(20)  NOT NULL DEFAULT 'PENDING',
    error_message     TEXT,
    chunk_count       INTEGER,
    page_count        INTEGER,

    created_at        TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ  NOT NULL DEFAULT now(),

    CONSTRAINT fk_documents_knowledge_base
        FOREIGN KEY (knowledge_base_id) REFERENCES knowledge_bases (id) ON DELETE CASCADE,

    CONSTRAINT uq_documents_ai_id UNIQUE (ai_document_id),

    -- Mirrors the AI service's lifecycle so the dashboard can show real
    -- progress rather than a spinner, plus FAILED for our own handoff errors.
    CONSTRAINT ck_documents_status CHECK (
        status IN ('PENDING', 'EXTRACTING', 'CHUNKING', 'EMBEDDING', 'INDEXED', 'FAILED')
    ),
    CONSTRAINT ck_documents_size_positive CHECK (size_bytes > 0)
);

CREATE INDEX ix_documents_knowledge_base_created
    ON documents (knowledge_base_id, created_at DESC);

-- Status polling asks "which documents are not finished yet", so the partial
-- index stays small no matter how large the corpus grows.
CREATE INDEX ix_documents_unfinished
    ON documents (knowledge_base_id)
    WHERE status NOT IN ('INDEXED', 'FAILED');

CREATE TRIGGER trg_documents_updated_at
    BEFORE UPDATE ON documents
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();

COMMENT ON COLUMN documents.ai_document_id IS
    'The AI service document id. Citations reference it, and it is how status is polled.';
