"""把工具名称连接到输入模型和真实业务函数。

validate_tool 使用 input_model 校验参数，execute_tool 使用 execute 调用工具。
适配函数统一传递租户、客户和幂等信息，底层业务实现在 app/tools。
"""

from collections.abc import Callable
from dataclasses import dataclass
from typing import cast
from pydantic import BaseModel
from sqlalchemy.orm import Session
from app.core.context import ExecutionContext
from app.schemas.domain import (
    CustomerResponse,
    EscalationResponse,
    OrderResponse,
    RefundRequestResponse,
    TicketResponse,
)
from app.services.idempotency import IdempotencyScope
from app.tools import registry
from app.tools.base import OwnershipError
from app.tools.customers import (
    GetCustomerInput,
    get_customer,
    get_customer_orders,
    get_customer_tickets,
)
from app.tools.escalation import EscalateToHumanInput, escalate_to_human
from app.tools.orders import CancelOrderInput, GetOrderInput, cancel_order, get_order
from app.tools.refunds import RequestRefundInput, request_refund
from app.tools.tickets import (
    CreateSupportTicketInput,
    GetTicketInput,
    create_support_ticket,
    get_ticket,
)


@dataclass(frozen=True, slots=True)
class AgentToolDefinition:
    """单个工具的执行定义，包含参数校验模型和统一签名的执行函数。

    input_model：校验工具参数的 Pydantic 模型，由 validate_tool 节点使用。
    execute：适配后的执行函数，参数固定为 (数据库会话, 执行上下文, 已校验参数, 幂等范围)，
    由 execute_tool 节点调用。
    """

    # 每个工具都遵循同一调用约定，图节点无需为各业务重复写分发逻辑。
    input_model: type[BaseModel]
    execute: Callable[[Session, ExecutionContext, BaseModel, IdempotencyScope | None], object]


def _get_customer(
    session: Session,
    context: ExecutionContext,
    request: BaseModel,
    idempotency: IdempotencyScope | None,
) -> CustomerResponse:
    """查询当前登录客户的基本信息；只读操作，不使用幂等范围"""
    del idempotency
    return get_customer(
        session, cast(GetCustomerInput, _scoped(context, request)), tenant_id=context.tenant_id
    )


def _get_customer_orders(
    session: Session,
    context: ExecutionContext,
    request: BaseModel,
    idempotency: IdempotencyScope | None,
) -> list[OrderResponse]:
    """查询当前登录客户的订单列表；只读操作，不使用幂等范围"""
    del idempotency
    return get_customer_orders(
        session, cast(GetCustomerInput, _scoped(context, request)), tenant_id=context.tenant_id
    )


def _get_customer_tickets(
    session: Session,
    context: ExecutionContext,
    request: BaseModel,
    idempotency: IdempotencyScope | None,
) -> list[TicketResponse]:
    """查询当前登录客户的工单列表；只读操作，不使用幂等范围"""
    del idempotency
    return get_customer_tickets(
        session, cast(GetCustomerInput, _scoped(context, request)), tenant_id=context.tenant_id
    )


def _get_order(
    session: Session,
    context: ExecutionContext,
    request: BaseModel,
    idempotency: IdempotencyScope | None,
) -> OrderResponse:
    """查询当前客户指定订单的详情和状态；只读操作，不使用幂等范围"""
    del idempotency
    return get_order(
        session, cast(GetOrderInput, _scoped(context, request)), tenant_id=context.tenant_id
    )


def _get_ticket(
    session: Session,
    context: ExecutionContext,
    request: BaseModel,
    idempotency: IdempotencyScope | None,
) -> TicketResponse:
    """查询当前客户指定工单的详情；只读操作，不使用幂等范围"""
    del idempotency
    return get_ticket(
        session, cast(GetTicketInput, _scoped(context, request)), tenant_id=context.tenant_id
    )


