"""把用户明确的记住/忘记表达提取为有限的记忆字段。

返回值为（待保存候选，待删除的键）；这里只提取，实际保存前还要经过记忆策略。
当前规则以英文短语匹配为主，中文注释不会自动增加中文识别能力。
"""

from __future__ import annotations

import re

from app.memory.schemas import MemoryCandidate, MemoryType


def extract_memory_request(message: str) -> tuple[MemoryCandidate | None, str | None]:
    normalized = " ".join(message.casefold().split())
    # 先识别遗忘，避免把 don't remember 中的 remember 当作保存请求。
    if "forget" in normalized or "don't remember" in normalized or "do not remember" in normalized:
        if "email" in normalized:
            return None, "contact_channel"
        if "concise" in normalized or "short" in normalized:
            return None, "response_style"
        return None, None
    if not (normalized.startswith("remember") or "remember that" in normalized):
        return None, None
    # 常见偏好映射到固定键，便于后续更新同一偏好或按键删除。
    if "email" in normalized:
        return (
            MemoryCandidate(
                memory_type=MemoryType.PREFERENCE,
                content="The customer prefers email updates.",
                normalized_key="contact_channel",
                explicit_user_request=True,
            ),
            None,
        )
    if "sms" in normalized or "text message" in normalized:
        return (
            MemoryCandidate(
                memory_type=MemoryType.PREFERENCE,
                content="The customer prefers SMS updates.",
                normalized_key="contact_channel",
                explicit_user_request=True,
            ),
            None,
        )
    if "concise" in normalized or "short" in normalized:
        return (
            MemoryCandidate(
                memory_type=MemoryType.PREFERENCE,
                content="The customer prefers concise answers.",
                normalized_key="response_style",
                explicit_user_request=True,
            ),
            None,
        )
    # 其他明确记忆请求作为支持上下文候选，是否允许持久化由 policy 决定。
    match = re.search(r"remember(?: that)? (.+)", normalized)
    if match:
        return (
            MemoryCandidate(
                memory_type=MemoryType.SUPPORT_CONTEXT,
                content=match.group(1),
                normalized_key="support_context",
                explicit_user_request=True,
            ),
            None,
        )
    return None, None
