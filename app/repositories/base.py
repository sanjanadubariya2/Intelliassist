"""Shared repository helpers."""

from __future__ import annotations

from typing import Any, cast

from sqlalchemy import CursorResult, Result


def affected_rows(result: Result[Any]) -> int:
    """Rows touched by a DML statement.

    ``Session.execute`` is typed as returning ``Result``, which has no
    ``rowcount`` — only the ``CursorResult`` that DML actually produces does.
    The cast is narrowing to what the statement really returned, and keeping it
    in one place stops it from being repeated at every delete.
    """
    return cast("CursorResult[Any]", result).rowcount
