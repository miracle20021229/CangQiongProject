"""识别明确确认/拒绝，并检查待执行动作的有效期与归属。

check_pending 节点用这些结果恢复上一轮动作；识别到确认不代表可以跳过重验。
"""

import re
from datetime import datetime
from typing import Protocol
from app.core.context import ExecutionContext
from app.policies.models import PendingAction, PendingActionStatus


class Clock(Protocol):
    def now(self) -> datetime: ...


class SystemClock:
    def now(self) -> datetime:
        return datetime.now().astimezone()


def parse_confirmation(message: str) -> str:
    # 只接受完整短语匹配，避免把带否定或条件的句子误判成同意。
    # Python's locale-independent casefold maps ASCII I to i. Normalize the two
    # Turkish uppercase forms first so exact, bounded phrase matching remains
    # stable without introducing substring or fuzzy approval behavior.
    normalized = message.translate(str.maketrans("Iİ", "ıi")).casefold()
    normalized = re.sub(r"[,.!?;:]+", " ", normalized)
    normalized = " ".join(normalized.split())
    if normalized in {
        "yes",
        "confirm",
        "confirmed",
        "proceed",
        "do it",
        "yes proceed",
        "yes please",
        "yes please proceed",
        "yes please proceed with the refund",
        "yes please proceed with the refund request",
        "i confirm",
        "ı confirm",
        "proceed with the refund",
        "proceed with the refund request",
        "go ahead",
        "please continue",
        "approved",
        "evet",
        "onaylıyorum",
        "onayla",
        "devam et",
        "işlemi onaylıyorum",
    }:
        return "confirmed"
    if normalized in {
        "no",
        "cancel",
        "never mind",
        "don't do it",
        "do not do it",
        "don't proceed",
        "do not proceed",
        "no cancel it",
        "stop",
        "hayır",
        "iptal",
        "iptal et",
        "vazgeçtim",
        "onaylamıyorum",
    }:
        return "rejected"
    return "ambiguous"  # 未命中明确短语时继续澄清，不默认批准。


def is_expired(action: PendingAction, now: datetime, ttl_seconds: int) -> bool:
    # 确认窗口从创建待办时开始计算，达到 TTL 即失效。
    return (now - action.created_at).total_seconds() >= ttl_seconds


def transition(action: PendingAction, status: PendingActionStatus) -> PendingAction:
    # 返回新状态副本，避免原地修改图中保留的待办对象。
    return action.model_copy(update={"status": status})


def belongs_to_context(action: PendingAction, context: ExecutionContext) -> bool:
    # 确认必须属于同一会话、租户、操作者和客户，不能跨会话挪用。
    principal = context.principal
    return (
        action.conversation_id == context.conversation_id
        and action.tenant_id == context.tenant_id
        and action.actor_id == principal.actor_id
        and action.actor_type == principal.actor_type
        and action.effective_customer_id == context.effective_customer_id
    )
