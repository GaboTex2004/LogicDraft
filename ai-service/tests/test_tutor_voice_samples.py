import io
from types import SimpleNamespace
import wave

import unittest

from app.tutor.voice_samples import audio_as_wav, extract_audio, preview_html


class TutorVoiceSampleTests(unittest.TestCase):
    def test_pcm_becomes_playable_wav(self):
        result = audio_as_wav(b"\x00\x00" * 240, "audio/L16;codec=pcm;rate=24000")
        with wave.open(io.BytesIO(result), "rb") as audio:
            self.assertEqual((audio.getnchannels(), audio.getsampwidth(), audio.getframerate(), audio.getnframes()), (1, 2, 24000, 240))
        self.assertEqual(audio_as_wav(result, "audio/wav"), result)

    def test_unexpected_audio_is_not_mislabeled(self):
        for data, mime in [(b"", "audio/pcm;rate=24000"), (b"a", "audio/pcm;rate=24000"), (b"aa", "audio/mp3"), (b"aa", "audio/l16;rate=48000")]:
            with self.subTest(mime=mime), self.assertRaises(ValueError):
                audio_as_wav(data, mime)

    def test_missing_or_multiple_audio_parts_rejected(self):
        with self.assertRaises(ValueError):
            extract_audio(SimpleNamespace(candidates=[]))
        part = SimpleNamespace(inline_data=SimpleNamespace(data=b"aa", mime_type="audio/l16;rate=24000"))
        response = SimpleNamespace(candidates=[SimpleNamespace(content=SimpleNamespace(parts=[part, part]))])
        with self.assertRaises(ValueError):
            extract_audio(response)

    def test_preview_has_manual_players_and_escaped_model(self):
        page = preview_html(["Kore", "Puck"], "<model>")
        self.assertEqual(page.count("<audio controls"), 2)
        self.assertNotIn("autoplay", page)
        self.assertIn("&lt;model&gt;", page)
