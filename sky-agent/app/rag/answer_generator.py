"""Citation-constrained, provider-neutral grounded answer generation.

中文阅读主线：answer 筛选证据 → _synthesize 组合摘录 → GroundingValidator 校验。
当前默认实现按片段摘录回答，不额外调用聊天模型；无依据或校验失败时明确降级。

The generator is deliberately extractive by default: retrieved evidence is the
only knowledge source, and every emitted factual claim carries an evidence
marker. A future synthesis provider may produce the same bounded contract, but
it must pass ``GroundingValidator`` before its output can reach a customer.
"""

from __future__ import annotations
import re
import time
from collections import defaultdict
from collections.abc import Sequence
from app.observability.metrics import get_metrics
from app.observability.tracing import span
from app.rag.context import construct_context
from app.rag.schemas import (
    AnswerGroundingStatus,
    Citation,
    GroundedAnswer,
    GroundingValidationResult,
    RetrievedChunk,
)

# 阅读主线：answer 筛选上下文 → _synthesize 摘录回答 → validate 校验依据。
# 词元规则：匹配 Unicode 字母和数字组成的文本，排除下划线；不等同于中文分词。
_TOKEN = re.compile(r"[^\W_]+", re.UNICODE)
# 提取回答中方括号内的引用标识，例如 [文档ID#章节]。
_CITATION = re.compile(r"\[([^\]]+)\]")
# 提取“数值 + 时间、百分比或货币单位”，供 _find_conflict 做启发式比较。
_QUANTIFIED_FACT = re.compile(
    r"\b(\d+(?:\.\d+)?)\s*(days?|months?|years?|hours?|%|usd|eur|gbp)\b",
    re.IGNORECASE,
)
# 停用词：提取问题和证据词元时排除这些常见功能词，保留更有区分度的信息。
_STOP_WORDS = {
    "about", "are", "can", "could", "does", "for", "from", "how", "is", "me", "my", "please",
    "tell", "the", "this", "what", "when", "where", "which", "with", "your",
}
# 资料引用词：询问文档、证据等内容时，_is_relevant 改用相关性分数门槛判断。
_EVIDENCE_REFERENCE_TERMS = {
    "article", "content", "document", "evidence", "note", "source", "text",
}
# 常规相关性分数门槛；有明确主题词时，后面允许特定的较低分数分支。
_MIN_RELEVANCE_SCORE = 0.35
# 泛化主题词：从问题词元中扣除它们，剩下的 distinctive 词元需要逐个匹配证据。
_GENERIC_QUERY_TERMS = {
    "allow", "allowed", "answer", "apply", "but", "cant", "cannot", "control", "controls", "days",
    "eligibility", "eligible", "explain", "first", "how", "information", "long", "many", "order",
    "policy", "process", "product", "question", "refund", "return", "support", "yes", "why", "work",
    "works",
}
# 证据不足或校验失败时使用的固定答复，不补造政策事实。
_SAFE_UNCERTAINTY = (
    "The knowledge base does not contain enough information to answer this question from the "
    "retrieved evidence. Please clarify the request or provide an applicable knowledge source."
)


