from fastapi import APIRouter, HTTPException
from app.core.config import get_settings
from app.schemas.diagram import InterpretRequest, InterpretResponse
from app.services.ai_service import AIService
from app.services.diagram_ai_service import DiagramAIService
from app.services.diagram_ai_service import (
    DiagramIncompleteResponseError, DiagramJsonError, DiagramStructureError,
)
from app.services.diagram_normalizer import DiagramConflictError
from app.services.providers.base import (
    ProviderConfigurationError, ProviderConnectionError, ProviderTimeoutError,
    ProviderResponseError, ProviderModelNotFoundError,
)

router = APIRouter(prefix="/ai/diagram", tags=["ai"])


@router.post("/interpret", response_model=InterpretResponse)
async def interpret(request: InterpretRequest) -> InterpretResponse:
    try:
        return await DiagramAIService(AIService.from_settings(get_settings())).interpret(request.prompt, request.diagram)
    except (ProviderConfigurationError, ProviderConnectionError, ProviderModelNotFoundError):
        raise HTTPException(503, "El proveedor de IA no esta disponible.") from None
    except ProviderTimeoutError:
        raise HTTPException(504, "El proveedor de IA excedio el tiempo de espera.") from None
    except DiagramConflictError:
        raise HTTPException(502, "La respuesta de IA contiene definiciones de atributos en conflicto.") from None
    except DiagramIncompleteResponseError:
        raise HTTPException(422, "La IA no pudo completar todas las modificaciones solicitadas.") from None
    except DiagramJsonError:
        raise HTTPException(502, "La IA devolvio una respuesta que no es JSON valido.") from None
    except DiagramStructureError:
        raise HTTPException(502, "La IA devolvio JSON que no cumple el contrato de operaciones.") from None
    except ProviderResponseError:
        raise HTTPException(502, "El proveedor no devolvio operaciones validas.") from None
