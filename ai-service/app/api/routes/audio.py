from pathlib import Path
import logging

from fastapi import APIRouter, File, HTTPException, UploadFile
from groq import (
    APIConnectionError,
    APIStatusError,
    APITimeoutError,
    AsyncGroq,
)

from app.core.config import get_settings


router = APIRouter(prefix="/audio", tags=["audio"])
logger = logging.getLogger(__name__)

MAX_AUDIO_BYTES = 10 * 1024 * 1024

ALLOWED_EXTENSIONS = {
    ".wav",
    ".mp3",
    ".m4a",
    ".ogg",
    ".webm",
    ".mp4",
    ".flac",
}


@router.post("/transcribe")
async def transcribe_audio(
    audio: UploadFile = File(...),
) -> dict[str, str]:

    settings = get_settings()

    if not settings.groq_api_key:
        logger.warning("Audio transcription unavailable: GROQ_API_KEY is not configured")
        raise HTTPException(
            status_code=503,
            detail="Groq no está configurado en el servidor.",
        )

    extension = Path(audio.filename or "").suffix.lower()

    if extension not in ALLOWED_EXTENSIONS:
        raise HTTPException(
            status_code=415,
            detail="Formato de audio no permitido.",
        )

    try:
        content = await audio.read(MAX_AUDIO_BYTES + 1)
    finally:
        await audio.close()

    if not content:
        raise HTTPException(
            status_code=400,
            detail="El archivo de audio está vacío.",
        )

    if len(content) > MAX_AUDIO_BYTES:
        raise HTTPException(
            status_code=413,
            detail="El audio supera el límite de 10 MB.",
        )

    logger.info(
        "Transcription request received audio_bytes=%d model=%s extension=%s",
        len(content), settings.groq_whisper_model, extension,
    )

    try:
        async with AsyncGroq(
            api_key=settings.groq_api_key,
            timeout=60.0,
        ) as client:

            result = await client.audio.transcriptions.create(
                file=(
                    f"recording{extension}",
                    content,
                    audio.content_type or "application/octet-stream",
                ),
                model=settings.groq_whisper_model,
                language="es",
                response_format="json",
            )

    except APITimeoutError:
        logger.warning("Groq transcription failed category=timeout model=%s", settings.groq_whisper_model)
        raise HTTPException(
            status_code=504,
            detail="Groq tardó demasiado en transcribir el audio.",
        ) from None

    except APIConnectionError:
        logger.warning("Groq transcription failed category=connection model=%s", settings.groq_whisper_model)
        raise HTTPException(
            status_code=503,
            detail="No se pudo conectar con Groq.",
        ) from None

    except APIStatusError as exc:
        logger.warning(
            "Groq transcription failed category=status status=%d model=%s",
            exc.status_code, settings.groq_whisper_model,
        )
        if exc.status_code == 429:
            raise HTTPException(
                status_code=503,
                detail="Groq alcanzó su límite de solicitudes.",
            ) from None

        raise HTTPException(
            status_code=502,
            detail="Groq rechazó la solicitud de transcripción.",
        ) from None

    text = result.text.strip()

    if not text:
        logger.info("Transcription completed without detected text model=%s", settings.groq_whisper_model)
        raise HTTPException(
            status_code=422,
            detail="No se detectó texto en el audio.",
        )

    logger.info("Transcription completed text_chars=%d model=%s", len(text), settings.groq_whisper_model)
    return {"text": text}
