import unittest
from unittest.mock import AsyncMock, patch

from app.services.ai_service import AIService
from app.services.providers.ollama_provider import OllamaProvider


class FakeResponse:
    def raise_for_status(self):
        return None

    def json(self):
        return {"response": '{"operations":[]}'}


class FakeClient:
    def __init__(self, **kwargs):
        self.kwargs = kwargs
        self.post = AsyncMock(return_value=FakeResponse())

    async def __aenter__(self):
        return self

    async def __aexit__(self, *_):
        return None


class StructuredGenerationTests(unittest.IsolatedAsyncioTestCase):
    async def test_ollama_receives_schema_and_zero_temperature_only_when_requested(self):
        instances = []

        def create(**kwargs):
            client = FakeClient(**kwargs)
            instances.append(client)
            return client

        provider = OllamaProvider("http://localhost:11434", "model", 60)
        schema = {"type": "object", "properties": {"operations": {"type": "array"}}}
        with patch("app.services.providers.ollama_provider.httpx.AsyncClient", side_effect=create):
            self.assertEqual(await provider.generate("structured", schema), '{"operations":[]}')
            self.assertEqual(await provider.generate("generic"), '{"operations":[]}')
        structured = instances[0].post.await_args.kwargs["json"]
        generic = instances[1].post.await_args.kwargs["json"]
        self.assertEqual(structured["format"], schema)
        self.assertEqual(structured["options"], {"temperature": 0})
        self.assertNotIn("format", generic)
        self.assertNotIn("options", generic)

    async def test_ai_service_forwards_optional_schema(self):
        provider = AsyncMock()
        provider.generate.return_value = "{}"
        service = AIService(provider)
        schema = {"type": "object"}
        await service.generate("prompt", schema)
        provider.generate.assert_awaited_once_with("prompt", schema)
