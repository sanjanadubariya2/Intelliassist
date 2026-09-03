"""Operator commands.

Deliberately argparse rather than a CLI framework — three commands do not
justify a dependency.

    python -m app.cli config      show effective configuration (secrets masked)
    python -m app.cli eval        run the golden set against a knowledge base
    python -m app.cli reindex     rebuild the BM25 index for a knowledge base
"""

from __future__ import annotations

import argparse
import asyncio
import sys
import uuid
from pathlib import Path

from app.core.config import get_settings
from app.core.logging import configure_logging
from app.db.redis import create_redis_client, create_redis_pool
from app.db.session import create_engine, create_session_factory, session_scope
from app.repositories.chunk_repo import ChunkRepository
from app.services.evaluation.runner import EvaluationRunner, load_golden_set
from app.services.providers import build_embedding_provider, build_llm_provider
from app.services.rag_service import RagService
from app.services.reranking.noop import NoopReranker
from app.services.retrieval.bm25 import Bm25Retriever
from app.services.retrieval.dense import DenseRetriever
from app.services.retrieval.fusion import FusionRetriever
from app.services.retrieval.index.bm25_index import Bm25Index, Bm25IndexCache


def show_config() -> int:
    settings = get_settings()
    print("IntelliAssist configuration")
    print(f"  environment        {settings.app.env}")
    print(f"  embedding          {settings.embedding.provider} / {settings.embedding.model}")
    print(f"  dimension          {settings.embedding.dimension}")
    print(f"  llm                {settings.llm.provider} / {settings.llm.model}")
    print(f"  reranker           {'on' if settings.reranker.enabled else 'off'}")
    print(f"  dense/bm25 top-k   {settings.retrieval.dense_top_k}/{settings.retrieval.bm25_top_k}")
    print(f"  rrf k              {settings.retrieval.rrf_k}")
    print(f"  final top-n        {settings.retrieval.final_top_n}")
    # Presence only. The value must never reach a terminal or a log.
    print(f"  api key set        {settings.app.api_key is not None}")
    print(f"  gemini key set     {settings.gemini_api_key is not None}")
    return 0


async def _run_eval(knowledge_base_id: uuid.UUID, golden_path: Path, as_json: bool) -> int:
    settings = get_settings()
    engine = create_engine(settings.database)
    redis_pool = create_redis_pool(settings.redis)
    redis = create_redis_client(redis_pool)

    try:
        async with session_scope(create_session_factory(engine)) as session:
            embeddings = build_embedding_provider(settings, redis=redis)
            chunk_repo = ChunkRepository(session)
            service = RagService(
                settings=settings,
                retriever=FusionRetriever(
                    retrievers=[
                        DenseRetriever(chunk_repo=chunk_repo, embeddings=embeddings),
                        Bm25Retriever(chunk_repo=chunk_repo, cache=Bm25IndexCache(redis)),
                    ],
                    weights=[settings.retrieval.dense_weight, settings.retrieval.bm25_weight],
                    k=settings.retrieval.rrf_k,
                ),
                reranker=NoopReranker(),
                llm=build_llm_provider(settings),
            )
            report = await EvaluationRunner(service).run(
                load_golden_set(golden_path), knowledge_base_id=knowledge_base_id
            )
        print(report.to_json() if as_json else report.render())
        return 0
    finally:
        await redis.aclose()
        await redis_pool.disconnect()
        await engine.dispose()


async def _reindex(knowledge_base_id: uuid.UUID) -> int:
    settings = get_settings()
    engine = create_engine(settings.database)
    redis_pool = create_redis_pool(settings.redis)
    redis = create_redis_client(redis_pool)

    try:
        cache = Bm25IndexCache(redis, ttl_seconds=settings.retrieval.bm25_cache_ttl_seconds)
        await cache.invalidate(knowledge_base_id)

        async with session_scope(create_session_factory(engine)) as session:
            corpus = await ChunkRepository(session).iter_corpus_for_bm25(knowledge_base_id)

        index = Bm25Index.build(corpus)
        if index is None:
            print(f"No indexable chunks in knowledge base {knowledge_base_id}.")
            return 1

        await cache.store(knowledge_base_id, index)
        print(f"Rebuilt BM25 index: {len(index.chunk_ids)} chunks.")
        return 0
    finally:
        await redis.aclose()
        await redis_pool.disconnect()
        await engine.dispose()


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(prog="intelliassist", description=__doc__)
    subparsers = parser.add_subparsers(dest="command", required=True)

    subparsers.add_parser("config", help="show effective configuration")

    evaluate = subparsers.add_parser("eval", help="run the golden set")
    evaluate.add_argument("--knowledge-base-id", required=True, type=uuid.UUID)
    evaluate.add_argument("--golden-set", required=True, type=Path)
    evaluate.add_argument("--json", action="store_true", help="emit JSON for CI comparison")

    reindex = subparsers.add_parser("reindex", help="rebuild the BM25 index")
    reindex.add_argument("--knowledge-base-id", required=True, type=uuid.UUID)

    args = parser.parse_args(argv)
    settings = get_settings()
    configure_logging(level=settings.app.log_level, json_output=False)

    if args.command == "config":
        return show_config()
    if args.command == "eval":
        return asyncio.run(_run_eval(args.knowledge_base_id, args.golden_set, bool(args.json)))
    return asyncio.run(_reindex(args.knowledge_base_id))


if __name__ == "__main__":
    sys.exit(main())