# 依据校验器：检查引用来源、摘录真实性和事实引用覆盖率，默认失败时不放行。
class GroundingValidator:
    """Fail-closed validator for bounded answer and citation contracts."""

    def __init__(self, minimum_coverage: float = 0.95) -> None:
        """设置事实引用覆盖率的最低要求，默认 0.95；超出合法比例范围时拒绝初始化。"""
        # 1.覆盖率是比例，必须落在 0～1；合法值保存供 validate 使用。
        if not 0.0 <= minimum_coverage <= 1.0:
            raise ValueError("minimum_coverage must be between zero and one")
        self.minimum_coverage = minimum_coverage

    def validate(
        self,
        answer: GroundedAnswer,
        evidence: Sequence[RetrievedChunk],
    ) -> GroundingValidationResult:
        """核对候选回答的引用与事实是否来自本次证据，返回覆盖率、放行结果和失败原因。"""
        # 先核对引用是否来自本次检索，以及引用文字是否确实存在于原文。
        # 1.按片段 ID 和引用 ID 建索引，准备有效引用集合、失败原因及无依据主张副本。
        evidence_by_chunk = {chunk.chunk_id: chunk for chunk in evidence}
        citations_by_id = {citation.citation_id: citation for citation in answer.citations}
        valid_citation_ids: set[str] = set()
        reasons: list[str] = []
        unsupported = list(answer.unsupported_claims)

        # 2.逐条核对引用，只有片段来源和摘录均通过检查才加入有效集合。
        for citation in answer.citations:
            chunk = evidence_by_chunk.get(citation.chunk_id)
            # 2.1.引用必须指向本次检索中的片段，且其文档 ID 必须一致；失败后继续检查下一条。
            if chunk is None or citation.document_id != chunk.document_id:
                reasons.append("citation_not_in_retrieved_evidence")
                continue
            # 2.2.忽略大小写和空白后，引用摘录必须确实包含在对应片段正文中。
            if _normalize(citation.quoted_excerpt) not in _normalize(chunk.content):
                reasons.append("citation_excerpt_mismatch")
                continue
            valid_citation_ids.add(citation.citation_id)

        # 再检查每条事实后面的引用标记，统计真正有证据支持的主张。
        # 3.逐条检查 grounded_claims，统计有有效引用摘录支持的事实数量。
        supported_claims = 0
        for claim in answer.grounded_claims:
            claim_position = answer.answer.find(claim)
            # 3.1.声明的事实没有出现在回答文本中时，记为无依据主张。
            if claim_position < 0:
                unsupported.append(claim)
                continue
            # 3.2.在事实之后的 160 个字符内找引用标记，再与有效引用 ID 求交集。
            suffix = answer.answer[claim_position + len(claim) : claim_position + len(claim) + 160]
            markers = set(_CITATION.findall(suffix))
            supporting_citations = [
                citations_by_id[marker]
                for marker in markers & valid_citation_ids
                if marker in citations_by_id
            ]
            # 3.3.至少一条关联引用的摘录包含该事实，才计为有依据；否则记录不匹配原因。
            if any(
                _normalize(claim) in _normalize(citation.quoted_excerpt)
                for citation in supporting_citations
            ):
                supported_claims += 1
            else:
                unsupported.append(claim)
                reasons.append("answer_evidence_mismatch")

        # 覆盖率不足、引用缺失或存在无依据主张，都不能作为合格回答放行。
        # 4.用支持的事实数除以候选事实与原有无依据主张总数；没有主张时覆盖率为 0。
        total_claims = len(answer.grounded_claims) + len(answer.unsupported_claims)
        coverage = supported_claims / total_claims if total_claims else 0.0
        # 4.1.分别记录证据为空、事实缺引用、覆盖率不足及无依据主张等失败原因。
        if not evidence:
            reasons.append("empty_evidence")
        if answer.grounded_claims and not answer.citations:
            reasons.append("citations_missing")
        if coverage < self.minimum_coverage:
            reasons.append("citation_coverage_below_threshold")
        if unsupported:
            reasons.append("unsupported_claims_present")

        # 5.证据非空、没有失败原因且覆盖率达标时放行；原因去重并保留首次出现顺序。
        accepted = bool(evidence) and not reasons and coverage >= self.minimum_coverage
        return GroundingValidationResult(
            citation_coverage=coverage,
            accepted=accepted,
            unsupported_claim_count=len(set(unsupported)),
            reasons=list(dict.fromkeys(reasons)),
        )


