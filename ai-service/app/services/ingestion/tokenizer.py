"""Token counting.

One tokenizer, used by the chunker and by context budgeting, so the token counts
stored at ingestion time mean the same thing as the budget applied at query time.

tiktoken is an OpenAI tokenizer and Gemini's differs, so counts here are an
approximation — consistently within about 10% for English prose. That is
accurate enough for chunk sizing and context budgeting, both of which have slack
built in, and it avoids a network call per chunk just to count tokens.
"""

from __future__ import annotations

import functools

import tiktoken

_ENCODING_NAME = "cl100k_base"


@functools.lru_cache(maxsize=1)
def _encoding() -> tiktoken.Encoding:
    return tiktoken.get_encoding(_ENCODING_NAME)


def count_tokens(text: str) -> int:
    if not text:
        return 0
    return len(_encoding().encode(text, disallowed_special=()))


def encode(text: str) -> list[int]:
    return _encoding().encode(text, disallowed_special=())


def decode(tokens: list[int]) -> str:
    return _encoding().decode(tokens)
