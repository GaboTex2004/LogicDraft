from abc import ABC, abstractmethod
from typing import Any


class BaseAIProvider(ABC):
    """Contract implemented by local and future remote AI providers."""

    @abstractmethod
    async def generate(self, prompt: str, json_schema: dict[str, Any] | None = None) -> str:
        raise NotImplementedError

    @abstractmethod
    async def is_available(self) -> bool:
        """Return provider reachability without raising on normal outages."""
        raise NotImplementedError


class ProviderError(Exception):
    """Base error safe to translate into an HTTP response."""


class ProviderConfigurationError(ProviderError):
    pass


class ProviderConnectionError(ProviderError):
    pass


class ProviderTimeoutError(ProviderError):
    pass


class ProviderResponseError(ProviderError):
    pass


class ProviderModelNotFoundError(ProviderError):
    pass
