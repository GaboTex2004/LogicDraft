from fastapi import APIRouter, HTTPException, status

from app.core.config import get_settings
from app.schemas.ai import GenerateRequest, GenerateResponse
from app.services.ai_service import AIService
from app.services.providers.base import (
    ProviderConfigurationError,
    ProviderConnectionError,
    ProviderModelNotFoundError,
    ProviderResponseError,
    ProviderTimeoutError,
)

router = APIRouter(prefix="/ai", tags=["ai"])


@router.post("/generate", response_model=GenerateResponse)
async def generate(request: GenerateRequest) -> GenerateResponse:
    try:
        service = AIService.from_settings(get_settings())
        result = await service.generate(request.prompt)
    except ProviderConfigurationError as exc:
        raise HTTPException(status_code=status.HTTP_503_SERVICE_UNAVAILABLE, detail=str(exc)) from None
    except ProviderConnectionError as exc:
        raise HTTPException(status_code=status.HTTP_503_SERVICE_UNAVAILABLE, detail=str(exc)) from None
    except ProviderTimeoutError as exc:
        raise HTTPException(status_code=status.HTTP_504_GATEWAY_TIMEOUT, detail=str(exc)) from None
    except ProviderModelNotFoundError as exc:
        raise HTTPException(status_code=status.HTTP_422_UNPROCESSABLE_ENTITY, detail=str(exc)) from None
    except ProviderResponseError as exc:
        raise HTTPException(status_code=status.HTTP_502_BAD_GATEWAY, detail=str(exc)) from None

    return GenerateResponse(content=result)
