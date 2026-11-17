-- Human handoff: who owns an escalated conversation, and when it changed hands.
--
-- The resolution column (V9) already says *that* a conversation needs a person.
-- This says *which* person, and records the two moments the inbox is measured
-- on: when it was escalated, and when it was resolved. Time-to-resolution is
-- the number a support lead actually asks for, and it cannot be reconstructed
-- after the fact from updated_at, which every edit moves.

ALTER TABLE conversations
    -- Kept nullable and SET NULL on delete: removing a teammate must not delete
    -- the conversations they were handling, only return them to the queue.
    ADD COLUMN assignee_id  UUID,
    ADD COLUMN escalated_at TIMESTAMPTZ,
    ADD COLUMN resolved_at  TIMESTAMPTZ;

ALTER TABLE conversations
    ADD CONSTRAINT fk_conversations_assignee
        FOREIGN KEY (assignee_id) REFERENCES users (id) ON DELETE SET NULL;

-- Conversations escalated before this migration have no recorded moment.
-- Their last activity is the best available estimate, and far better than
-- leaving them out of every inbox metric.
UPDATE conversations
   SET escalated_at = COALESCE(last_message_at, started_at)
 WHERE resolution = 'ESCALATED';

-- "My queue" is the inbox's default view.
CREATE INDEX ix_conversations_assignee
    ON conversations (assignee_id, last_message_at DESC)
    WHERE assignee_id IS NOT NULL;

-- The inbox lists everything that was ever handed off, across bots.
CREATE INDEX ix_conversations_escalated
    ON conversations (escalated_at DESC)
    WHERE escalated_at IS NOT NULL;

COMMENT ON COLUMN conversations.assignee_id IS
    'The teammate handling a handed-off conversation. Null means it is in the shared queue.';
COMMENT ON COLUMN conversations.escalated_at IS
    'When the conversation was first escalated to a person. Never cleared, so a resolved handoff stays countable.';
