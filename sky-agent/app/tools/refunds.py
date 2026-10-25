"""创建退款申请，并检查订单是否允许申请退款。

本工具只写入 REQUESTED 申请记录；实际支付退款不在这里完成。
确认与策略判断在图节点中执行，此处保留最后的业务校验和幂等保护。
"""

from pydantic import BaseModel, Field
from sqlalchemy import select
from sqlalchemy.exc import IntegrityError
from sqlalchemy.orm import Session
from app.models import Order, OrderStatus, RefundRequest, RefundStatus
from app.schemas.domain import RefundRequestResponse
from app.services.idempotency import IdempotencyScope, execute_idempotent
from app.tools.base import (
    DuplicateActionError,
    InvalidStateTransitionError,
    OwnershipError,
    ResourceNotFoundError,
)


# 原因必须由用户提供；编译器负责检查其来源，模型不能自行补造。
class RequestRefundInput(BaseModel):
    customer_id: int = Field(gt=0)
    order_id: int = Field(gt=0)
    reason: str = Field(min_length=1, max_length=2000)


def request_refund(
    session: Session,
    request: RequestRefundInput,
    *,
    idempotency: IdempotencyScope,
    tenant_id: str = "default",
) -> RefundRequestResponse:
    def load_result(refund_id: int) -> RefundRequestResponse:
        refund = session.scalar(
            select(RefundRequest).where(
                RefundRequest.id == refund_id, RefundRequest.tenant_id == tenant_id
            )
        )
        if refund is None:
            raise ResourceNotFoundError("Refund request", refund_id)
        return RefundRequestResponse.model_validate(refund)

    def perform() -> tuple[RefundRequestResponse, int]:
        # 即使上游已校验，实际写入前仍检查最新订单和退款申请状态。
        validate_refund_request(session, request, tenant_id=tenant_id)
        refund = RefundRequest(
            customer_id=request.customer_id,
            tenant_id=tenant_id,
            order_id=request.order_id,
            reason=request.reason,
            status=RefundStatus.REQUESTED,
        )
        session.add(refund)
        session.flush()
        return RefundRequestResponse.model_validate(refund), refund.id

    try:
        return execute_idempotent(
            session,
            scope=idempotency,
            operation="request_refund",
            customer_id=request.customer_id,
            request_payload=request.model_dump(mode="json"),
            perform=perform,
            load_result=load_result,
        )
    except IntegrityError as error:
        # 数据库唯一约束兜底，拦住并发创建的重复退款申请。
        raise DuplicateActionError(
            f"An active refund request already exists for order {request.order_id}"
        ) from error


def validate_refund_request(
    session: Session, request: RequestRefundInput, *, tenant_id: str = "default"
) -> None:
    order = session.scalar(
        select(Order).where(Order.id == request.order_id, Order.tenant_id == tenant_id)
    )
    if order is None:
        raise ResourceNotFoundError("Order", request.order_id)
    if order.customer_id != request.customer_id:
        raise OwnershipError("Order", request.order_id, request.customer_id)
    order_status = OrderStatus(order.status)
    if order_status != OrderStatus.DELIVERED:
        # 当前业务规则只接受已送达订单的退款申请。
        raise InvalidStateTransitionError(
            f"Order {order.id} is not eligible for a refund from status {order_status.value}"
        )
    # 同一订单存在这些进行中状态时，不再创建新的申请。
    active_statuses = {
        RefundStatus.REQUESTED,
        RefundStatus.APPROVED,
        RefundStatus.PROCESSING,
    }
    existing = session.scalar(
        select(RefundRequest)
        .where(RefundRequest.order_id == request.order_id)
        .where(RefundRequest.tenant_id == tenant_id)
        .where(RefundRequest.status.in_(active_statuses))
    )
    if existing is not None:
        raise DuplicateActionError(f"An active refund request already exists for order {order.id}")
