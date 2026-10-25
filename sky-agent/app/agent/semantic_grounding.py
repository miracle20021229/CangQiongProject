"""
使用确定性规则校验语义实体引用的信任边界，检查模型给出的目标是否有用户文本依据。
"""

from __future__ import annotations
import re
from enum import StrEnum
from typing import Literal
from pydantic import BaseModel, ConfigDict
from app.agent.schemas import SemanticDecision

"""
检查模型给出的业务目标，能否在用户说过的话中找到依据。
例如用户说“查询订单 123”，模型给出的订单号也是 123，就能找到文字依据；
如果模型却给出 999，而用户没有提过这个编号，就标记为“缺少依据”。
“最新订单”先标记为待解析的指代，具体订单留给后续流程确定。
这里只核对目标的文字依据，订单是否存在、是否属于当前客户由后续流程检查。
"""

_DOMAIN_INTEGER_ID = re.compile(r"(?<![A-Za-z0-9_-])([1-9][0-9]*)(?![A-Za-z0-9_-])")


class GroundingStatus(StrEnum):
    """语义目标的依据校验状态，用于区分有依据、符号指代、缺少依据、无需校验和无效目标。"""

    GROUNDED = "grounded"  # 有依据：目标编号出现在传入的用户文本中，不代表业务校验通过
    SYMBOLIC = "symbolic"  # 符号指代：目标为“最新订单”，具体订单由后续流程解析
    UNGROUNDED = "ungrounded"  # 缺少依据：目标编号未出现在传入的用户文本中
    NOT_APPLICABLE = "not_applicable"  # 无需校验：语义决策没有业务目标
    INVALID = "invalid"  # 目标无效：目标类型不受支持，或缺少对应的订单号、工单号


class SemanticGrounding(BaseModel):
    """Privacy-safe, server-computed provenance result for a semantic target.

    由服务端计算的语义目标来源校验结果，仅记录状态、目标类型和依据来源，
    不保存用户原文或具体编号，以减少隐私信息暴露。
    """

    model_config = ConfigDict(extra="forbid")  # 禁止传入未声明的字段

    status: GroundingStatus  # 目标依据的校验状态，必填
    # 目标类型：指定订单、指定工单或最新订单；未记录时为 None
    reference_type: Literal["explicit_order", "explicit_ticket", "latest_order"] | None = None
    # 依据来源：编号匹配用户文本时记录 current_user_message，否则为 None
    trusted_source: Literal["current_user_message"] | None = None


def _user_domain_ids(user_message: str) -> set[str]:
    """Return canonical integer IDs explicitly present in the current message.

    提取传入用户文本中明确出现的正整数编号，转换为规范的字符串并去重后返回。
    调用方可能传入多条用户消息拼接后的文本；这里只提取数字，不判断其业务含义。
    """

    return {str(int(value)) for value in _DOMAIN_INTEGER_ID.findall(user_message)}


def validate_semantic_grounding(decision: SemanticDecision, user_message: str) -> SemanticGrounding:
    """Validate semantic target provenance without using model claims or fuzzy matching.

    校验语义决策中目标的来源依据，不采信模型自行声称的依据，也不进行模糊匹配。
    将指定订单或工单的编号与用户文本中提取的编号核对，并返回校验结果；
    “最新订单”仅标记为符号指代，目标是否存在及其归属由后续业务流程校验。
    """

    target = decision.target
    if target is None:
        return SemanticGrounding(status=GroundingStatus.NOT_APPLICABLE)
    if target.type == "latest_order":
        return SemanticGrounding(
            status=GroundingStatus.SYMBOLIC,
            reference_type=target.type,
        )
    if target.type not in {"explicit_order", "explicit_ticket"}:
        return SemanticGrounding(status=GroundingStatus.INVALID)
    identifier = target.order_id if target.type == "explicit_order" else target.ticket_id
    if identifier is None:
        return SemanticGrounding(
            status=GroundingStatus.INVALID,
            reference_type=target.type,
        )
    status = (
        GroundingStatus.GROUNDED
        if str(identifier) in _user_domain_ids(user_message)
        else GroundingStatus.UNGROUNDED
    )
    return SemanticGrounding(
        status=status,
        reference_type=target.type,
        trusted_source="current_user_message" if status == GroundingStatus.GROUNDED else None,
    )