# 证据回答生成器：组合原文摘录和引用；当前实现不额外调用聊天模型。
class GroundedAnswerGenerator:
    """Generate bounded extractive answers and reject unsupported synthesis."""

    def __init__(
        self,
        max_context: int = 4,
        validator: GroundingValidator | None = None,
    ) -> None:
        """设置上下文片段上限和依据校验器；未提供校验器时创建默认实例。"""
        # 1.保存上下文片段上限；默认取 4 条，实际筛选由 answer 调用 construct_context 完成。
        self.max_context = max_context
        # 2.复用传入的校验器，否则使用默认最低覆盖率为 0.95 的校验器。
        self.validator = validator or GroundingValidator()

    def answer(self, query: str, chunks: Sequence[RetrievedChunk]) -> GroundedAnswer:
        """接收用户问题和检索片段，同步筛选证据、摘录回答并校验，供 retrieve_knowledge 节点使用。"""
        # 检索命中不等于能回答，先限制上下文，再过滤与当前问题无关的片段。
        # 1.用固定规则归一化支持的问题表达，供后面的相关性判断和摘录主题识别使用。
        normalized_query = normalize_knowledge_query(query)
        # 2.建立回答生成与上下文构建的追踪区间，记录每阶段的运行信息。
        with span("rag.answer_generate") as generation_span:
            with span("rag.context_build") as context_span:
                # 2.1.同步调用上下文构建函数：按分数排序、按内容去重，并限制片段数量。
                context = construct_context(chunks, self.max_context)
                context_span.set_attribute("rag.final_context_chunks", len(context))
            # 3.逐条检查上下文与问题的相关性；没有相关证据时生成不确定答复。
            relevant = [chunk for chunk in context if _is_relevant(normalized_query, chunk)]
            if not relevant:
                # 没有相关证据时返回不确定答复，不补造政策或事实。
                result = _uncertain_answer()
            else:
                # 4.有相关证据时先生成摘录式候选回答，再交给校验器检查引用和事实依据。
                result = self._synthesize(relevant, normalized_query)
                validation_started = time.perf_counter()
                validation_status = "error"
                try:
                    # 4.1.传入候选回答及实际使用的相关证据，同步取得覆盖率、失败原因和放行结果。
                    validation = self.validator.validate(result, relevant)
                    validation_status = "accepted" if validation.accepted else "rejected"
                # 4.2.无论校验正常返回还是抛出异常，都记录耗时；这里不吞掉校验异常。
                finally:
                    get_metrics().grounding_validation_duration_seconds.record(
                        time.perf_counter() - validation_started,
                        {"status": validation_status},
                    )
                # 4.3.把校验结果写入回答副本，并让 grounded 与是否通过校验保持一致。
                result = result.model_copy(
                    update={"validation": validation, "grounded": validation.accepted}
                )
                # 4.4.校验未通过时替换为固定的不确定说明，避免把候选事实作为可信答复返回。
                if not validation.accepted:
                    result = _rejected_answer(result, validation)
            # 5.记录最终结果的监控数据；返回 GroundedAnswer，由节点投影到知识回答和引用字段。
            _record_grounding_observability(generation_span, result, len(context))
            return result

    def _synthesize(self, chunks: Sequence[RetrievedChunk], query: str) -> GroundedAnswer:
        """从相关片段中摘录事实并生成引用，得到尚待 validate 校验的候选回答。"""
        # 从原文摘录并附引用；发现数量/单位冲突时展示分歧，不擅自选一方。
        # 1.先查数量单位的可能冲突；发现时使用冲突片段，否则取前两条相关片段。
        conflict = _find_conflict(chunks)
        selected = list(conflict or chunks[:2])
        # 2.识别时间或适用条件主题，摘取对应证据句，再把摘录与来源一一配对生成引用。
        focus = _query_focus(query)
        excerpts = [_excerpt(chunk.content, focus=focus) for chunk in selected]
        citations = [
            _citation(chunk, excerpt) for chunk, excerpt in zip(selected, excerpts, strict=True)
        ]
        # 3.摘录本身作为事实主张；后面的校验器检查这些原文是否有对应引用支持。
        claims = excerpts
        # 3.1.发现可能冲突时并列展示两方证据，不自动选择一方，状态为 CONFLICT。
        if conflict:
            statements = " ".join(
                f"{claim} [{citation.citation_id}]."
                for claim, citation in zip(claims, citations, strict=True)
            )
            answer = (
                f"The retrieved sources conflict: {statements} "
                "I cannot resolve the conflict from the available evidence."
            )
            status = AnswerGroundingStatus.CONFLICT
            # 冲突分支使用固定的较低置信度；它仍会进入后续引用与事实校验。
            confidence = 0.25
        else:
            statements = " ".join(
                f"{claim} [{citation.citation_id}]."
                for claim, citation in zip(claims, citations, strict=True)
            )
            # 3.2.未发现冲突时组合摘录及引用标记，并按引用分数计算依据置信度。
            answer = f"Based on the retrieved evidence: {statements}"
            status = AnswerGroundingStatus.PASS
            confidence = _confidence(citations)
        # 4.封装候选回答；来源数按文档 ID 去重，validation 初始为未通过，等待 answer 校验。
        return GroundedAnswer(
            answer=answer,
            citations=citations,
            confidence=confidence,
            grounded_claims=claims,
            unsupported_claims=[],
            source_count=len({citation.document_id for citation in citations}),
            status=status,
            validation=GroundingValidationResult(
                citation_coverage=0.0,
                accepted=False,
                unsupported_claim_count=0,
            ),
        )


