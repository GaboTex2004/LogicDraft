from fastapi import APIRouter

from app.core.config import get_settings
from app.schemas.ai import HealthResponse
from app.services.ai_service import AIService
from app.services.providers.base import ProviderConfigurationError

router = APIRouter(tags=["health"])


@router.get("/health", response_model=HealthResponse)
async def health_check() -> HealthResponse:
    """Report API health even when the configured provider is unavailable."""
    settings = get_settings()
    provider_available = False
    try:
        provider_available = await AIService.from_settings(settings).provider_available()
    except ProviderConfigurationError:
        pass
    return HealthResponse(
        status="ok",
        provider=settings.ai_provider,
        providerAvailable=provider_available,
    )
