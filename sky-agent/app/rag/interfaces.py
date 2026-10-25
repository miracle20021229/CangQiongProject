from __future__ import annotations
from collections.abc import Sequence
from dataclasses import dataclass
from typing import Protocol, runtime_checkable
from pydantic import BaseModel
from app.rag.schemas import DocumentChunk, RetrievedChunk


@dataclass(frozen=True, slots=True)
class RetrievalMetadata:
    """记录一次检索的运行信息，包括后端、命中数量、耗时及降级情况。

    只包含运行元数据，不包含查询原文或知识正文。
    """

    backend: str
    embedding_provider: str
    reranker_enabled: bool
    retrieval_count: int
    latency_seconds: float
    fallback_status: str = "none"
    hybrid: bool = False
    fusion_strategy: str = "none"
    dense_candidate_count: int = 0
    sparse_candidate_count: int = 0


@dataclass(frozen=True, slots=True)
class RetrievalResult:
    """封装当前请求中一次检索的完整结果。

    包含命中的知识片段、运行元数据，以及发生降级的组件。
    """

    chunks: tuple[RetrievedChunk, ...]
    metadata: RetrievalMetadata
    degraded_components: tuple[str, ...] = ()


class KnowledgeFilter(BaseModel):
    """限定知识检索可用的元数据筛选字段。

    支持按分类、文档 ID 和来源筛选，不提供任意 payload 过滤表达式的入口。
    """

    category: str | None = None
    document_id: str | None = None
    source: str | None = None


@runtime_checkable
class KnowledgeRetriever(Protocol):
    """供 Agent 调用的知识检索接口，统一不同检索后端的查询入口。"""

    def retrieve(self, query: str) -> RetrievalResult:
        """根据查询文本检索相关知识，返回知识片段及本次检索的运行信息。"""
        ...


@runtime_checkable
class KnowledgeIndexer(Protocol):
    """知识索引维护接口，用于写入文档片段和清空索引。"""

    def upsert(self, chunks: Sequence[DocumentChunk]) -> int:
        """新增或更新文档片段的索引，并返回本次写入的片段数量。

        已有相同 chunk_id 的片段会被更新。
        """
        ...

    def reset(self) -> None:
        """清空当前后端维护的文档片段及其索引，便于重新导入知识。"""
        ...


@runtime_checkable
class ManagedKnowledgeRetriever(Protocol):
    """支持资源释放的检索后端接口，用于统一管理连接等资源的生命周期。"""

    def close(self) -> None:
        """关闭检索后端，释放其持有的连接等资源。"""
        ...


@runtime_checkable
class ReadyKnowledgeRetriever(Protocol):
    """支持就绪检查的检索后端接口，用于判断后端能否提供检索服务。"""

    def is_ready(self) -> bool:
        """检查后端是否已具备提供检索服务的条件，就绪时返回 True。

        具体检查项由后端实现，例如连接状态、索引结构和知识数据是否可用。
        """
        ...
