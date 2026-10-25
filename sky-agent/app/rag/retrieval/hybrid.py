"""本地混合检索器：向量召回 + BM25 关键词召回 → 加权融合 → 可选重排 → 截取最终上下文。

数据全部保存在进程内存中，适合开发和测试；生产环境的同类逻辑由 Qdrant 后端实现。
"""
import math
import time
from collections import Counter
from collections.abc import Sequence
from app.observability.metrics import get_metrics
from app.observability.tracing import span
from app.rag.embeddings import EmbeddingProvider
from app.rag.interfaces import RetrievalMetadata, RetrievalResult
from app.rag.reranking.service import DeterministicReranker, Reranker
from app.rag.retrieval.lexical import _tokens
from app.rag.schemas import DocumentChunk, RetrievedChunk


class HybridRetriever:
    """同时实现 KnowledgeRetriever（retrieve）和 KnowledgeIndexer（upsert / reset）的内存检索器。"""
    def __init__(
        self,
        embedding_provider: EmbeddingProvider,
        reranker: Reranker | None = None,
        *,
        dense_top_k: int = 8,
        sparse_top_k: int = 8,
        rerank_candidates: int = 12,
        final_context_count: int = 4,
        reranker_timeout_seconds: float = 3.0,
        reranker_enabled: bool = True,
    ) -> None:
        self.embedding_provider = embedding_provider
        # 未传入重排器时使用确定性重排器兜底；是否真正执行重排由 reranker_enabled 决定。
        self.reranker = reranker or DeterministicReranker()
        self.dense_top_k = dense_top_k  #向量召回 dense_top_k
        self.sparse_top_k = sparse_top_k  #关键词召回 sparse_top_k
        self.rerank_candidates = rerank_candidates  #两路合并后选取的最多的向量数
        self.final_context_count = final_context_count  #最终重排后生成的向量数量
        
        # 仅保存配置；本类不会用外部计时器中断同步重排，见 retrieve 中的说明。
        self.reranker_timeout_seconds = reranker_timeout_seconds
        self.reranker_enabled = reranker_enabled
        self.backend_type = "local"

        self._chunks: dict[str, DocumentChunk] = {}  #文档片段(正文)
        self._vectors: dict[str, list[float]] = {}  #匹配的向量

    @property
    def chunk_count(self) -> int:
        """当前索引中的片段数量。"""
        return len(self._chunks)

    def upsert(self, chunks: Sequence[DocumentChunk]) -> int:
        """批量向量化片段并写入内存索引，相同 chunk_id 会覆盖旧数据，返回写入数量。"""
        # 1.一次性批量生成所有片段正文的向量，减少模型调用次数。
        vectors = self.embedding_provider.embed_documents([chunk.content for chunk in chunks])
        # 2.片段与向量按顺序一一对应；strict=True 保证数量不一致时直接报错，避免错位写入。
        for chunk, vector in zip(chunks, vectors, strict=True):
            self._chunks[chunk.chunk_id] = chunk
            self._vectors[chunk.chunk_id] = vector
        return len(chunks)

    def reset(self) -> None:
        """清空片段和向量索引，便于重新导入知识。"""
        self._chunks.clear()
        self._vectors.clear()

    def retrieve(self, query: str) -> RetrievalResult:
        """执行一次混合检索，返回最终片段及运行元数据，并记录链路追踪和指标。"""
        # 1.初始化计时、状态与统计变量；status 供 finally 中的耗时指标区分成功和失败。
        started = time.perf_counter()
        status = "ok"
        degraded_components: list[str] = []
        dense_candidate_count = 0
        sparse_candidate_count = 0
        attributes = {
            "rag.backend": self.backend_type,
            "rag.embedding_provider": self.embedding_provider.provider_type,
            "rag.reranker_enabled": self.reranker_enabled,
        }
        # 2.整个检索包在 rag.retrieve 追踪区间内，每个阶段再开子区间，便于定位耗时。
        with span("rag.retrieve", attributes=attributes) as retrieval_span:
            try:
                # 3.索引为空时直接返回空结果，不调用向量模型。
                if not self._chunks:
                    results: list[RetrievedChunk] = []
                else:
                    # 4.把用户问题转为向量。
                    with span("rag.embed_query"):
                        query_vector = self.embedding_provider.embed_query(query)
                    # 5.向量召回：与所有片段计算相似度（暴力遍历），降序后取前 dense_top_k 条。
                    with span("rag.dense_search") as dense_span:
                        dense = sorted(
                            (
                                (chunk_id, _cosine(query_vector, vector))
                                for chunk_id, vector in self._vectors.items()
                            ),
                            key=lambda item: item[1],
                            reverse=True,
                        )[: self.dense_top_k]
                        dense_candidate_count = len(dense)
                        dense_span.set_attribute("rag.dense_candidates", len(dense))
                    # 6.关键词召回：BM25 打分并降序，取前 sparse_top_k 条。
                    with span("rag.sparse_search") as sparse_span:
                        sparse = self._bm25(query)[: self.sparse_top_k]
                        sparse_candidate_count = len(sparse)
                        sparse_span.set_attribute("rag.sparse_candidates", len(sparse))
                    # 7.两路结果转为 chunk_id → 分数，取并集作为候选；只被一路命中的片段另一路记 0 分。
                    dense_scores = {chunk_id: score for chunk_id, score in dense}
                    sparse_scores = {chunk_id: score for chunk_id, score in sparse}
                    candidates = set(dense_scores) | set(sparse_scores)
                    # 8.加权融合：两路分数量纲不同，先各自除以本路最高分归一化，
                    #   再按 向量 0.6 + 关键词 0.4 加权求和；最高分为 0 或没有结果时用 1.0 避免除零。
                    with span("rag.fusion") as fusion_span:
                        max_dense = max(dense_scores.values(), default=1.0) or 1.0
                        max_sparse = max(sparse_scores.values(), default=1.0) or 1.0
                        results = [
                            RetrievedChunk(
                                **self._chunks[chunk_id].model_dump(),
                                score=0.6 * dense_scores.get(chunk_id, 0.0) / max_dense
                                + 0.4 * sparse_scores.get(chunk_id, 0.0) / max_sparse,
                            )
                            for chunk_id in candidates
                        ]
                        # 8.1.按融合分降序，只保留前 rerank_candidates 条送入重排。
                        results.sort(key=lambda item: item.score, reverse=True)
                        results = results[: self.rerank_candidates]
                        fusion_span.set_attribute("rag.fused_candidates", len(results))
                    # 9.可选重排：重排器对候选重新打分，按 (重排分, 融合分) 降序排列。
                    if self.reranker_enabled:
                        with span("rag.rerank") as rerank_span:
                            try:
                                # A synchronous local reranker is not cancellable by an outer
                                # wall-clock timer. Providers that perform I/O must enforce a
                                # native request deadline and raise on timeout themselves.
                                rerank_scores = self.reranker.score(query, results)
                                for result, rerank_score in zip(
                                    results, rerank_scores, strict=True
                                ):
                                    result.rerank_score = rerank_score
                                results.sort(
                                    key=lambda item: (item.rerank_score or 0.0, item.score),
                                    reverse=True,
                                )
                            # 9.1.重排失败时不中断检索：记录降级组件，沿用融合分的排序继续返回。
                            except Exception:
                                degraded_components.append("reranker")
                                rerank_span.set_attribute("rag.fallback_status", "reranker")
                            rerank_span.set_attribute("rag.retrieval_count", len(results))
                    # 10.截取最终交给回答生成的 final_context_count 条片段。
                    results = results[: self.final_context_count]
                # 11.汇总运行元数据：后端、命中数、耗时、降级状态、融合策略和两路候选数。
                fallback = "reranker" if degraded_components else "none"
                latency = time.perf_counter() - started
                metadata = RetrievalMetadata(
                    backend=self.backend_type,
                    embedding_provider=self.embedding_provider.provider_type,
                    reranker_enabled=self.reranker_enabled,
                    retrieval_count=len(results),
                    latency_seconds=latency,
                    fallback_status=fallback,
                    hybrid=True,
                    fusion_strategy="weighted_score",
                    dense_candidate_count=dense_candidate_count,
                    sparse_candidate_count=sparse_candidate_count,
                )
                # 12.写入追踪属性并累加成功请求计数，返回不可变的检索结果。
                retrieval_span.set_attribute("rag.retrieval_count", len(results))
                retrieval_span.set_attribute("rag.fallback_status", fallback)
                get_metrics().rag_requests_total.add(
                    1, {"status": "ok", "backend": self.backend_type}
                )
                return RetrievalResult(
                    chunks=tuple(results),
                    metadata=metadata,
                    degraded_components=tuple(degraded_components),
                )
            # 13.其他阶段（向量化、召回、融合）出错时记录失败指标后原样抛出，由上层决定重试或降级。
            except Exception:
                status = "error"
                retrieval_span.set_attribute("rag.status", "error")
                get_metrics().rag_requests_total.add(
                    1, {"status": "error", "backend": self.backend_type}
                )
                raise
            # 14.无论成功或失败，都记录本次检索总耗时。
            finally:
                get_metrics().rag_retrieval_duration_seconds.record(
                    time.perf_counter() - started,
                    {"status": status, "backend": self.backend_type},
                )

    def _bm25(self, query: str) -> list[tuple[str, float]]:
        """对全部片段计算 BM25 分数（k1=1.5，b=0.75），按分数降序返回 (chunk_id, score)。"""
        # 1.问题和每个片段都按小写英文/数字分词。
        query_tokens = _tokens(query)
        documents = {chunk_id: _tokens(chunk.content) for chunk_id, chunk in self._chunks.items()}
        # 2.文档频率 df：每个词出现在多少个片段中，片段内先 set 去重，只计一次。
        document_frequency = Counter(
            token for tokens in documents.values() for token in set(tokens)
        )
        # 3.片段平均长度（词数），用于长度归一化；max(..., 1) 防止空索引除零。
        average_length = sum(len(tokens) for tokens in documents.values()) / max(len(documents), 1)
        scores: list[tuple[str, float]] = []
        for chunk_id, tokens in documents.items():
            # 4.词频 tf：当前片段内每个词出现的次数。
            counts = Counter(tokens)
            score = 0.0
            for token in query_tokens:
                if token not in counts:
                    continue
                # 4.1.IDF = ln((N - df + 0.5) / (df + 0.5) + 1)，越少见的词权重越高，+1 保证非负。
                idf = math.log(
                    (len(documents) - document_frequency[token] + 0.5)
                    / (document_frequency[token] + 0.5)
                    + 1
                )
                # 4.2.分母 = tf + k1 * ((1 - b) + b * 片段长度 / 平均长度)，越长的片段被惩罚越多。
                denominator = counts[token] + 1.5 * (
                    0.25 + 0.75 * len(tokens) / max(average_length, 1)
                )
                # 4.3.单词得分 = IDF * tf * (k1 + 1) / 分母，tf 增大后得分趋于饱和。
                score += idf * counts[token] * 2.5 / denominator
            scores.append((chunk_id, score))
        return sorted(scores, key=lambda item: item[1], reverse=True)


def _cosine(left: Sequence[float], right: Sequence[float]) -> float:
    """计算两个向量的点积；向量已做 L2 归一化时，点积即余弦相似度。"""
    return sum(a * b for a, b in zip(left, right, strict=True))
