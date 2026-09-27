import asyncio
import logging
from typing import Any

from google import genai
from google.genai import errors, types
from copy import deepcopy

from app.services.diagram_gemini_schema import (
    conversion_attribute_count,
    gemini_diagram_plan_schema,
    is_diagram_plan_schema,
)

from app.services.providers.base import (
    BaseAIProvider,
    ProviderConfigurationError,
    ProviderConnectionError,
    ProviderResponseError,
    ProviderTimeoutError,
)

logger = logging.getLogger(__name__)

def adapt_schema_for_gemini(schema: dict[str, Any]) -> dict[str, Any]:
    if is_diagram_plan_schema(schema):
        return gemini_diagram_plan_schema(conversion_attribute_count(schema))
    adapted = deepcopy(schema)

    def clean(node: Any) -> None:
        if isinstance(node, dict):
            node.pop("pattern", None)

            if "const" in node:
                node["enum"] = [node.pop("const")]

            for value in node.values():
                clean(value)

        elif isinstance(node, list):
            for item in node:
                clean(item)

    clean(adapted)
    return adapted
class GeminiProvider(BaseAIProvider):
    def __init__(self, api_key: str, model: str, timeout: float) -> None:
        self._api_key = api_key.strip()
        self._model = model.strip()
        self._timeout = timeout

    async def generate(
        self,
        prompt: str,
        json_schema: dict[str, Any] | None = None,
    ) -> str:
        if not self._api_key or not self._model:
            raise ProviderConfigurationError(
                "GEMINI_API_KEY y GEMINI_MODEL deben estar configurados."
            )

        client = genai.Client(api_key=self._api_key)

        try:
            config = types.GenerateContentConfig(
                temperature=0 if json_schema is not None else 0.2,
                automatic_function_calling=types.AutomaticFunctionCallingConfig(
                    disable=True
                ),
                response_mime_type=(
                    "application/json" if json_schema is not None else "text/plain"
                ),
                response_json_schema=(
                    adapt_schema_for_gemini(json_schema) if json_schema is not None else None
                ),
            )

            logger.info(
                "GEMINI_REQUEST: model=%s uses_tools=false uses_afc=false "
                "uses_schema=%s response_mime_type=%s",
                self._model,
                json_schema is not None,
                config.response_mime_type,
            )

            response = await asyncio.wait_for(
                client.aio.models.generate_content(
                    model=self._model,
                    contents=prompt,
                    config=config,
                ),
                timeout=self._timeout,
            )

            if not response.text:
                raise ProviderResponseError(
                    "Gemini no devolvió una respuesta de texto."
                )

            return response.text

        except TimeoutError:
            raise ProviderTimeoutError(
                "Gemini superó el tiempo de espera."
            ) from None

        except errors.APIError as exc:
            logger.warning(
                "GEMINI_DIAGNOSTICO: code=%s error_type=%s",
                exc.code,
                type(exc).__name__,
            )
            raise ProviderResponseError(
                f"Gemini devolvió un error HTTP ({exc.code})."
            ) from None

        except ProviderResponseError:
            raise

        finally:
            client.close()

    async def is_available(self) -> bool:
        return bool(self._api_key and self._model)
