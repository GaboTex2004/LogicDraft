from typing import Any
import logging
from time import perf_counter

import httpx

logger = logging.getLogger(__name__)

from app.services.providers.base import (
    BaseAIProvider,
    ProviderConfigurationError,
    ProviderConnectionError,
    ProviderModelNotFoundError,
    ProviderResponseError,
    ProviderTimeoutError,
)


class OllamaProvider(BaseAIProvider):
    def __init__(self, base_url: str, model: str, timeout: float) -> None:
        self._base_url = base_url.rstrip("/")
        self._model = model.strip()
        self._timeout = timeout

    async def generate(self, prompt: str, json_schema: dict[str, Any] | None = None) -> str:
        started = perf_counter()
        if not self._model:
            raise ProviderConfigurationError(
                "OLLAMA_MODEL is not configured. Set it in ai-service/.env before generating."
            )

        try:
            async with httpx.AsyncClient(timeout=self._timeout) as client:
                body: dict[str, Any] = {
                    "model": self._model,
                    "prompt": prompt,
                    "stream": False,
                    "keep_alive": "30m",
                    "options": {
                        "temperature": 0.2,
                        "num_predict": 300,
                    },
                }

                if json_schema is not None:
                    body.update({
                        "format": json_schema,
                        "options": {
                            "temperature": 0,
                            "num_predict": 3000,
                        },
                    })
                response = await client.post(
                    f"{self._base_url}/api/generate",
                    json=body,
                )
                response.raise_for_status()
        except httpx.TimeoutException:
            raise ProviderTimeoutError("Ollama did not respond before the configured timeout.") from None
        except httpx.ConnectError:
            raise ProviderConnectionError(
                "Could not connect to Ollama. Verify that it is running and OLLAMA_BASE_URL is correct."
            ) from None
        except httpx.HTTPStatusError as exc:
            if exc.response.status_code == 404:
                raise ProviderModelNotFoundError(
                    f"Ollama model '{self._model}' is not available. Pull it before generating."
                ) from None
            raise ProviderResponseError(
                f"Ollama returned an unexpected HTTP status ({exc.response.status_code})."
            ) from None
        except httpx.RequestError:
            raise ProviderConnectionError("The request to Ollama could not be completed.") from None

        try:
            payload: Any = response.json()
            result = payload["response"]
        except (ValueError, KeyError, TypeError):
            raise ProviderResponseError("Ollama returned an invalid response.") from None

        if not isinstance(result, str):
            raise ProviderResponseError("Ollama returned an invalid response.")
        metrics = {key: value for key in ("prompt_eval_count", "eval_count", "load_duration", "eval_duration")
                   if isinstance(value := payload.get(key), (int, float)) and not isinstance(value, bool)}
        logger.info("Ollama generation elapsedMs=%d promptChars=%d outputChars=%d structured=%s metrics=%s",
                    (perf_counter() - started) * 1000, len(prompt), len(result), json_schema is not None, metrics)
        if payload.get("done_reason") == "length":
            logger.warning("Ollama output reached token limit; normal validation must reject incomplete JSON")
        return result

    async def is_available(self) -> bool:
        try:
            async with httpx.AsyncClient(timeout=self._timeout) as client:
                response = await client.get(f"{self._base_url}/api/tags")
                return response.is_success
        except httpx.HTTPError:
            return False
