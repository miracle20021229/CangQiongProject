"""客户资料、客户订单列表和客户工单列表的只读工具。

调用方传入客户与租户范围，具体查询交给 customer_service，结果转换为响应模型。
"""

from pydantic import BaseModel, Field
from sqlalchemy.orm import Session

from app.schemas.domain import CustomerResponse, OrderResponse, TicketResponse
from app.services import customer_service
from app.tools.base import ResourceNotFoundError


# 三个工具共用客户 ID 入参；登录身份约束由上层适配器一并校验。
class GetCustomerInput(BaseModel):
    customer_id: int = Field(gt=0)


def get_customer(
    session: Session, request: GetCustomerInput, *, tenant_id: str = "default"
) -> CustomerResponse:
    customer = customer_service.get_customer_for_scope(
        session, request.customer_id, request.customer_id, tenant_id
    )
    if customer is None:
        raise ResourceNotFoundError("Customer", request.customer_id)
    # 将数据库实体转换为约定的返回字段。
    return CustomerResponse.model_validate(customer)


# 返回客户范围内的订单列表，供“我的订单有哪些”这类诉求使用。
def get_customer_orders(
    session: Session, request: GetCustomerInput, *, tenant_id: str = "default"
) -> list[OrderResponse]:
    customer = customer_service.get_customer(session, request.customer_id, tenant_id)
    if customer is None:
        raise ResourceNotFoundError("Customer", request.customer_id)
    return [
        OrderResponse.model_validate(order)
        for order in customer_service.get_customer_orders(session, request.customer_id, tenant_id)
    ]


# 返回客户范围内的工单列表，供后续查询某个工单时定位目标。
def get_customer_tickets(
    session: Session, request: GetCustomerInput, *, tenant_id: str = "default"
) -> list[TicketResponse]:
    customer = customer_service.get_customer(session, request.customer_id, tenant_id)
    if customer is None:
        raise ResourceNotFoundError("Customer", request.customer_id)
    return [
        TicketResponse.model_validate(ticket)
        for ticket in customer_service.get_customer_tickets(session, request.customer_id, tenant_id)
    ]
