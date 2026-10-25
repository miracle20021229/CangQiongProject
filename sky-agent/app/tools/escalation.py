"""把客服事项登记为待人工处理的任务。

保存原因、优先级和摘要，可关联订单或工单；这里创建记录，不接通真人会话。
"""

from pydantic import BaseModel, Field
from sqlalchemy import select
from sqlalchemy.orm import Session

from app.models import (
    Customer,
    Escalation,
    EscalationPriority,
    EscalationStatus,
    Order,
    SupportTicket,
)
from app.schemas.domain import EscalationResponse
from app.services.idempotency import IdempotencyScope, execute_idempotent
from app.tools.base import OwnershipError, ResourceNotFoundError


class EscalateToHumanInput(BaseModel):
    customer_id: int = Field(gt=0)
    ticket_id: int | None = Field(default=None, gt=0)
    order_id: int | None = Field(default=None, gt=0)
    reason: str = Field(min_length=1, max_length=2000)
    priority: EscalationPriority
    summary: str = Field(min_length=1, max_length=5000)


def escalate_to_human(
    session: Session,
    request: EscalateToHumanInput,
    *,
    idempotency: IdempotencyScope,
    tenant_id: str = "default",
) -> EscalationResponse:
    def load_result(escalation_id: int) -> EscalationResponse:
        # 重复请求返回原来的人工处理记录。
        escalation = session.scalar(
            select(Escalation).where(
                Escalation.id == escalation_id, Escalation.tenant_id == tenant_id
            )
        )
        if escalation is None:
            raise ResourceNotFoundError("Escalation", escalation_id)
        return EscalationResponse.model_validate(escalation)

    def perform() -> tuple[EscalationResponse, int]:
        if (
            session.scalar(
                select(Customer).where(
                    Customer.id == request.customer_id, Customer.tenant_id == tenant_id
                )
            )
            is None
        ):
            raise ResourceNotFoundError("Customer", request.customer_id)
        if request.ticket_id is not None:
            # 所有关联资源都要单独检查归属，不能只相信模型提供的编号。
            ticket = session.scalar(
                select(SupportTicket).where(
                    SupportTicket.id == request.ticket_id, SupportTicket.tenant_id == tenant_id
                )
            )
            if ticket is None:
                raise ResourceNotFoundError("Support ticket", request.ticket_id)
            if ticket.customer_id != request.customer_id:
                raise OwnershipError("Support ticket", request.ticket_id, request.customer_id)
        if request.order_id is not None:
            order = session.scalar(
                select(Order).where(Order.id == request.order_id, Order.tenant_id == tenant_id)
            )
            if order is None:
                raise ResourceNotFoundError("Order", request.order_id)
            if order.customer_id != request.customer_id:
                raise OwnershipError("Order", request.order_id, request.customer_id)
        # QUEUED 表示已排队等待人工接手，尚未由人工完成处理。
        escalation = Escalation(
            customer_id=request.customer_id,
            tenant_id=tenant_id,
            ticket_id=request.ticket_id,
            order_id=request.order_id,
            reason=request.reason,
            priority=request.priority,
            summary=request.summary,
            status=EscalationStatus.QUEUED,
        )
        session.add(escalation)
        session.flush()  # 取得记录 ID，供幂等层登记结果。
        return EscalationResponse.model_validate(escalation), escalation.id

    return execute_idempotent(
        session,
        scope=idempotency,
        operation="escalate_to_human",
        customer_id=request.customer_id,
        request_payload=request.model_dump(mode="json"),
        perform=perform,
        load_result=load_result,
    )
