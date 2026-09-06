"""The Gemini-backed rewriter.

Uses the existing `LLMProvider` protocol rather than a second client: the
retries, timeouts and transient-error classification in `GeminiLLMProvider`
were hard-won, and a parallel client would have to learn them again.
"""

from __future__ import annotations

import asyncio
import re

import structlog

from app.core.config import QueryRewriteConfig
from app.services.llm.base import LLMProvider

from .base import ConversationTurn, RewriteResult
from .prompts import SYSTEM_PROMPT, build_rewrite_prompt

logger = structlog.get_logger(__name__)

#: A model that ignores "reply with the query alone" usually does so in one of
#: a few predictable ways. Stripping them is cheaper than another round trip.
_PREFIXES = re.compile(
    r"^\s*(rewritten(\s+standalone)?(\s+search)?\s+query\s*:|"
    r"standalone\s+query\s*:|query\s*:|answer\s*:)\s*",
    re.IGNORECASE,
)

#: A sentence boundary: terminator, space, capital. A search query is one
#: sentence; prose is several, and prose is what a model produces once it has
#: been talked into answering instead of rewriting.
_SENTENCE_BREAK = re.compile(r"[.!?]\s+[A-Z]")


class LLMQueryRewriter:
    """Rewrites a follow-up into a standalone query, or returns it unchanged.

    Never raises. Every failure path — disabled, no history, a model error, a
    timeout, an empty or implausible completion — resolves to the original
    query, because retrieval on a slightly worse query beats no answer at all.
    """

    def __init__(self, *, llm: LLMProvider, config: QueryRewriteConfig) -> None:
        self._llm = llm
        self._config = config

    async def rewrite(
        self,
        *,
        query: str,
        history: list[ConversationTurn],
    ) -> RewriteResult:
        if not self._config.enabled:
            return RewriteResult(query, query, changed=False, reason="disabled")

        window = self._window(history)
        if not window:
            # Nothing to resolve a reference against. The first question in a
            # conversation is already standalone by definition.
            return RewriteResult(query, query, changed=False, reason="no_history")

        try:
            completion = await asyncio.wait_for(
                self._llm.complete(
                    system=SYSTEM_PROMPT,
                    prompt=build_rewrite_prompt(
                        question=query,
                        history=window,
                        max_message_chars=self._config.max_message_chars,
                    ),
                ),
                timeout=self._config.timeout_seconds,
            )
        except TimeoutError:
            logger.warning("query_rewrite_timeout", timeout=self._config.timeout_seconds)
            return RewriteResult(query, query, changed=False, reason="timeout")
        except Exception as exc:
            # Any provider failure at all. This stage is an optimisation; it
            # does not get to break a request the user is waiting on.
            logger.warning("query_rewrite_failed", error=type(exc).__name__)
            return RewriteResult(query, query, changed=False, reason="error")

        rewritten = self._clean(completion.text)
        if not self._plausible(rewritten, query):
            return RewriteResult(query, query, changed=False, reason="implausible")

        changed = rewritten.casefold() != query.strip().casefold()
        logger.info(
            "query_rewritten",
            changed=changed,
            history_turns=len(window),
            # Lengths, not text: the query is user content and does not belong
            # in logs by default.
            original_chars=len(query),
            rewritten_chars=len(rewritten),
        )
        return RewriteResult(query, rewritten, changed=changed)

    def _window(self, history: list[ConversationTurn]) -> list[ConversationTurn]:
        """The most recent N messages, oldest first.

        Recent turns are the ones a pronoun refers to; older ones cost tokens
        and add topics the question has already moved on from.
        """
        turns = self._config.history_turns
        if turns <= 0:
            return []
        return [turn for turn in history if turn.content.strip()][-turns:]

    @staticmethod
    def _clean(text: str) -> str:
        """Strips the wrappers a model adds despite being asked not to."""
        cleaned = text.strip()

        # Occasionally the whole reply is fenced, or a leading label survives.
        if cleaned.startswith("```"):
            cleaned = cleaned.strip("`").strip()
            if "\n" in cleaned:
                first, rest = cleaned.split("\n", 1)
                # Drop a language tag like ```text
                cleaned = rest.strip() if first.isalpha() else cleaned

        cleaned = _PREFIXES.sub("", cleaned)

        # A single line. Anything after the first is commentary we did not ask
        # for, and folding it into the query would poison retrieval.
        cleaned = cleaned.splitlines()[0].strip() if cleaned else ""

        if len(cleaned) >= 2 and cleaned[0] == cleaned[-1] and cleaned[0] in "\"'":
            cleaned = cleaned[1:-1].strip()

        return cleaned

    def _plausible(self, rewritten: str, original: str) -> bool:
        """Rejects a rewrite that clearly is not one.

        The guard that matters is length. A model that has been talked into
        answering, or into obeying an instruction hidden in the history,
        produces prose — and prose is both a bad search query and a sign the
        rules were not followed. Falling back costs one mediocre retrieval;
        forwarding it would send the injected text into the pipeline.
        """
        if not rewritten:
            return False

        if len(rewritten) > self._config.max_query_chars:
            logger.warning(
                "query_rewrite_rejected",
                reason="too_long",
                chars=len(rewritten),
                limit=self._config.max_query_chars,
            )
            return False

        # The strongest signal, and the one length alone misses: a query is a
        # single sentence. An obeyed injection reads as a paragraph, and a
        # short one can still slip under any character cap loose enough to
        # allow a legitimate rewrite.
        if _SENTENCE_BREAK.search(rewritten):
            logger.warning("query_rewrite_rejected", reason="multi_sentence")
            return False

        # A rewrite resolves references; it does not expand a short question
        # into an essay. The floor keeps short standalone queries legal.
        if len(rewritten) > max(200, len(original) * 4):
            logger.warning("query_rewrite_rejected", reason="disproportionate")
            return False

        return True
