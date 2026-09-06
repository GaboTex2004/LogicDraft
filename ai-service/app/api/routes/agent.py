from fastapi import APIRouter, HTTPException
from app.core.config import get_settings
from app.schemas.agent import AgentAskRequest, AgentAskResponse
from app.services.agent_service import AgentService
from app.services.ai_service import AIService
from app.services.providers.base import (
    ProviderConfigurationError, ProviderConnectionError, ProviderModelNotFoundError,
    ProviderResponseError, ProviderTimeoutError,
)

router = APIRouter(prefix="/agent", tags=["agent"])


@router.post("/ask", response_model=AgentAskResponse)
async def ask(request: AgentAskRequest) -> AgentAskResponse:
    try:
        return await AgentService(AIService.from_settings(get_settings())).ask(request)
    except (ProviderConfigurationError, ProviderConnectionError, ProviderModelNotFoundError):
        raise HTTPException(503, "El proveedor de IA no esta disponible.") from None
    except ProviderTimeoutError:
        raise HTTPException(504, "El proveedor de IA excedio el tiempo de espera.") from None
    except ProviderResponseError:
        raise HTTPException(502, "El proveedor no devolvio una respuesta valida.") from None
