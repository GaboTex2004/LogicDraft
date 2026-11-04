import logging
from pathlib import Path

from fastapi import APIRouter, File, Form, HTTPException, UploadFile
from google.genai import errors as gemini_errors
import httpx
from pydantic import ValidationError

from app.schemas.image import ImageDocument, ImageRecognition
from app.services.diagram_ai_service import (
    DiagramJsonError,
    DiagramStructureError,
)
from app.services.diagram_normalizer import DiagramConflictError
from app.services.gemini_vision_service import GeminiVisionService

logger = logging.getLogger(__name__)

router = APIRouter(prefix="/ai/image", tags=["image"])

MAX_IMAGE_BYTES = 5 * 1024 * 1024

ALLOWED_FORMATS = {
    ".jpg": "image/jpeg",
    ".jpeg": "image/jpeg",
    ".png": "image/png",
    ".webp": "image/webp",
}


def detect_image_type(content: bytes) -> str | None:
    if content.startswith(b"\xff\xd8\xff"):
        return "image/jpeg"

    if content.startswith(b"\x89PNG\r\n\x1a\n"):
        return "image/png"

    if (
        len(content) >= 12
        and content.startswith(b"RIFF")
        and content[8:12] == b"WEBP"
    ):
        return "image/webp"

    return None


@router.post("/interpret", response_model=ImageRecognition, response_model_exclude_none=True)
async def interpret_image(
    image: UploadFile = File(...),
    prompt: str = Form(""),
    diagram: str | None = Form(None),
) -> ImageRecognition:

    extension = Path(image.filename or "").suffix.lower()

    if extension not in ALLOWED_FORMATS:
        raise HTTPException(
            status_code=415,
            detail="Formato no permitido. Utiliza JPG, PNG o WEBP.",
        )

    if len(prompt) > 10000:
        raise HTTPException(
            status_code=422,
            detail="La instrucción supera los 10000 caracteres.",
        )

    try:
        content = await image.read(MAX_IMAGE_BYTES + 1)
    finally:
        await image.close()

    if not content:
        raise HTTPException(
            status_code=400,
            detail="La imagen está vacía.",
        )

    if len(content) > MAX_IMAGE_BYTES:
        raise HTTPException(
            status_code=413,
            detail="La imagen supera el límite de 5 MB.",
        )

    detected_type = detect_image_type(content)

    if detected_type != ALLOWED_FORMATS[extension]:
        raise HTTPException(
            status_code=415,
            detail="El contenido no corresponde al formato de imagen indicado.",
        )

    context = None

    if diagram:
        try:
            if len(diagram) > 1_000_000:
                raise ValueError("Contexto demasiado grande")
            context = ImageDocument.model_validate_json(diagram)
        except ValueError:
            raise HTTPException(
                status_code=422,
                detail="El contexto del diagrama no es válido.",
            ) from None

    try:
        return await GeminiVisionService().interpret(
            image=content,
            mime_type=detected_type,
            prompt=prompt,
            diagram=context,
        )
    except DiagramConflictError:
        logger.warning("Image interpretation conflict in AI response")
        raise HTTPException(
            status_code=502,
            detail="La respuesta de IA contiene definiciones de atributos en conflicto.",
        ) from None

    except DiagramJsonError:
        logger.warning("Gemini Vision returned invalid JSON")
        raise HTTPException(
            status_code=502,
            detail="La IA devolvió una respuesta que no es JSON válido.",
        ) from None

    except (DiagramStructureError, ValidationError) as exception:
        logger.warning(
            "Gemini Vision response failed image-v1 validation (%s)",
            type(exception).__name__,
        )
        raise HTTPException(
            status_code=502,
            detail="Gemini devolvió una respuesta que no cumple el contrato image-v1.",
        ) from None

    except gemini_errors.APIError as exception:
        code = exception.code
        logger.warning(
            "Gemini Vision APIError (code=%s, status=%s, mime=%s, bytes=%d)",
            code,
            getattr(exception, "status", None),
            detected_type,
            len(content),
        )

        if code == 429:
            raise HTTPException(
                status_code=503,
                detail="Gemini alcanzó su límite de solicitudes o cuota. Inténtalo más tarde.",
            ) from None

        if code in (401, 403):
            raise HTTPException(
                status_code=503,
                detail="Gemini no está disponible por un problema de configuración o permisos.",
            ) from None

        if code == 404:
            raise HTTPException(
                status_code=503,
                detail="El modelo de Gemini configurado no está disponible.",
            ) from None

        if code in (408, 504):
            raise HTTPException(
                status_code=504,
                detail="Gemini excedió el tiempo de espera.",
            ) from None

        if code in (500, 502, 503):
            raise HTTPException(
                status_code=503,
                detail="Gemini presenta un problema temporal. Inténtalo más tarde.",
            ) from None

        raise HTTPException(
            status_code=502,
            detail="Gemini rechazó la solicitud de interpretación.",
        ) from None

    except (httpx.TimeoutException, TimeoutError):
        logger.warning("Gemini Vision request timed out (mime=%s, bytes=%d)", detected_type, len(content))
        raise HTTPException(
            status_code=504,
            detail="Se agotó el tiempo de conexión con Gemini.",
        ) from None

    except httpx.RequestError as exception:
        logger.warning("Gemini Vision network error (%s)", type(exception).__name__)
        raise HTTPException(
            status_code=503,
            detail="No se pudo establecer conexión con Gemini.",
        ) from None

    except ValueError as exception:
        if "no esta configurado" in str(exception).lower():
            logger.warning("Gemini Vision is not configured")
            raise HTTPException(
                status_code=503,
                detail="Gemini no está configurado en el servicio de IA.",
            ) from None
        logger.warning("Gemini Vision returned invalid value (%s)", type(exception).__name__)
        raise HTTPException(
            status_code=502,
            detail="Gemini no devolvió una interpretación válida.",
        ) from None
