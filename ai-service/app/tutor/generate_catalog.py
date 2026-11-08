"""Offline, resumable generation of the reviewed static tutor manifest."""
import json
from pathlib import Path
import re
import wave
import time

from app.tutor.voice_samples import extract_audio


def main():
    from app.core.config import get_settings
    from google import genai
    from google.genai import types

    directory = Path(__file__).resolve().parents[3] / "Frontend" / "public" / "tutor-audio"
    manifest = json.loads((directory / "manifest.json").read_text(encoding="utf-8"))
    if manifest["voice"] != "Puck" or manifest["model"] != "gemini-2.5-flash-preview-tts":
        raise ValueError("Unexpected voice/model")
    entries = {entry["file"]: entry["spoken"] for entry in manifest["entries"].values()}
    def progress():
        manifest["files"] = [name for name in entries if (directory / name).exists() and (directory / name).stat().st_size > 44]
        (directory / "manifest.json").write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    if len(entries) > 40 or any(not re.fullmatch(r"[0-9a-f]{20}\.wav", name) for name in entries):
        raise ValueError("Invalid manifest filenames/size")
    settings = get_settings()
    if not settings.gemini_api_key:
        print("Falta GEMINI_API_KEY. No se hicieron solicitudes.")
        return 1
    with genai.Client(api_key=settings.gemini_api_key, http_options=types.HttpOptions(timeout=90_000)) as client:
        for number, (filename, text) in enumerate(entries.items(), 1):
            target = directory / filename
            if target.exists():
                with wave.open(str(target), "rb") as existing:
                    if existing.getnframes() == 0:
                        raise ValueError("Existing empty audio; review before regeneration")
                print(f"{number}/{len(entries)} ya generado", flush=True)
                continue
            try:
                response = client.models.generate_content(model=manifest["model"], contents=(
                    "Lee únicamente el siguiente texto en español latinoamericano, con tono cercano, claro y tranquilo de instructor. "
                    "Ritmo moderado, pausas naturales, sin música ni explicaciones adicionales.\n\n" + text),
                    config=types.GenerateContentConfig(response_modalities=["AUDIO"], speech_config=types.SpeechConfig(
                        voice_config=types.VoiceConfig(prebuilt_voice_config=types.PrebuiltVoiceConfig(voice_name="Puck")))))
                audio = extract_audio(response)
                with target.open("xb") as output:
                    output.write(audio)
                print(f"{number}/{len(entries)} generado", flush=True)
                progress()
                if number < len(entries):
                    time.sleep(30)  # Offline authoring only: avoid bursts against TTS quotas.
            except Exception as error:
                code = getattr(error, "code", None)
                print(f"Generación detenida; código: {code if isinstance(code, int) else 'no disponible'}. Los audios anteriores se conservan.", flush=True)
                # Only public quota identifiers/delays, never provider messages or project/key data.
                payload = getattr(error, "response_json", None)
                if isinstance(payload, dict):
                    body = payload.get("error", payload)
                    details = body.get("details", []) if isinstance(body, dict) else []
                    for detail in details if isinstance(details, list) else []:
                        if not isinstance(detail, dict):
                            continue
                        delay = detail.get("retryDelay", "")
                        if isinstance(delay, str) and re.fullmatch(r"[0-9.]+s", delay):
                            print(f"Espera indicada por Gemini: {delay}", flush=True)
                        for violation in detail.get("violations", []):
                            quota = violation.get("quotaId", "") if isinstance(violation, dict) else ""
                            if isinstance(quota, str) and re.fullmatch(r"[A-Za-z0-9_-]+", quota):
                                print(f"Cuota indicada por Gemini: {quota}", flush=True)
                progress()
                return 1
    progress()
    print("Catálogo completo. Pendiente revisión auditiva del usuario.", flush=True)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
