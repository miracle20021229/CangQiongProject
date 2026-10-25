"""RAG 的装配入口，对外统一提供文档导入和知识检索。

build_knowledge_service 选择向量模型、重排器和检索后端；图节点只依赖统一接口。
导入链路：Markdown → 文档块 → 索引；查询链路：检索后端 → RetrievalResult。
"""

from collections.abc import Sequence
from pathlib import Path
from typing import Any
from app.core.config import Settings
from app.rag.backends.local import LocalKnowledgeBackend
from app.rag.backends.qdrant import QdrantKnowledgeBackend
from app.rag.config import RagBackend
from app.rag.embeddings import build_embedding_provider
from app.rag.ingestion.chunking import chunk_document
from app.rag.ingestion.loader import load_markdown_documents
from app.rag.interfaces import (
    KnowledgeIndexer,
    KnowledgeRetriever,
    ManagedKnowledgeRetriever,
    ReadyKnowledgeRetriever,
    RetrievalResult,
)
from app.rag.rerankers import DeterministicReranker
from app.rag.schemas import DocumentChunk, KnowledgeDocument


class KnowledgeService:
    def __init__(self, retriever: KnowledgeRetriever) -> None:
        self.retriever = retriever

    def ingest_directory(self, directory: Path, chunk_size: int = 800) -> int:
        documents = load_markdown_documents(directory)
        return self.ingest_documents(documents, chunk_size=chunk_size)

    def ingest_documents(
        self, documents: Sequence[KnowledgeDocument], *, chunk_size: int = 800
    ) -> int:
        # 长文先切片；每个片段保留来源，方便检索结果生成引用。
        chunks: list[DocumentChunk] = []
        for document in documents:
            chunks.extend(chunk_document(document, max_chars=chunk_size))
        if not isinstance(self.retriever, KnowledgeIndexer):
            raise RuntimeError("The configured knowledge backend does not support ingestion.")
        return self.retriever.upsert(chunks)

    def retrieve(self, query: str) -> RetrievalResult:
        # 此层统一接口，具体的混合检索在 local / qdrant 后端实现。
        return self.retriever.retrieve(query)

    def reset(self) -> None:
        if not isinstance(self.retriever, KnowledgeIndexer):
            raise RuntimeError("The configured knowledge backend does not support reset.")
        self.retriever.reset()

    def close(self) -> None:
        if isinstance(self.retriever, ManagedKnowledgeRetriever):
            self.retriever.close()

    def is_ready(self) -> bool:
        if isinstance(self.retriever, ReadyKnowledgeRetriever):
            return self.retriever.is_ready()
        return True

    def readiness_category(self) -> str:
        """Return a safe structural readiness category for operator health projection."""

        return str(getattr(self.retriever, "last_readiness_category", "ready"))

    @property
    def backend_type(self) -> str:
        return str(getattr(self.retriever, "backend_type", "unknown"))


def build_knowledge_service(
    settings: Settings, *, qdrant_client: Any | None = None
) -> KnowledgeService:
    # 向量模型和重排器独立配置，不等同于负责识别用户意图的聊天模型。
    # A retrieval attempt is the logical operation boundary. Network-backed embedding and
    # Qdrant calls must not have a native deadline longer than that attempt's budget.
    embedding_provider = build_embedding_provider(
        settings,
        timeout_seconds=min(settings.embedding_timeout_seconds, settings.retrieval_timeout_seconds),
        connect_timeout_seconds=min(
            settings.embedding_connect_timeout_seconds,
            settings.retrieval_timeout_seconds,
        ),
    )
    reranker = DeterministicReranker() if settings.reranker_enabled else None
    backend = RagBackend(settings.rag_backend)
    if backend is RagBackend.LOCAL:
        # 本地后端适合开发时读取随项目附带的知识文档。
        retriever: KnowledgeRetriever = LocalKnowledgeBackend(
            embedding_provider,
            reranker=reranker,
            dense_top_k=settings.rag_dense_top_k,
            sparse_top_k=settings.rag_sparse_top_k,
            rerank_candidates=settings.rag_rerank_candidates,
            final_context_count=settings.rag_final_context_count,
            reranker_timeout_seconds=settings.rag_reranker_timeout_seconds,
            reranker_enabled=settings.reranker_enabled,
        )
    else:
        # Qdrant 后端读取外部已建好的知识索引，运行时不在这里自动建库。
        retriever = QdrantKnowledgeBackend(
            url=settings.qdrant_url,
            collection_name=settings.qdrant_collection,
            embedding_provider=embedding_provider,
            reranker=reranker,
            reranker_enabled=settings.reranker_enabled,
            rerank_candidates=settings.rag_rerank_candidates,
            final_context_count=settings.rag_final_context_count,
            # Retrieval owns the logical attempt budget. Keep Qdrant's native request
            # deadline at or below it so a retry cannot overlap an abandoned call.
            timeout_seconds=min(
                settings.qdrant_timeout_seconds, settings.retrieval_timeout_seconds
            ),
            reranker_timeout_seconds=settings.rag_reranker_timeout_seconds,
            embedding_dimension=settings.embedding_dimension,
            embedding_model=settings.embedding_model,
            require_alias=True,
            schema_version=settings.qdrant_schema_version,
            chunking_version=settings.qdrant_chunking_version,
            dense_top_k=settings.rag_dense_top_k,
            sparse_top_k=settings.rag_sparse_top_k,
            client=qdrant_client,
        )
    service = KnowledgeService(retriever)
    if backend is RagBackend.LOCAL:
        # 只有本地模式自动导入；Qdrant 的导入入口是 scripts/rag_ingest.py。
        service.ingest_directory(Path(__file__).parents[2] / "knowledge", settings.rag_chunk_size)
    return service


def build_default_knowledge_service(settings: Settings) -> KnowledgeService:
    """Compatibility name retained for existing runtime callers."""
    return build_knowledge_service(settings)


__all__ = [
    "KnowledgeRetriever",
    "KnowledgeService",
    "RetrievalResult",
    "build_default_knowledge_service",
    "build_knowledge_service",
]
