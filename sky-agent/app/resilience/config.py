from collections.abc import Callable
from dataclasses import dataclass
from time import sleep
from app.core.config import Settings


@dataclass(frozen=True, slots=True)
class ResilienceConfig:
    enabled: bool = True  # 是否启用重试；关闭后每个操作只执行一次
    max_retries: int = 2  # 最大重试次数（不含首次调用），总尝试次数 = max_retries + 1
    initial_backoff_ms: int = 100  # 首次重试的退避等待时间（毫秒），之后按 2 的幂递增
    max_backoff_ms: int = 500  # 单次退避等待的上限（毫秒）
    jitter_ratio: float = 0.2  # 退避时间的随机抖动比例（±20%），避免大量请求同时重试
    retry_budget_attempts: int = 100  # 重试预算：单个服务在时间窗口内允许的最大重试次数
    retry_budget_window_seconds: float = 60.0  # 重试预算的滑动时间窗口（秒）
    max_retry_after_seconds: float = 30.0  # 服务端 Retry-After 响应头可接受的最大等待时间（秒）
    circuit_failure_threshold: int = 5  # 熔断阈值：连续失败达到该次数后打开熔断器
    circuit_recovery_seconds: float = 30.0  # 熔断打开后等待多久进入半开状态（秒）
    circuit_half_open_attempts: int = 1  # 半开状态下允许同时进行的试探调用数
    bulkhead_default_limit: int = 32  # 隔离舱：普通依赖（检索、工具等）的最大并发数
    bulkhead_provider_limit: int = 8  # 隔离舱：LLM 依赖的最大并发数
    bulkhead_wait_seconds: float = 0.0  # 隔离舱满时的等待时间（秒），0 表示立即拒绝
    principal_rate_limit: int = 600  # 每个调用者在时间窗口内允许的最大请求数
    customer_rate_limit: int = 600  # 每个客户在时间窗口内允许的最大请求数
    provider_rate_limit: int = 600  # 每个外部服务提供方在时间窗口内允许的最大调用数
    rate_limit_window_seconds: float = 60.0  # 限流的滑动时间窗口（秒）
    llm_timeout_seconds: float = 30.0  # 单次 LLM 调用超时（秒）
    retrieval_timeout_seconds: float = 5.0  # 单次知识/记忆检索超时（秒）
    reranker_timeout_seconds: float = 3.0  # 单次重排序调用超时（秒）
    tool_timeout_seconds: float = 10.0  # 单次工具调用超时（秒）

    @classmethod
    def from_settings(cls, settings: Settings) -> "ResilienceConfig":
        return cls(
            enabled=settings.resilience_enabled,
            max_retries=settings.resilience_max_retries,
            initial_backoff_ms=settings.resilience_initial_backoff_ms,
            max_backoff_ms=settings.resilience_max_backoff_ms,
            jitter_ratio=settings.resilience_jitter_ratio,
            retry_budget_attempts=settings.resilience_retry_budget_attempts,
            retry_budget_window_seconds=settings.resilience_retry_budget_window_seconds,
            max_retry_after_seconds=settings.resilience_max_retry_after_seconds,
            circuit_failure_threshold=settings.resilience_circuit_failure_threshold,
            circuit_recovery_seconds=settings.resilience_circuit_recovery_seconds,
            circuit_half_open_attempts=settings.resilience_circuit_half_open_attempts,
            bulkhead_default_limit=settings.resilience_bulkhead_default_limit,
            bulkhead_provider_limit=settings.resilience_bulkhead_provider_limit,
            bulkhead_wait_seconds=settings.resilience_bulkhead_wait_seconds,
            principal_rate_limit=settings.resilience_principal_rate_limit,
            customer_rate_limit=settings.resilience_customer_rate_limit,
            provider_rate_limit=settings.resilience_provider_rate_limit,
            rate_limit_window_seconds=settings.resilience_rate_limit_window_seconds,
            llm_timeout_seconds=settings.llm_timeout_seconds,
            retrieval_timeout_seconds=settings.retrieval_timeout_seconds,
            reranker_timeout_seconds=settings.rag_reranker_timeout_seconds,
            tool_timeout_seconds=settings.tool_timeout_seconds,
        )


Sleeper = Callable[[float], None]


def default_sleeper(seconds: float) -> None:
    sleep(seconds)
