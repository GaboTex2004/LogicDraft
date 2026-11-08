"""Generate a small, reviewable voice audition; never used by public API routes."""
import argparse
import html
import io
import os
from pathlib import Path
import wave

VOICES = ("Kore", "Puck", "Zephyr")
MODEL = "gemini-2.5-flash-preview-tts"
TEXT = (
    "Hola, soy tu guía de LogicDraft. Vamos a crear tu primer diagrama, paso a paso. "
    "Escribe el nombre que quieras para tu proyecto y pulsa Crear proyecto. "
    "Cuando aparezca en la lista, pulsa Abrir. Después crearemos una clase y sus atributos. "
    "Puedes pausar la guía o repetir una instrucción cuando lo necesites."
)
PROMPT = (
    "Lee únicamente el siguiente texto en español latinoamericano, con tono cercano, "
    "claro y tranquilo de instructor. Ritmo moderado, pausas naturales, sin música, "
    "sin añadir explicaciones. Pronuncia LogicDraft como Logic Draft.\n\n" + TEXT
)


def audio_as_wav(data: bytes, mime_type: str) -> bytes:
    """Gemini 2.5 returns raw mono signed PCM16 LE at 24 kHz; validate other formats."""
    if not data or len(data) > 20_000_000:
        raise ValueError("Invalid audio size")
    mime = mime_type.lower().replace(" ", "")
    if mime.split(";")[0] in ("audio/wav", "audio/x-wav"):
        with wave.open(io.BytesIO(data), "rb") as audio:
            if audio.getnframes() == 0 or audio.getcomptype() != "NONE":
                raise ValueError("Invalid WAV")
        return data
    if mime.split(";")[0] not in ("audio/l16", "audio/pcm") or "rate=24000" not in mime or len(data) % 2:
        raise ValueError("Unsupported audio format; expected PCM16 at 24 kHz")
    result = io.BytesIO()
    with wave.open(result, "wb") as audio:
        audio.setnchannels(1)
        audio.setsampwidth(2)
        audio.setframerate(24000)
        audio.writeframes(data)
    return result.getvalue()


def extract_audio(response) -> bytes:
    candidates = response.candidates or []
    if not candidates or not candidates[0].content:
        raise ValueError("No audio candidate")
    parts = candidates[0].content.parts or []
    audio_parts = [part.inline_data for part in parts if part.inline_data and part.inline_data.mime_type.startswith("audio/")]
    if len(audio_parts) != 1:
        raise ValueError("Expected one audio part")
    return audio_as_wav(audio_parts[0].data, audio_parts[0].mime_type)


def preview_html(voices: list[str], model: str) -> str:
    cards = "".join(
        f'<section><h2>{html.escape(voice)}</h2><audio controls preload="none" src="{voice}.wav"></audio></section>'
        for voice in voices
    )
    return f'''<!doctype html><html lang="es"><meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>Muestras de voz — LogicDraft</title>
<style>body{{background:#18181b;color:#e5e1e4;font:16px/1.6 system-ui;max-width:760px;margin:40px auto;padding:20px}}h1,h2{{color:#b8c4ff}}section{{padding:16px;border:1px solid #454650;border-radius:12px;margin:16px 0}}audio{{width:100%}}</style>
<h1>Elige la voz del instructor</h1><p>Audio generado por IA. Escucha con el mismo volumen y compara claridad, acento, pausas y pronunciación de LogicDraft.</p>
<p>Modelo: {html.escape(model)}</p><blockquote>{html.escape(TEXT)}</blockquote>{cards}
<p>Estas muestras no se reproducen automáticamente ni están integradas todavía en el tutor.</p></html>'''


def main() -> int:
    parser = argparse.ArgumentParser(description="Generate up to three fixed-text Gemini TTS samples")
    parser.add_argument("--voice", action="append", choices=VOICES, help="Repeat to compare voices; defaults to Kore and Puck")
    parser.add_argument("--model", default=os.environ.get("TUTOR_TTS_MODEL", MODEL))
    args = parser.parse_args()
    voices = list(dict.fromkeys(args.voice or ["Kore", "Puck"]))
    output = Path(__file__).resolve().parents[2] / "voice-samples.local"
    if any((output / f"{voice}.wav").exists() for voice in voices):
        print("Ya hay muestras para una voz seleccionada. Escúchalas primero; no se sobrescribieron archivos ni se llamó a la API.")
        return 1
    # Existing server configuration loads the key; never print settings or provider exceptions.
    from app.core.config import get_settings
    from google import genai
    from google.genai import types

    settings = get_settings()
    if not settings.gemini_api_key:
        print("Falta GEMINI_API_KEY en la configuración del ai-service. No se hicieron solicitudes.")
        return 1
    output.mkdir(exist_ok=True)
    completed = []
    with genai.Client(api_key=settings.gemini_api_key, http_options=types.HttpOptions(timeout=90_000)) as client:
        for voice in voices:
            try:
                response = client.models.generate_content(
                    model=args.model, contents=PROMPT,
                    config=types.GenerateContentConfig(response_modalities=["AUDIO"], speech_config=types.SpeechConfig(
                        voice_config=types.VoiceConfig(prebuilt_voice_config=types.PrebuiltVoiceConfig(voice_name=voice)))),
                )
                audio = extract_audio(response)
                with (output / f"{voice}.wav").open("xb") as target:
                    target.write(audio)
                completed.append(voice)
                print(f"Muestra creada: {voice}.wav")
            except Exception as error:
                code = getattr(error, "code", None)
                safe_code = code if isinstance(code, int) else "no disponible"
                print(f"No se generó {voice}. Código del proveedor: {safe_code}. No se reintentó automáticamente.")
                break
    if completed:
        (output / "index.html").write_text(preview_html(completed, args.model), encoding="utf-8")
        print("Abre ai-service/voice-samples.local/index.html para escuchar las muestras.")
    return 0 if len(completed) == len(voices) else 1


if __name__ == "__main__":
    raise SystemExit(main())
