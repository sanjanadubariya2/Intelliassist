"""A rewriter that does nothing.

Used where the behaviour must be exactly the old one: rewriting switched off,
and in tests that assert the pipeline is unchanged for standalone questions.
Cheaper and clearer than threading `None` through the service and branching on
it at every call site.
"""

from __future__ import annotations

from .base import ConversationTurn, RewriteResult


class PassthroughQueryRewriter:
    """Returns the query it was given."""

    async def rewrite(
        self,
        *,
        query: str,
        history: list[ConversationTurn],
    ) -> RewriteResult:
        return RewriteResult(query, query, changed=False, reason="passthrough")
