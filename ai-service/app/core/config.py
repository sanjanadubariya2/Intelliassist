"""Application configuration.

This module is the *only* place in the codebase that reads the environment, and
the only place a model name, dimension, threshold or top-k appears as a literal.
Everything else takes a ``Settings`` instance.
"""

from __future__ import annotations

from functools import lru_cache
from typing import Literal

from pydantic import AliasChoices, BaseModel, Field, SecretStr, field_validator, model_validator
from pydantic_settings import BaseSettings, SettingsConfigDict

Environment = Literal["local", "test", "staging", "production"]

# pgvector stores up to 16,000 dimensions but can only build HNSW / IVFFlat
# indexes up to 2,000. Above that every query degrades to a sequential scan.
MAX_INDEXABLE_DIMENSION = 2000


class AppConfig(BaseModel):
    name: str = "intelliassist"
    version: str = "0.1.0"
    env: Environment = "local"
    debug: bool = False
    log_level: str = "INFO"
    log_json: bool = True

    # Shared secret for the X-API-Key header. Service-to-service only: this is
    # not user authentication and carries no identity.
    api_key: SecretStr | None = None

    cors_origins: list[str] = Field(default_factory=list)


class DatabaseConfig(BaseModel):
    url: str = "postgresql+asyncpg://intelliassist:intelliassist@localhost:5432/intelliassist"
    pool_size: int = 10
    max_overflow: int = 5
    pool_timeout: int = 30
    pool_recycle: int = 1800
    echo: bool = False


class RedisConfig(BaseModel):
    url: str = "redis://localhost:6379/0"
    max_connections: int = 20


class EmbeddingConfig(BaseModel):
    provider: Literal["gemini", "fake"] = "gemini"
    model: str = "gemini-embedding-001"

    # Matryoshka truncation. 768 keeps us well inside the HNSW index ceiling at
    # a quarter the storage of the native 3072.
    dimension: int = 768

    batch_size: int = 32
    max_retries: int = 3
    timeout_seconds: float = 30.0
    cache_ttl_seconds: int = 60 * 60 * 24 * 7

    # Gemini distinguishes the two sides of an asymmetric retrieval pair.
    task_type_document: str = "RETRIEVAL_DOCUMENT"
    task_type_query: str = "RETRIEVAL_QUERY"

    @field_validator("dimension")
    @classmethod
    def _within_index_ceiling(cls, value: int) -> int:
        if value > MAX_INDEXABLE_DIMENSION:
            raise ValueError(
                f"embedding dimension {value} exceeds {MAX_INDEXABLE_DIMENSION}; "
                "pgvector cannot build an HNSW index above that and every query "
                "would fall back to a sequential scan"
            )
        return value


class LLMConfig(BaseModel):
    provider: Literal["gemini", "fake"] = "gemini"
    model: str = "gemini-3.1-flash-lite"
    temperature: float = 0.2
    max_output_tokens: int = 1536
    timeout_seconds: float = 60.0

    # 4, not 2: measured roughly a 1-in-3 success rate during a Gemini demand
    # spike, where two retries still surfaces the failure to the caller more
    # often than not. Backoff is exponential with jitter.
    max_retries: int = 4


class ChunkingConfig(BaseModel):
    target_tokens: int = 500
    overlap_tokens: int = 80
    min_tokens: int = 40
    max_tokens: int = 900

    @model_validator(mode="after")
    def _overlap_fits(self) -> ChunkingConfig:
        if self.overlap_tokens >= self.target_tokens:
            raise ValueError("overlap_tokens must be smaller than target_tokens")
        if self.max_tokens < self.target_tokens:
            raise ValueError("max_tokens must be at least target_tokens")
        return self


class RetrievalConfig(BaseModel):
    dense_top_k: int = 50
    bm25_top_k: int = 50

    # RRF: score = sum over retrievers of weight / (rrf_k + rank).
    rrf_k: int = 60
    dense_weight: float = 1.0
    bm25_weight: float = 1.0

    rerank_candidates: int = 30
    final_top_n: int = 6

    # Cross-encoder logits, not probabilities. Provisional — Phase 9 tunes this
    # against the golden set.
    min_rerank_score: float = -2.0

    hnsw_ef_search: int = 100
    bm25_cache_ttl_seconds: int = 3600
    context_token_budget: int = 6000


