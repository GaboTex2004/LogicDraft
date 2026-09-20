from fastapi import APIRouter, HTTPException
from pydantic import ValidationError

from app.core.config import get_settings
from app.schemas.runtime import RuntimeInterpretRequest, RuntimeInterpretResponse, RuntimeSchema
from app.services.ai_service import AIService
from app.services.providers.base import (
    ProviderConfigurationError, ProviderConnectionError, ProviderModelNotFoundError,
    ProviderResponseError, ProviderTimeoutError,
)
from app.services.runtime_ai_service import RuntimeAIService

router = APIRouter(prefix="/runtime", tags=["runtime"])


@router.post("/interpret", response_model=RuntimeInterpretResponse)
async def interpret(request: RuntimeInterpretRequest) -> RuntimeInterpretResponse:
    try:
        schema = RuntimeSchema.model_validate_json(request.application_schema)
    except ValidationError:
        raise HTTPException(422, "Esquema de aplicacion invalido.") from None
    try:
        return await RuntimeAIService(AIService.from_settings(get_settings())).interpret(request.text, schema)
    except (ProviderConfigurationError, ProviderConnectionError, ProviderModelNotFoundError):
        raise HTTPException(503, "El proveedor de IA no esta disponible.") from None
    except ProviderTimeoutError:
        raise HTTPException(504, "El proveedor de IA excedio el tiempo de espera.") from None
    except ProviderResponseError:
        raise HTTPException(502, "El proveedor de IA devolvio una respuesta invalida.") from None
