"""The query-rewriting contract.

A follow-up question is not a search query. "What about part-time staff?"
carries almost no retrievable signal on its own: the embedding lands nowhere
near the leave policy, and BM25 matches on "staff" and little else.

The obvious fix — gluing the last few messages onto the front of the query —
makes retrieval worse, not better. An embedding is a single point in space, so
appending a previous turn drags that point toward the *old* topic; BM25 then
scores every stopword the history contributed. The query stops describing what
the user is asking now.

So the history is used to *rewrite* the question into a standalone one, and
only that rewritten string reaches retrieval.
"""

from __future__ import annotations

from dataclasses import dataclass
from typing import Literal, Protocol, runtime_checkable

Role = Literal["user", "assistant"]


@dataclass(frozen=True, slots=True)
class ConversationTurn:
    """One prior message. Content is untrusted text, never an instruction."""

    role: Role
    content: str


@dataclass(frozen=True, slots=True)
class RewriteResult:
    """What the rewriter produced, and whether it actually did anything.

    `original` is kept because it is what the user typed, and that — not our
    reconstruction of it — is what gets persisted and shown back to them.
    """

    original: str
    rewritten: str
    #: False when the rewriter was skipped, declined, or failed and fell back.
    changed: bool
    #: Why it was skipped or failed. Internal diagnostics; never sent to a client.
    reason: str | None = None

    @property
    def query(self) -> str:
        """The string retrieval should actually use."""
        return self.rewritten


@runtime_checkable
class QueryRewriter(Protocol):
    """Produces a standalone search query from a question plus its history.

    Implementations must never raise. A rewriter is an enhancement to
    retrieval, and an enhancement that can fail the request is a liability —
    the caller is entitled to assume it always returns something usable.
    """

    async def rewrite(
        self,
        *,
        query: str,
        history: list[ConversationTurn],
    ) -> RewriteResult: ...