def _uncertain_answer() -> GroundedAnswer:
    """证据为空或不相关时，返回固定的不确定说明及未通过校验的结果。"""
    # 1.返回固定说明；引用、事实主张及来源数清空，置信度与覆盖率为 0，grounded 为 False。
    return GroundedAnswer(
        answer=_SAFE_UNCERTAINTY,
        citations=[],
        confidence=0.0,
        grounded_claims=[],
        unsupported_claims=[],
        source_count=0,
        status=AnswerGroundingStatus.INSUFFICIENT_EVIDENCE,
        validation=GroundingValidationResult(
            citation_coverage=0.0,
            accepted=False,
            unsupported_claim_count=0,
            reasons=["empty_or_irrelevant_evidence"],
        ),
        grounded=False,
    )


def _rejected_answer(
    candidate: GroundedAnswer,
    validation: GroundingValidationResult,
) -> GroundedAnswer:
    """校验失败时替换候选回答，保留有限的诊断主张和校验结果。"""
    # 1.合并候选回答中的两类主张，按原顺序去重，最多保留 20 条用于诊断。
    rejected_claims = list(dict.fromkeys(candidate.unsupported_claims + candidate.grounded_claims))
    # 2.清空对外事实和引用、保留失败校验结果；这些候选主张不再作为可信回答返回。
    return GroundedAnswer(
        answer=_SAFE_UNCERTAINTY,
        citations=[],
        confidence=0.0,
        grounded_claims=[],
        unsupported_claims=rejected_claims[:20],
        source_count=0,
        status=AnswerGroundingStatus.REJECTED,
        validation=validation,
        grounded=False,
    )


def _citation(chunk: RetrievedChunk, excerpt: str | None = None) -> Citation:
    """把检索片段与摘录封装成结构化引用，保留文档、片段及来源信息。"""
    # 1.优先使用传入摘录；未提供有效摘录时调用 _excerpt，从片段正文取得默认摘录。
    quoted_excerpt = excerpt or _excerpt(chunk.content)
    # 2.保存引用标识、文档与片段 ID、标题和来源，便于回查当前引用对应的原文。
    return Citation(
        citation_id=chunk.citation_id,
        document_id=chunk.document_id,
        title=chunk.title,
        chunk_id=chunk.chunk_id,
        source=chunk.source,
        # 2.1.使用非零重排分数，否则用检索分数；负数限制为 0，摘录原文单独保存。
        relevance_score=max(0.0, chunk.rerank_score or chunk.score),
        quoted_excerpt=quoted_excerpt,
    )


def _excerpt(content: str, limit: int = 320, *, focus: str | None = None) -> str:
    """按问题主题选取一条证据句，合并空白并限制摘录字符数。"""
    # 1.合并正文空白，按 .!? 后的空白分句，准备按问题主题选出一条证据句。
    normalized = " ".join(content.split())
    sentences = re.split(r"(?<=[.!?])\s+", normalized)
    # 2.时间主题：匹配处理、审核、结算或工作日等规则，优先取第一条匹配句。
    if focus == "timing":
        timing_terms = re.compile(
            r"\b(?:processing|review|settlement|business\s+days?|additional\s+days?|3[–-]5)\b",
            re.IGNORECASE,
        )
        first_sentence = next(
            (sentence for sentence in sentences if timing_terms.search(sentence)), sentences[0]
        )
    # 3.适用条件主题：匹配资格、送达、损坏、30 天或退回等规则；无匹配时退回首句。
    elif focus == "eligibility":
        eligibility_terms = re.compile(
            r"\b(?:eligible|qualify|considered|delivered|damaged|30\s+calendar\s+days?|returned)\b",
            re.IGNORECASE,
        )
        first_sentence = next(
            (sentence for sentence in sentences if eligibility_terms.search(sentence)), sentences[0]
        )
    else:
        # 4.没有指定主题时直接取首句。
        first_sentence = sentences[0]
    # 5.按字符数截取到 limit，默认 320；去掉尾部空白及 .!?，不改写事实。
    bounded = first_sentence[:limit].rstrip()
    return bounded.rstrip(".!?")


