"""Conversational query rewriting.

A stage in front of retrieval, not a change to it: the RAG pipeline receives a
string exactly as it always has, and neither knows nor cares whether that
string was rewritten.
"""

from .base import ConversationTurn, QueryRewriter, RewriteResult, Role
from .llm_rewriter import LLMQueryRewriter
from .passthrough import PassthroughQueryRewriter

__all__ = [
    "ConversationTurn",
    "LLMQueryRewriter",
    "PassthroughQueryRewriter",
    "QueryRewriter",
    "RewriteResult",
    "Role",
]
