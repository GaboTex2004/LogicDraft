from typing import Any
from app.core.config import Settings
from app.services.providers.base import BaseAIProvider, ProviderConfigurationError
from app.services.providers.ollama_provider import OllamaProvider
from app.services.providers.gemini_provider import GeminiProvider

class AIService:
    def __init__(self, provider: BaseAIProvider) -> None:
        self._provider = provider

    @classmethod
    def from_settings(cls, settings: Settings) -> "AIService":
        provider_name = settings.ai_provider.strip().lower()
        if provider_name == "ollama":
            provider = OllamaProvider(
                base_url=settings.ollama_base_url,
                model=settings.ollama_model,
                timeout=settings.ai_request_timeout,
            )
            return cls(provider)
        if provider_name == "gemini":
            provider = GeminiProvider(
                api_key=settings.gemini_api_key,
                model=settings.gemini_model,
                timeout=settings.ai_request_timeout,
            )
            return cls(provider)

        raise ProviderConfigurationError(
            f"AI provider '{settings.ai_provider}' is not supported."
        )

    async def generate(self, prompt: str, json_schema: dict[str, Any] | None = None) -> str:
        return await self._provider.generate(prompt, json_schema)

    async def provider_available(self) -> bool:
        return await self._provider.is_available()