def normalize_knowledge_query(query: str) -> str:
    """Map bounded multilingual/topic phrases to the stored evidence vocabulary."""

    # 1.仅为正则判断统一特殊字符、大小写和空白；未命中规则时仍返回原始 query。
    normalized = " ".join(query.replace("İ", "I").casefold().split())
    # 2.匹配部分土耳其语“退款何时到账、退款用时”等表达，统一为退款处理时间检索词。
    if re.search(
        r"\b(?:para\s+ne\s+zaman\s+yatar|ne\s+zaman\s+(?:hesabıma|geçer)|"
        r"iade\s+süresi|geri\s+ödeme\s+süresi)\b",
        normalized,
    ):
        return "refund processing review settlement"
    # 3.匹配部分土耳其语“适用期限”表达，映射到已送达及 30 个自然日的检索词。
    if re.search(r"\b(?:iade\s+uygunluk\s+süresi|uygunluk\s+süresi)\b", normalized):
        return "refund eligibility delivered 30 calendar days"
    # 4.匹配部分土耳其语“退款条件”表达，映射到适用资格、送达、损坏和退回主题。
    if re.search(r"\b(?:iade\s+şart|iade\s+koşul|geri\s+ödeme\s+şart)\w*\b", normalized):
        return "refund eligibility delivered damaged returned"
    # 5.匹配英文处理时间、结算或“退款多久”等表达；how long 还需同时出现退款相关词。
    if re.search(
        r"\b(?:processing\s+time|settlement|when\s+will.*refund|refund.*take)\b",
        normalized,
    ) or (
        re.search(r"\bhow\s+long\b", normalized)
        and re.search(r"\b(?:refund|return|reimburse|money\s+back)\b", normalized)
    ):
        return "refund processing review settlement"
    # 6.没有匹配规则时原样返回；这是固定正则映射，当前没有中文规则或模型改写。
    return query


def _query_focus(query: str) -> str | None:
    """识别查询关注的是退款时间还是适用条件，指导 _excerpt 选句。"""
    # 1.处理、结算或审核关键词优先归为 timing，让摘录聚焦时间说明。
    if "processing" in query or "settlement" in query or "review" in query:
        return "timing"
    # 2.适用资格关键词归为 eligibility；两类都没有时返回 None，使用默认首句。
    if "eligibility" in query or "eligible" in query:
        return "eligibility"
    return None


def _normalize(value: str) -> str:
    """忽略大小写并合并空白，供摘录与原文、事实与引用之间的包含比较使用。"""
    # 1.casefold 忽略大小写，split 与 join 把连续空白合并为一个空格。
    return " ".join(value.casefold().split())


def _terms(value: str) -> set[str]:
    """用正则提取词元，过滤停用词并简化英文词尾，供问题与证据的关键词匹配使用。"""
    terms: set[str] = set()
    # 1.忽略大小写后，用 _TOKEN 提取词元；这里没有调用分词模型。
    for raw in _TOKEN.findall(value.casefold()):
        # 2.跳过停用词和不足三个字符的词元。
        if raw in _STOP_WORDS or len(raw) < 3:
            continue
        # 3.长度超过四且以 s 结尾时删去末尾 s，再放入集合去重；这是简化规则。
        terms.add(raw[:-1] if raw.endswith("s") and len(raw) > 4 else raw)
    return terms


def _is_relevant(query: str, chunk: RetrievedChunk) -> bool:
    """结合检索分数和关键词覆盖，判断一个文档片段是否适合回答当前问题。"""
    # 1.提取问题关键词；过滤后为空时，片段直接判为不相关。
    query_terms = _terms(query)
    if not query_terms:
        return False
    # 2.重排分数存在时使用它，否则用检索分数；这里明确保留有效的零分。
    score = chunk.rerank_score if chunk.rerank_score is not None else chunk.score
    # 2.1.问题包含资料引用词时，直接按常规分数门槛判断，不进入后面的主题词覆盖检查。
    if query_terms & _EVIDENCE_REFERENCE_TERMS:
        return score >= _MIN_RELEVANCE_SCORE
    # 3.合并标题、类别、章节和正文的词元，统计问题词覆盖，并扣除泛化词得到区别性词元。
    evidence_terms = _terms(" ".join((chunk.title, chunk.category, chunk.section, chunk.content)))
    covered = {term for term in query_terms if _term_matches(term, evidence_terms)}
    distinctive = query_terms - _GENERIC_QUERY_TERMS
    # A deterministic reranker can score a short, highly specific question
    # below the general threshold even when every distinctive term is present
    # in the source.  Permit that bounded case, but never let a low-confidence
    # generic query pass without stronger retrieval support.
    # 4.低于常规门槛时，只有分数至少 0.2 且存在区别性词元才允许继续检查。
    if score < _MIN_RELEVANCE_SCORE:
        if score < 0.2 or not distinctive:
            return False
    # 5.至少覆盖一个问题词，且每个区别性词元都能匹配证据时，判为相关。
    return bool(covered) and all(_term_matches(term, evidence_terms) for term in distinctive)


