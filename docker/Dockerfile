# syntax=docker/dockerfile:1
#
# Two stages: dependencies resolve and install in the builder against a cache
# mount, then only the virtualenv and source cross into the runtime image.
# The base install deliberately omits the `rerank` extra — torch would add
# ~2.5 GB. Build with `--build-arg EXTRAS=rerank` when real reranking is wanted.

ARG PYTHON_VERSION=3.12

# ------------------------------------------------------------- builder ------
FROM ghcr.io/astral-sh/uv:python${PYTHON_VERSION}-bookworm-slim AS builder

ENV UV_COMPILE_BYTECODE=1 \
    UV_LINK_MODE=copy \
    UV_PYTHON_DOWNLOADS=never

WORKDIR /app

ARG EXTRAS=""

# Dependency layer, cached independently of source changes.
COPY pyproject.toml uv.lock* README.md ./
RUN --mount=type=cache,target=/root/.cache/uv \
    if [ -n "$EXTRAS" ]; then \
        uv sync --frozen --no-install-project --no-dev --extra "$EXTRAS"; \
    else \
        uv sync --frozen --no-install-project --no-dev; \
    fi

COPY app ./app
RUN --mount=type=cache,target=/root/.cache/uv \
    uv sync --frozen --no-dev

# ------------------------------------------------------------- runtime ------
FROM python:${PYTHON_VERSION}-slim-bookworm AS runtime

ENV PYTHONUNBUFFERED=1 \
    PYTHONDONTWRITEBYTECODE=1 \
    PATH="/app/.venv/bin:$PATH"

RUN groupadd --system --gid 1001 app \
    && useradd --system --uid 1001 --gid app --create-home app

WORKDIR /app

COPY --from=builder --chown=app:app /app/.venv /app/.venv
COPY --from=builder --chown=app:app /app/app /app/app
# alembic.ini is added here in Phase 1, alongside the first migration.

USER app
EXPOSE 8000

HEALTHCHECK --interval=15s --timeout=5s --start-period=20s --retries=3 \
    CMD python -c "import urllib.request,sys; sys.exit(0 if urllib.request.urlopen('http://127.0.0.1:8000/health/live', timeout=3).status==200 else 1)"

CMD ["uvicorn", "app.main:app", "--host", "0.0.0.0", "--port", "8000"]
