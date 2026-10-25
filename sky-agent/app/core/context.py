"""单次 Agent 请求的执行上下文（身份与客户范围）。"""

from collections.abc import Iterator
from typing import Any
from pydantic import BaseModel, ConfigDict, Field, field_serializer, model_validator
from app.auth.models import ActorType, Principal


class ExecutionContext(BaseModel):
    """由服务端持有的单次 Agent 请求身份与客户范围。

    该对象由服务端根据认证结果构造，不信任来自 LLM 或客户端的身份信息，
    用于在整个请求链路中传递"谁在为哪个客户、在哪个租户下执行操作"。
    """

    # frozen：创建后不可修改；extra="forbid"：禁止传入未声明的字段
    model_config = ConfigDict(frozen=True, extra="forbid")

    # 请求唯一标识，用于链路追踪与审计
    request_id: str = Field(min_length=1, max_length=200)
    # 会话标识，关联同一对话中的多轮请求
    conversation_id: str = Field(min_length=1, max_length=200)
    # 已认证的调用主体（客服、客户或服务账号）
    principal: Principal
    # 租户标识，未显式指定时会尝试继承 principal 的租户
    tenant_id: str = Field(default="default", min_length=1, max_length=200)
    # 本次请求实际作用的客户 ID，必须为正整数
    effective_customer_id: int = Field(gt=0)

    @model_validator(mode="after")
    def validate_customer_actor_scope(self) -> "ExecutionContext":
        """校验租户与客户范围，防止越权访问。

        - 若 tenant_id 为默认值且 principal 带有租户，则继承 principal 的租户；
        - principal 的租户必须与上下文租户一致；
        - 客户本人调用时，只能操作自己的 customer_id。

        Raises:
            ValueError: 租户或客户范围不匹配时抛出。
        """
        if self.principal.tenant_id is not None and self.tenant_id == "default":
            # 模型为 frozen，需绕过 __setattr__ 限制完成赋值
            object.__setattr__(self, "tenant_id", self.principal.tenant_id)
        if self.principal.tenant_id is not None and self.principal.tenant_id != self.tenant_id:
            raise ValueError("Principal does not match tenant scope.")
        if (
            self.principal.actor_type == ActorType.CUSTOMER
            and self.principal.customer_id != self.effective_customer_id
        ):
            raise ValueError("Customer principal does not match effective customer scope.")
        return self

    def safe_metadata(self) -> dict[str, str | int | list[str] | list[int]]:
        """返回可安全用于日志、追踪与审计的上下文元数据。

        仅包含白名单字段，不输出 principal 的完整信息。
        """
        return {
            "request_id": self.request_id,
            "conversation_id": self.conversation_id,
            "actor_id": self.principal.actor_id,
            "actor_type": self.principal.actor_type.value,
            "principal_type": self.principal.principal_type.value,
            "tenant_id": self.tenant_id,
            "roles": list(self.principal.roles),
            "effective_customer_id": self.effective_customer_id,
        }

    @field_serializer("principal")
    def serialize_principal(self, principal: Principal) -> dict[str, object]:
        """序列化 principal 字段时只输出指定的字段。

        将枚举转为字符串值，并复制列表，保证结果可被 JSON 序列化。
        """
        return {
            "actor_id": principal.actor_id,
            "actor_type": principal.actor_type.value,
            "principal_type": principal.principal_type.value,
            "roles": list(principal.roles),
            "groups": list(principal.groups),
            "tenant_id": principal.tenant_id,
            "customer_id": principal.customer_id,
            "customer_ids": list(principal.customer_ids),
        }

    def __repr_args__(self) -> Iterator[tuple[str | None, Any]]:
        """自定义 repr 输出，仅展示安全元数据，避免日志打印敏感信息。"""
        return iter(self.safe_metadata().items())