def _term_matches(term: str, evidence_terms: set[str]) -> bool:
    """先比较完整词元，再对长度足够的词元使用前五字符做启发式匹配。"""
    # 1.词元完全相同时直接匹配；否则不足五个字符的短词不做前缀匹配。
    if term in evidence_terms:
        return True
    if len(term) < 5:
        return False
    # 2.对长度至少为五的证据词比较前五字符，允许简单词形差异；不做语义推理。
    return any(
        len(evidence_term) >= 5
        and (term.startswith(evidence_term[:5]) or evidence_term.startswith(term[:5]))
        for evidence_term in evidence_terms
    )


def _find_conflict(chunks: Sequence[RetrievedChunk]) -> tuple[RetrievedChunk, ...] | None:
    """按数量单位分组，发现同单位不同数值时返回两条对应证据，提示可能的冲突。"""
    # 1.按单位建立“数值 → 首个来源片段”的索引，同数值使用 setdefault 保留首次来源。
    facts: dict[str, dict[str, RetrievedChunk]] = defaultdict(dict)
    for chunk in chunks:
        # 2.从正文提取数量和单位，单位转小写并去掉末尾 s；不做不同单位换算。
        for value, unit in _QUANTIFIED_FACT.findall(chunk.content):
            normalized_unit = unit.casefold().rstrip("s")
            facts[normalized_unit].setdefault(value, chunk)
    for values in facts.values():
        # 3.同一单位出现不同数值时取前两条来源；只是冲突线索，不判断数值是否描述同一事项。
        if len(values) > 1:
            return tuple(list(values.values())[:2])
    return None


def _confidence(citations: Sequence[Citation]) -> float:
    """取引用相关性分数的平均值作为依据置信度；该值不是模型回答正确的概率。"""
    # 1.没有引用时置信度为 0；否则把每条相关性分数上限截到 1。
    if not citations:
        return 0.0
    scores = [min(citation.relevance_score, 1.0) for citation in citations]
    # 2.取这些分数的平均值并保留四位小数，作为依据分数摘要。
    return round(sum(scores) / len(scores), 4)


def _record_grounding_observability(
    generation_span: object,
    answer: GroundedAnswer,
    retrieval_count: int,
) -> None:
    """记录依据校验的追踪属性和统计指标，不改变回答内容或图的路由。"""
    # 1.整理最终依据状态、覆盖率、无依据主张数、上下文数量、置信度及是否通过。
    attributes = {
        "rag.grounding.status": answer.status.value,
        "rag.grounding.citation_coverage": answer.validation.citation_coverage,
        "rag.grounding.unsupported_claim_count": len(answer.unsupported_claims),
        "rag.grounding.retrieval_count": retrieval_count,
        "rag.grounding.answer_confidence": answer.confidence,
        "rag.grounding.accepted": answer.validation.accepted,
    }
    # 2.逐项写入当前生成阶段的追踪区间，方便定位依据校验结果。
    for key, value in attributes.items():
        generation_span.set_attribute(key, value)  # type: ignore[attr-defined]
    # 3.按最终状态分类，记录覆盖率、无依据主张数、上下文片段数和置信度的观测值。
    labels = {"status": answer.status.value}
    metrics = get_metrics()
    metrics.rag_grounding_citation_coverage.record(answer.validation.citation_coverage, labels)
    metrics.rag_grounding_unsupported_claim_count.record(len(answer.unsupported_claims), labels)
    metrics.rag_grounding_retrieval_count.record(retrieval_count, labels)
    metrics.rag_grounding_answer_confidence.record(answer.confidence, labels)
