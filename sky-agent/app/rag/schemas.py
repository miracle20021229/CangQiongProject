from enum import StrEnum
from typing import Any
from pydantic import BaseModel, Field, model_validator


class KnowledgeDocument(BaseModel):
    document_id: str  # 文档标识，当前加载器使用不含扩展名的文件名。
    title: str  # 原始知识文档的标题。
    category: str  # 文档所属业务分类，如退款、配送等。
    source: str  # 文档来源位置，当前加载器记录文件路径。
    content: str  # 原始文档的完整正文，供后续切分。


class DocumentChunk(BaseModel):
    chunk_id: str  # 切片标识，由文档 ID、章节标识和切片序号组成。
    document_id: str  # 所属原始文档的标识，用于关联完整文档。
    title: str  # 所属原始文档的标题。
    category: str  # 继承原始文档的业务分类。
    section: str  # 切片所属章节的标识，用于定位和引用。
    source: str  # 原始文档的来源位置，便于追溯。
    chunk_index: int  # 切分时分配的切片序号，用于区分同一文档中的片段。
    content: str  # 当前切片的正文，用于建立检索索引。


class RetrievedChunk(BaseModel):
    chunk_id: str  # 检索命中的切片标识。
    document_id: str  # 命中切片所属的原始文档标识。
    title: str  # 命中切片所属文档的标题。
    category: str  # 命中切片所属的业务分类。
    section: str  # 命中切片的章节标识，参与生成 citation_id。
    source: str  # 原始文档的来源位置，供回答引用和追溯。
    content: str  # 命中切片的正文，作为生成回答的候选证据。
    score: float  # 检索阶段的相关性得分，用于候选切片排序。
    rerank_score: float | None = None  # 重排阶段的相关性得分；None 表示尚无可用的重排分数。

    # 计算属性：由文档和章节拼出引用标识，通过对象.citation_id 读取。
    @property
    def citation_id(self) -> str:
        return f"{self.document_id}#{self.section}"


class Citation(BaseModel):
    """Bounded evidence reference used by grounded answer generation."""

    citation_id: str  # 回答中的引用标识，通常为“文档 ID#章节标识”。
    document_id: str  # 被引用证据所属的原始文档标识。
    title: str  # 被引用文档的标题，供展示引用来源。
    chunk_id: str  # 提供本条引用证据的具体切片标识。
    source: str  # 被引用文档的来源位置，便于追溯原文。
    relevance_score: float = Field(ge=0.0)  # 引用证据的相关性得分，必须大于或等于 0。
    quoted_excerpt: str = Field(min_length=1, max_length=500)  # 本条引用的证据摘录，长度限制为 1～500 个字符。

    @model_validator(mode="before")
    @classmethod
    def preserve_legacy_citation_projection(cls, value: Any) -> Any:
        """Accept pre-upgrade checkpoint citations without inventing evidence."""

        if not isinstance(value, dict):
            return value
        payload = dict(value)
        citation_id = str(payload.get("citation_id", "unknown"))
        payload.setdefault("document_id", citation_id.split("#", maxsplit=1)[0])
        payload.setdefault("chunk_id", citation_id)
        payload.setdefault("relevance_score", 0.0)
        payload.setdefault("quoted_excerpt", "Excerpt unavailable from legacy citation projection.")
        return payload


class AnswerGroundingStatus(StrEnum):
    PASS = "pass"
    CONFLICT = "conflict"
    INSUFFICIENT_EVIDENCE = "insufficient_evidence"
    REJECTED = "rejected"


class GroundingValidationResult(BaseModel):
    citation_coverage: float = Field(ge=0.0, le=1.0)
    accepted: bool
    unsupported_claim_count: int = Field(ge=0)
    reasons: list[str] = Field(default_factory=list)


class GroundedAnswer(BaseModel):
    answer: str
    citations: list[Citation]
    confidence: float = Field(ge=0.0, le=1.0)
    grounded_claims: list[str] = Field(default_factory=list)
    unsupported_claims: list[str] = Field(default_factory=list)
    source_count: int = Field(ge=0)
    status: AnswerGroundingStatus
    validation: GroundingValidationResult
    grounded: bool = False
