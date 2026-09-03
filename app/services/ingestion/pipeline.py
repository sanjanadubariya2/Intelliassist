"""Ingestion orchestration: bytes in, indexed chunks out.

Runs on a worker, never in a request. Status is advanced at each stage so a
caller polling the document endpoint sees real progress rather than a spinner,
and so a failure names the stage it happened in.
"""

from __future__ import annotations

import uuid
from dataclasses import dataclass

from app.core.config import Settings
from app.core.exceptions import ExtractionError, IntelliAssistError
from app.core.logging import get_logger
from app.models.document import Document, DocumentStatus
from app.repositories.chunk_repo import ChunkRepository
from app.repositories.document_repo import DocumentRepository
from app.repositories.knowledge_base_repo import KnowledgeBaseRepository
from app.services.embeddings.base import EmbeddingProvider, EmbeddingTask
from app.services.ingestion.chunker import Chunk, Chunker
from app.services.ingestion.cleaner import clean_document
from app.services.ingestion.extractors.registry import ExtractorRegistry

logger = get_logger(__name__)


@dataclass(frozen=True, slots=True)
class IngestionResult:
    document_id: uuid.UUID
    chunk_count: int
    page_count: int | None
    status: DocumentStatus


class IngestionPipeline:
    def __init__(
        self,
        *,
        settings: Settings,
        embeddings: EmbeddingProvider,
        document_repo: DocumentRepository,
        chunk_repo: ChunkRepository,
        knowledge_base_repo: KnowledgeBaseRepository,
        registry: ExtractorRegistry | None = None,
    ) -> None:
        self._settings = settings
        self._embeddings = embeddings
        self._documents = document_repo
        self._chunks = chunk_repo
        self._knowledge_bases = knowledge_base_repo
        self._registry = registry or ExtractorRegistry()
        self._chunker = Chunker(settings.chunking)

    async def ingest(self, *, document_id: uuid.UUID, data: bytes) -> IngestionResult:
        document = await self._documents.get(document_id)
        if document is None:
            raise IntelliAssistError(f"Document {document_id} vanished before ingestion.")

        try:
            return await self._run(document_id=document_id, data=data, document=document)
        except IntelliAssistError as exc:
            await self._fail(document_id, exc.message)
            raise
        except Exception as exc:
            await self._fail(document_id, f"{type(exc).__name__}: {exc}")
            raise

    async def _run(
        self, *, document_id: uuid.UUID, data: bytes, document: Document
    ) -> IngestionResult:
        knowledge_base_id = document.knowledge_base_id
        mime_type = document.mime_type
        filename = document.filename

        knowledge_base = await self._knowledge_bases.get(knowledge_base_id)
        if knowledge_base is None:
            raise IntelliAssistError(f"Knowledge base {knowledge_base_id} no longer exists.")

        # A knowledge base is bound to the model that built it. Indexing with a
        # different one would put incomparable vectors in the same index, and
        # every subsequent search would silently degrade.
        if knowledge_base.embedding_model != self._embeddings.model:
            raise IntelliAssistError(
                "Knowledge base was built with a different embedding model.",
                details={
                    "knowledge_base_model": knowledge_base.embedding_model,
                    "configured_model": self._embeddings.model,
                },
            )

        # -- extract ---------------------------------------------------------
        await self._documents.set_status(document_id, DocumentStatus.EXTRACTING)
        extractor = self._registry.get(mime_type)
        extracted = extractor.extract(data, filename=filename)

        if extracted.is_empty:
            raise ExtractionError(
                "No extractable text found. A scanned PDF needs OCR, which this "
                "service does not perform.",
                details={"filename": filename, "mime_type": mime_type},
            )

        cleaned = clean_document(extracted)

        # -- chunk -----------------------------------------------------------
        await self._documents.set_status(document_id, DocumentStatus.CHUNKING)
        chunks = self._chunker.chunk_document(cleaned)
        if not chunks:
            raise ExtractionError("Document produced no chunks after cleaning.")

        # -- embed -----------------------------------------------------------
        await self._documents.set_status(document_id, DocumentStatus.EMBEDDING)
        vectors = await self._embeddings.embed(
            [chunk.text for chunk in chunks], task=EmbeddingTask.DOCUMENT
        )
        self._validate_vectors(vectors, expected_count=len(chunks))

        # -- persist ---------------------------------------------------------
        # Replace rather than append, so re-ingesting a document cannot leave
        # orphaned chunks from a previous attempt alongside the new ones.
        await self._chunks.delete_by_document(document_id)
        await self._chunks.bulk_insert(
            [
                self._row(
                    chunk=chunk,
                    vector=vector,
                    document_id=document_id,
                    knowledge_base_id=knowledge_base_id,
                )
                for chunk, vector in zip(chunks, vectors, strict=True)
            ]
        )

        await self._documents.set_status(
            document_id,
            DocumentStatus.INDEXED,
            chunk_count=len(chunks),
            page_count=cleaned.page_count,
        )

        logger.info(
            "document_indexed",
            document_id=str(document_id),
            knowledge_base_id=str(knowledge_base_id),
            chunks=len(chunks),
            pages=cleaned.page_count,
        )
        return IngestionResult(
            document_id=document_id,
            chunk_count=len(chunks),
            page_count=cleaned.page_count,
            status=DocumentStatus.INDEXED,
        )

    def _validate_vectors(self, vectors: list[list[float]], *, expected_count: int) -> None:
        """Check width before persistence, not after.

        The column is ``vector(768)``; a wrong width would be rejected by
        Postgres with an opaque error halfway through a bulk insert. Failing
        here names the actual problem.
        """
        if len(vectors) != expected_count:
            raise IntelliAssistError(
                "Embedding provider returned the wrong number of vectors.",
                details={"expected": expected_count, "received": len(vectors)},
            )

        expected_dim = self._settings.embedding.dimension
        for position, vector in enumerate(vectors):
            if len(vector) != expected_dim:
                raise IntelliAssistError(
                    "Embedding provider returned a vector of the wrong width.",
                    details={
                        "expected_dimension": expected_dim,
                        "received_dimension": len(vector),
                        "chunk_index": position,
                    },
                )

    @staticmethod
    def _row(
        *,
        chunk: Chunk,
        vector: list[float],
        document_id: uuid.UUID,
        knowledge_base_id: uuid.UUID,
    ) -> dict[str, object]:
        return {
            "id": uuid.uuid4(),
            "knowledge_base_id": knowledge_base_id,
            "document_id": document_id,
            "chunk_index": chunk.index,
            "text": chunk.text,
            "token_count": chunk.token_count,
            "embedding": vector,
            "page_number": chunk.page_number,
            "char_start": chunk.char_start,
            "char_end": chunk.char_end,
            "content_hash": chunk.content_hash,
            "metadata": chunk.meta,
        }

    async def _fail(self, document_id: uuid.UUID, message: str) -> None:
        logger.error("ingestion_failed", document_id=str(document_id), error=message)
        await self._documents.set_status(document_id, DocumentStatus.FAILED, error_message=message)
