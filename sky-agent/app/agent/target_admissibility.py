"""在解析语义目标之前做一道固定规则的把关：模型给出的目标能不能被采信，由这里说了算"""

from __future__ import annotations
from enum import StrEnum
from app.agent.schemas import Intent, SemanticTarget
from app.agent.semantic_grounding import GroundingStatus, SemanticGrounding


class TargetAdmissibility(StrEnum):
    ADMISSIBLE = "admissible"                              # 放行
    ADMISSIBLE_SYMBOLIC_READ = "admissible_symbolic_read"  # 放行，但仅限只读语义
    REQUIRES_CLARIFICATION = "requires_clarification"      # 拦截，且可追问用户补全
    INVALID = "invalid"                                    # 拦截，目标类型本身不合法


DESTRUCTIVE_TARGET_INTENTS = frozenset({Intent.ORDER_CANCEL, Intent.REFUND_REQUEST})


def assess_target_admissibility(
    intent: Intent,
    target: SemanticTarget | None,
    grounding: SemanticGrounding | None,
) -> TargetAdmissibility:
    """判断模型提出的目标能否进入后续的编译环节。
    对于取消订单、退款这类不可逆操作，模型给出的“最新订单”之类的指代一律不作数，
    必须由用户明确指定；这类指代只允许用在范围受限的只读查询里。"""

    if target is None:
        return (
            TargetAdmissibility.REQUIRES_CLARIFICATION
            if intent in DESTRUCTIVE_TARGET_INTENTS
            else TargetAdmissibility.ADMISSIBLE
        )
    if target.type == "latest_order":
        if intent in DESTRUCTIVE_TARGET_INTENTS:
            return TargetAdmissibility.REQUIRES_CLARIFICATION
        return TargetAdmissibility.ADMISSIBLE_SYMBOLIC_READ
    if target.type not in {"explicit_order", "explicit_ticket"}:
        return TargetAdmissibility.INVALID
    if grounding is not None and grounding.status in {
        GroundingStatus.UNGROUNDED,
        GroundingStatus.INVALID,
    }:
        return TargetAdmissibility.REQUIRES_CLARIFICATION
    return TargetAdmissibility.ADMISSIBLE
