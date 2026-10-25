"""查询工单详情，或按用户提供的类别与描述创建工单。

建单可关联订单，但必须验证其客户归属；重复写入由幂等封装处理。
"""

from pydantic import BaseModel, Field
from sqlalchemy import select
from sqlalchemy.orm import Session

from app.models import Customer, Order, SupportTicket, TicketStatus
from app.schemas.domain import TicketResponse
from app.services.idempotency import IdempotencyScope, execute_idempotent
from app.services.ticket_service import get_ticket_for_customer
from app.tools.base import OwnershipError, ResourceNotFoundError


class GetTicketInput(BaseModel):
    ticket_id: int = Field(gt=0)
    customer_id: int = Field(gt=0)


# 订单可选，工单类别和问题描述必填。
class CreateSupportTicketInput(BaseModel):
    customer_id: int = Field(gt=0)
    order_id: int | None = Field(default=None, gt=0)
    category: str = Field(min_length=1, max_length=100)
    description: str = Field(min_length=1, max_length=5000)


def get_ticket(
    session: Session, request: GetTicketInput, *, tenant_id: str = "default"
) -> TicketResponse:
    ticket = get_ticket_for_customer(session, request.ticket_id, request.customer_id, tenant_id)
    if ticket is None:
        raise ResourceNotFoundError("Support ticket", request.ticket_id)
    return TicketResponse.model_validate(ticket)


def create_support_ticket(
    session: Session,
    request: CreateSupportTicketInput,
    *,
    idempotency: IdempotencyScope,
    tenant_id: str = "default",
) -> TicketResponse:
    def load_result(ticket_id: int) -> TicketResponse:
        # 相同幂等请求复用已创建的工单。
        ticket = session.scalar(
            select(SupportTicket).where(
                SupportTicket.id == ticket_id, SupportTicket.tenant_id == tenant_id
            )
        )
        if ticket is None:
            raise ResourceNotFoundError("Support ticket", ticket_id)
        return TicketResponse.model_validate(ticket)

    def perform() -> tuple[TicketResponse, int]:
        if (
            session.scalar(
                select(Customer).where(
                    Customer.id == request.customer_id, Customer.tenant_id == tenant_id
                )
            )
            is None
        ):
            raise ResourceNotFoundError("Customer", request.customer_id)
        if request.order_id is not None:
            # 关联订单必须存在，并且属于当前客户与租户。
            order = session.scalar(
                select(Order).where(Order.id == request.order_id, Order.tenant_id == tenant_id)
            )
            if order is None:
                raise ResourceNotFoundError("Order", request.order_id)
            if order.customer_id != request.customer_id:
                raise OwnershipError("Order", request.order_id, request.customer_id)
        # 新工单以 OPEN 状态进入后续处理流程。
        ticket = SupportTicket(
            customer_id=request.customer_id,
            tenant_id=tenant_id,
            order_id=request.order_id,
            category=request.category,
            status=TicketStatus.OPEN,
            description=request.description,
        )
        session.add(ticket)
        session.flush()  # 获取新记录 ID，随结果交给幂等层保存。
        return TicketResponse.model_validate(ticket), ticket.id

    return execute_idempotent(
        session,
        scope=idempotency,
        operation="create_support_ticket",
        customer_id=request.customer_id,
        request_payload=request.model_dump(mode="json"),
        perform=perform,
        load_result=load_result,
    )
