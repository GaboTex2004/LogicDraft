import asyncio
from types import SimpleNamespace
import unittest
from unittest.mock import patch

import httpx
from groq import APIStatusError

from app.main import app


class FakeTranscriptions:
    def __init__(self, text: str) -> None:
        self.text = text
        self.calls: list[dict[str, object]] = []

    async def create(self, **kwargs):
        self.calls.append(kwargs)
        return SimpleNamespace(text=self.text)


class FailingTranscriptions:
    async def create(self, **_kwargs):
        request = httpx.Request("POST", "https://api.groq.com/openai/v1/audio/transcriptions")
        response = httpx.Response(500, request=request)
        raise APIStatusError("mock Groq failure", response=response, body=None)


class FakeGroq:
    def __init__(self, transcriptions: FakeTranscriptions) -> None:
        self.audio = SimpleNamespace(transcriptions=transcriptions)

    async def __aenter__(self):
        return self

    async def __aexit__(self, *_args):
        return None


async def post_audio(content: bytes, filename: str = "voice-0.webm"):
    transport = httpx.ASGITransport(app=app)
    async with httpx.AsyncClient(transport=transport, base_url="http://test") as client:
        return await client.post(
            "/api/audio/transcribe",
            files={"audio": (filename, content, "audio/webm;codecs=opus")},
        )


class AudioTranscriptionTest(unittest.TestCase):
    def test_audio_is_sent_to_configured_groq_whisper_without_exposing_the_key(self):
        transcriptions = FakeTranscriptions("Agrega nombre a Cliente")
        fake_groq = FakeGroq(transcriptions)
        settings = SimpleNamespace(groq_api_key="secret-test-key", groq_whisper_model="whisper-large-v3-turbo")
        with patch("app.api.routes.audio.get_settings", return_value=settings), patch(
            "app.api.routes.audio.AsyncGroq", return_value=fake_groq
        ):
            response = asyncio.run(post_audio(b"valid mocked webm"))

        self.assertEqual(response.status_code, 200)
        self.assertEqual(response.json(), {"text": "Agrega nombre a Cliente"})
        self.assertEqual(len(transcriptions.calls), 1)
        call = transcriptions.calls[0]
        self.assertEqual(call["model"], "whisper-large-v3-turbo")
        self.assertEqual(call["language"], "es")
        self.assertNotIn("secret-test-key", response.text)

    def test_empty_audio_is_rejected_before_calling_groq(self):
        settings = SimpleNamespace(groq_api_key="secret-test-key", groq_whisper_model="whisper-large-v3-turbo")
        with patch("app.api.routes.audio.get_settings", return_value=settings):
            response = asyncio.run(post_audio(b""))
        self.assertEqual(response.status_code, 400)
        self.assertEqual(response.json()["detail"], "El archivo de audio está vacío.")

    def test_missing_server_key_is_a_controlled_configuration_error(self):
        settings = SimpleNamespace(groq_api_key="", groq_whisper_model="whisper-large-v3-turbo")
        with patch("app.api.routes.audio.get_settings", return_value=settings):
            response = asyncio.run(post_audio(b"valid mocked webm"))
        self.assertEqual(response.status_code, 503)
        self.assertNotIn("GROQ_API_KEY", response.text)

    def test_invalid_audio_extension_is_rejected(self):
        settings = SimpleNamespace(groq_api_key="secret-test-key", groq_whisper_model="whisper-large-v3-turbo")
        with patch("app.api.routes.audio.get_settings", return_value=settings):
            response = asyncio.run(post_audio(b"not audio", "voice.txt"))
        self.assertEqual(response.status_code, 415)

    def test_empty_groq_transcription_is_rejected(self):
        transcriptions = FakeTranscriptions("   ")
        settings = SimpleNamespace(groq_api_key="secret-test-key", groq_whisper_model="whisper-large-v3-turbo")
        with patch("app.api.routes.audio.get_settings", return_value=settings), patch(
            "app.api.routes.audio.AsyncGroq", return_value=FakeGroq(transcriptions)
        ):
            response = asyncio.run(post_audio(b"valid mocked webm"))
        self.assertEqual(response.status_code, 422)
        self.assertEqual(response.json()["detail"], "No se detectó texto en el audio.")

    def test_groq_error_is_mapped_without_exposing_provider_body_or_key(self):
        settings = SimpleNamespace(groq_api_key="secret-test-key", groq_whisper_model="whisper-large-v3-turbo")
        with patch("app.api.routes.audio.get_settings", return_value=settings), patch(
            "app.api.routes.audio.AsyncGroq", return_value=FakeGroq(FailingTranscriptions())
        ):
            response = asyncio.run(post_audio(b"valid mocked webm"))
        self.assertEqual(response.status_code, 502)
        self.assertEqual(response.json()["detail"], "Groq rechazó la solicitud de transcripción.")
        self.assertNotIn("secret-test-key", response.text)
        self.assertNotIn("mock Groq failure", response.text)


if __name__ == "__main__":
    unittest.main()