def _create_ticket(
    session: Session,
    context: ExecutionContext,
    request: BaseModel,
    idempotency: IdempotencyScope | None,
) -> TicketResponse:
    """为当前客户创建客服工单；写操作，必须提供幂等范围"""
    return create_support_ticket(
        session,
        cast(CreateSupportTicketInput, _scoped(context, request)),
        idempotency=_require_idempotency(idempotency),
        tenant_id=context.tenant_id,
    )


def _cancel_order(
    session: Session,
    context: ExecutionContext,
    request: BaseModel,
    idempotency: IdempotencyScope | None,
) -> object:
    """取消当前客户的指定订单；写操作，必须提供幂等范围"""
    return cancel_order(
        session,
        cast(CancelOrderInput, _scoped(context, request)),
        idempotency=_require_idempotency(idempotency),
        tenant_id=context.tenant_id,
    )


def _request_refund(
    session: Session,
    context: ExecutionContext,
    request: BaseModel,
    idempotency: IdempotencyScope | None,
) -> RefundRequestResponse:
    """为当前客户的指定订单提交退款申请；写操作，必须提供幂等范围"""
    return request_refund(
        session,
        cast(RequestRefundInput, _scoped(context, request)),
        idempotency=_require_idempotency(idempotency),
        tenant_id=context.tenant_id,
    )


def _escalate_to_human(
    session: Session,
    context: ExecutionContext,
    request: BaseModel,
    idempotency: IdempotencyScope | None,
) -> EscalationResponse:
    """把当前客户的问题转交人工客服；写操作，必须提供幂等范围"""
    return escalate_to_human(
        session,
        cast(EscalateToHumanInput, _scoped(context, request)),
        idempotency=_require_idempotency(idempotency),
        tenant_id=context.tenant_id,
    )


def _scoped(context: ExecutionContext, request: BaseModel) -> BaseModel:
    """校验参数中的客户 ID 与登录身份一致，返回绑定当前客户的参数副本

    客户 ID 不一致时抛出 OwnershipError，防止越权访问其他客户的数据。
    """
    # 先拒绝与登录身份冲突的客户 ID，再生成绑定当前客户的参数副本。
    requested_customer = getattr(request, "customer_id", None)
    if requested_customer != context.effective_customer_id:
        resource_id = requested_customer if isinstance(requested_customer, int) else 0
        raise OwnershipError("Customer scope", resource_id, context.effective_customer_id)
    return request.model_copy(update={"customer_id": context.effective_customer_id})


def _require_idempotency(scope: IdempotencyScope | None) -> IdempotencyScope:
    """确保写操作带有幂等范围，缺失时抛出 ValueError，存在时原样返回"""
    # 所有业务写入必须有幂等标识，重复请求才能复用已执行结果。
    if scope is None:
        raise ValueError("Business writes require an idempotency key.")
    return scope


# 与 tools/registry.py 配套：前者描述风险，这里描述“怎么校验、怎么调用”。
TOOL_DEFINITIONS: dict[str, AgentToolDefinition] = {
    "get_customer": AgentToolDefinition(GetCustomerInput, _get_customer),
    "get_customer_orders": AgentToolDefinition(GetCustomerInput, _get_customer_orders),
    "get_order": AgentToolDefinition(GetOrderInput, _get_order),
    "get_customer_tickets": AgentToolDefinition(GetCustomerInput, _get_customer_tickets),
    "get_ticket": AgentToolDefinition(GetTicketInput, _get_ticket),
    "create_support_ticket": AgentToolDefinition(CreateSupportTicketInput, _create_ticket),
    "cancel_order": AgentToolDefinition(CancelOrderInput, _cancel_order),
    "request_refund": AgentToolDefinition(RequestRefundInput, _request_refund),
    "escalate_to_human": AgentToolDefinition(EscalateToHumanInput, _escalate_to_human),
}


def get_agent_tool_definition(name: str) -> AgentToolDefinition | None:
    """按工具名称查找执行定义；未在注册表登记或没有执行定义时返回 None"""
    # 只有同时出现在元数据注册表中的工具，才允许进入执行适配层。
    if name not in registry.TOOL_REGISTRY:
        return None
    return TOOL_DEFINITIONS.get(name)
