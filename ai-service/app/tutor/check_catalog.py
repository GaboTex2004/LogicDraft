"""Verify static assets and create an offline listening page. No API requests."""
import html
import json
from pathlib import Path
import re
import wave


def main():
    directory = Path(__file__).resolve().parents[3] / "Frontend" / "public" / "tutor-audio"
    manifest = json.loads((directory / "manifest.json").read_text(encoding="utf-8"))
    unique = {entry["file"]: entry["spoken"] for entry in manifest["entries"].values()}
    available, cards = [], []
    for filename, spoken in unique.items():
        if not re.fullmatch(r"[0-9a-f]{20}\.wav", filename):
            raise ValueError("Invalid filename")
        path = directory / filename
        if path.exists():
            with wave.open(str(path), "rb") as audio:
                frames = audio.getnframes()
                if not frames or audio.getcomptype() != "NONE" or len(audio.readframes(frames)) != frames * audio.getnchannels() * audio.getsampwidth():
                    raise ValueError("Incomplete or invalid WAV")
            available.append(filename)
        player = f'<audio controls preload="none" src="{filename}"></audio>' if filename in available else '<p>Audio pendiente</p>'
        cards.append(f'<section><p>{html.escape(spoken)}</p>{player}</section>')
    if set(available) != set(manifest.get("files", [])):
        raise ValueError("Manifest availability differs from the files on disk")
    page = '<!doctype html><html lang="es"><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>Revisión Puck — LogicDraft</title><style>body{background:#18181b;color:#e5e1e4;font:16px/1.6 system-ui;max-width:800px;margin:30px auto;padding:16px}section{border:1px solid #454650;border-radius:10px;padding:16px;margin:12px 0}audio{width:100%}</style><h1>Revisión de voz Puck</h1><p>Audio generado por IA. Compara cada grabación con el texto: pronunciación, exactitud y ritmo.</p>' + ''.join(cards) + '</html>'
    (directory / "review.html").write_text(page, encoding="utf-8")
    print(f"{len(available)}/{len(unique)} audios WAV íntegros; {len(manifest['entries'])} pasos. Revisión: Frontend/public/tutor-audio/review.html")
    return 0 if len(available) == len(unique) else 1


if __name__ == "__main__":
    raise SystemExit(main())