class RerankerConfig(BaseModel):
    enabled: bool = True
    model: str = "cross-encoder/ms-marco-MiniLM-L-6-v2"
    device: Literal["auto", "cpu", "cuda"] = "auto"
    batch_size: int = 32
    max_length: int = 512


class IngestionConfig(BaseModel):
    max_file_bytes: int = 50 * 1024 * 1024
    allowed_mime_types: list[str] = Field(
        default_factory=lambda: [
            "application/pdf",
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            "text/plain",
            "text/markdown",
        ]
    )
    job_timeout_seconds: int = 1800
    max_job_retries: int = 2


class QueryRewriteConfig(BaseModel):
    """Turns a follow-up question into a standalone search query.

    Off by default in spirit if not in flag: with no history supplied the
    rewriter is skipped entirely, so an existing caller sees no change.
    """

    enabled: bool = True

    # How many prior messages to consider. Six is roughly three exchanges,
    # which covers the references people actually make ("it", "that one",
    # "what about part-timers") without paying to re-read a long conversation
    # on every question. Counted in messages, not exchanges, so an odd number
    # is meaningful.
    history_turns: int = Field(default=6, ge=0, le=40)

    # A rewrite is a small, cheap call and must not hold up an answer. If the
    # model is slow the original query is used instead.
    timeout_seconds: float = Field(default=8.0, gt=0)

    # Truncation guards. History arrives from another service and ultimately
    # from the public internet; without a cap a caller could push an arbitrary
    # payload into the prompt.
    max_message_chars: int = Field(default=600, ge=100, le=4000)
    max_query_chars: int = Field(default=400, ge=50, le=2000)


class Settings(BaseSettings):
    model_config = SettingsConfigDict(
        env_prefix="INTELLIASSIST_",
        env_nested_delimiter="__",
        env_file=".env",
        env_file_encoding="utf-8",
        extra="ignore",
    )

    app: AppConfig = Field(default_factory=AppConfig)
    database: DatabaseConfig = Field(default_factory=DatabaseConfig)
    redis: RedisConfig = Field(default_factory=RedisConfig)
    embedding: EmbeddingConfig = Field(default_factory=EmbeddingConfig)
    llm: LLMConfig = Field(default_factory=LLMConfig)
    chunking: ChunkingConfig = Field(default_factory=ChunkingConfig)
    retrieval: RetrievalConfig = Field(default_factory=RetrievalConfig)
    reranker: RerankerConfig = Field(default_factory=RerankerConfig)
    ingestion: IngestionConfig = Field(default_factory=IngestionConfig)
    query_rewrite: QueryRewriteConfig = Field(default_factory=QueryRewriteConfig)

    # Accepted from the vendor-conventional names too, so an existing shell
    # export works without renaming.
    gemini_api_key: SecretStr | None = Field(
        default=None,
        validation_alias=AliasChoices(
            "INTELLIASSIST_GEMINI_API_KEY",
            "GEMINI_API_KEY",
            "GOOGLE_API_KEY",
        ),
    )

    @model_validator(mode="after")
    def _deployed_environments_require_secrets(self) -> Settings:
        if self.app.env in ("staging", "production"):
            if self.app.api_key is None:
                raise ValueError(
                    "INTELLIASSIST_APP__API_KEY is required when APP__ENV is staging or production"
                )
            if self.llm.provider == "gemini" and self.gemini_api_key is None:
                raise ValueError("GEMINI_API_KEY is required when LLM__PROVIDER is gemini")
        return self

    @property
    def is_local(self) -> bool:
        return self.app.env in ("local", "test")


@lru_cache(maxsize=1)
def get_settings() -> Settings:
    """Process-wide settings singleton. Cleared in tests via ``cache_clear()``."""
    return Settings()
