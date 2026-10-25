"""用户记忆的检索、保存与遗忘入口，供记忆相关图节点调用。

policy 判断能否保存，repository 负责数据库读写；本层组织去重、过期和可见范围。
记忆用于补充回答上下文，不提供业务权限或订单状态。
"""

from __future__ import annotations

from datetime import UTC, datetime, timedelta

from sqlalchemy.orm import Session

from app.auth.models import ActorType, Principal
from app.memory.compaction import compact_customer_memory
from app.memory.models import MemoryRecord
from app.memory.policy import evaluate_candidate
from app.memory.repository import MemoryRepository
from app.memory.retrieval import score_memory
from app.memory.schemas import (
    MemoryCandidate,
    MemoryOperationResult,
    MemoryRecordView,
    MemoryRetentionPolicy,
    MemorySensitivityLevel,
    MemorySource,
    MemoryStatus,
    MemoryStorageEligibility,
    MemoryType,
)
from app.observability.metrics import get_metrics
from app.observability.tracing import span


class MemoryService:
    def __init__(
        self,
        *,
        enabled: bool = True,
        max_context_items: int = 5,
        default_ttl_days: int = 365,
        support_context_ttl_days: int = 30,
        repository: MemoryRepository | None = None,
    ) -> None:
        self.enabled = enabled
        self.max_context_items = max_context_items
        self.default_ttl_days = default_ttl_days
        self.support_context_ttl_days = support_context_ttl_days
        self.repository = repository or MemoryRepository()

    def retrieve(
        self,
        session: Session,
        customer_id: int,
        query: str,
        now: datetime | None = None,
        principal: Principal | None = None,
    ) -> list[MemoryRecordView]:
        # 先筛选未过期且当前身份可见的记忆，再按相关性排序。
        if not self.enabled:
            return []
        current = now or datetime.now(UTC).replace(tzinfo=None)
        tenant_id = (principal.tenant_id if principal is not None else None) or "default"
        active = [
            record
            for record in self.repository.active_for_customer(session, customer_id, tenant_id)
            if record.expires_at is None or record.expires_at > current
        ]
        allowed: list[MemoryRecord] = []
        for record in active:
            if not _can_retrieve(record, customer_id, principal):
                get_metrics().memory_sensitive_retrieval_blocked.add(
                    1, {"reason": "scope_or_sensitivity"}
                )
                continue
            allowed.append(record)
        ranked = sorted(
            allowed, key=lambda record: score_memory(record, query, current), reverse=True
        )
        # 限制进入模型上下文的条数，避免带入无关历史。
        return [self._view(record) for record in ranked[: self.max_context_items]]

    def remember(
        self,
        session: Session,
        customer_id: int,
        candidate: MemoryCandidate,
        *,
        source: MemorySource = MemorySource.USER_EXPLICIT,
        now: datetime | None = None,
        retention_policy: MemoryRetentionPolicy = MemoryRetentionPolicy.STANDARD,
        tenant_id: str = "default",
    ) -> MemoryOperationResult:
        if not self.enabled:
            return MemoryOperationResult(status="disabled", reason="memory_disabled")
        if retention_policy == MemoryRetentionPolicy.NO_STORE:
            return MemoryOperationResult(status="reject", reason="retention_policy_no_store")
        current = now or datetime.now(UTC).replace(tzinfo=None)
        # 保存前检查敏感内容、权限声明和用户授权；必要时使用脱敏后的内容。
        policy = evaluate_candidate(candidate)
        if policy.reason.startswith("dlp_"):
            get_metrics().memory_dlp_rejected.add(1, {"level": "restricted"})
        if policy.outcome != "allow" or policy.candidate is None:
            return MemoryOperationResult(
                status=policy.outcome,
                reason=policy.reason,
                security_signal=policy.security_signal,
            )
        normalized = policy.candidate
        if policy.storage_eligibility == MemoryStorageEligibility.REDACT:
            get_metrics().memory_dlp_redacted.add(1, {"level": policy.sensitivity_level.value})
        else:
            get_metrics().memory_dlp_allowed.add(1, {"level": policy.sensitivity_level.value})
        # 同一客户与记忆键：相同内容刷新时间，不同内容将旧记录标为已替代。
        existing = self.repository.active_by_key(
            session, customer_id, normalized.normalized_key, tenant_id
        )
        for record in existing:
            if record.content == normalized.content:
                record.updated_at = current
                record.confidence = max(record.confidence, normalized.confidence)
                session.commit()
                return MemoryOperationResult(status="deduplicated", record=self._view(record))
            self.repository.mark_status(record, MemoryStatus.SUPERSEDED, current)
        expires_at = self._expiry_for(normalized.memory_type, current, policy.retention_policy)
        record = MemoryRecord(
            customer_id=customer_id,
            tenant_id=tenant_id,
            memory_type=normalized.memory_type,
            content=normalized.content,
            normalized_key=normalized.normalized_key,
            source=source,
            confidence=normalized.confidence,
            created_at=current,
            updated_at=current,
            expires_at=expires_at,
            status=MemoryStatus.ACTIVE,
            sensitivity_level=policy.sensitivity_level,
            retention_policy=policy.retention_policy,
            redaction_state=policy.redaction_state,
        )
        session.add(record)
        session.commit()  # 记忆服务在这里提交本次保存，随后返回可展示的记录。
        return MemoryOperationResult(status="persisted", record=self._view(record))

    def forget(
        self,
        session: Session,
        customer_id: int,
        normalized_key: str,
        now: datetime | None = None,
        tenant_id: str = "default",
    ) -> MemoryOperationResult:
        if not self.enabled:
            return MemoryOperationResult(status="disabled", reason="memory_disabled")
        current = now or datetime.now(UTC).replace(tzinfo=None)
        records = self.repository.active_by_key(session, customer_id, normalized_key, tenant_id)
        if not records:
            return MemoryOperationResult(status="not_found", reason="memory_not_found")
        for record in records:
            # 遗忘采用逻辑删除：记录不再参与有效记忆检索。
            self.repository.mark_status(record, MemoryStatus.DELETED, current)
        session.commit()
        return MemoryOperationResult(status="forgotten", affected_count=len(records))

    def compact(
        self,
        session: Session,
        customer_id: int,
        now: datetime | None = None,
        tenant_id: str = "default",
    ) -> int:
        with span("memory.compact") as memory_span:
            compacted = compact_customer_memory(
                session,
                customer_id,
                now or datetime.now(UTC).replace(tzinfo=None),
                self.repository,
                tenant_id=tenant_id,
            )
            memory_span.set_attribute("memory.status", "compacted")
            memory_span.set_attribute("memory.result_count", compacted)
            return compacted

    def _expiry_for(
        self,
        memory_type: MemoryType,
        now: datetime,
        retention_policy: MemoryRetentionPolicy = MemoryRetentionPolicy.STANDARD,
    ) -> datetime | None:
        # 支持上下文等短期信息使用较短期限；长期偏好使用默认保留期限。
        if retention_policy == MemoryRetentionPolicy.NO_STORE:
            return now
        if retention_policy == MemoryRetentionPolicy.SHORT:
            return now + timedelta(days=self.support_context_ttl_days)
        if memory_type in {
            MemoryType.SUPPORT_CONTEXT,
            MemoryType.UNRESOLVED_ISSUE,
            MemoryType.INTERACTION_SUMMARY,
        }:
            return now + timedelta(days=self.support_context_ttl_days)
        return now + timedelta(days=self.default_ttl_days) if self.default_ttl_days > 0 else None

    @staticmethod
    def _view(record: MemoryRecord) -> MemoryRecordView:
        return MemoryRecordView.model_validate(record, from_attributes=True)


def _can_retrieve(record: MemoryRecord, customer_id: int, principal: Principal | None) -> bool:
    # 客户、租户、敏感级别与操作者角色都要符合条件，才能进入模型上下文。
    if record.customer_id != customer_id:
        return False
    if principal is not None and principal.tenant_id is not None:
        if record.tenant_id != principal.tenant_id:
            return False
    if record.sensitivity_level == MemorySensitivityLevel.RESTRICTED:
        return False
    if principal is None:
        return True
    if principal.actor_type == ActorType.CUSTOMER:
        return principal.customer_id == customer_id
    if principal.actor_type == ActorType.SUPPORT_OPERATOR:
        return "support_operator" in principal.roles
    return False
