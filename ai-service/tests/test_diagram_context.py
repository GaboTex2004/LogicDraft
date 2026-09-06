import json
import unittest
from unittest.mock import AsyncMock, patch
import httpx
from app.main import app
from app.schemas.diagram import InterpretRequest


class ContextTests(unittest.IsolatedAsyncioTestCase):
    async def test_context_reaches_model_and_old_request_still_works(self):
        diagram = {"entities": [{"name": "Cliente", "attributes": [
            {"name": "id", "dataType": "Long", "primaryKey": True, "nullable": False}
        ]}], "relationships": [{"sourceEntity": "Cliente", "targetEntity": "Cliente", "relationshipType": None}]}
        answer = {"operations": [{"type": "ADD_ATTRIBUTE", "entityName": "Cliente",
            "attribute": {"name": "telefono", "dataType": "String"}}]}
        with patch("app.services.ai_service.AIService.generate", new=AsyncMock(return_value=json.dumps(answer))) as generate:
            async with httpx.AsyncClient(transport=httpx.ASGITransport(app=app), base_url="http://test") as c:
                response = await c.post("/api/ai/diagram/interpret", json={"prompt": "Agrega telefono", "diagram": diagram})
                self.assertEqual(response.status_code, 200)
                instruction = generate.call_args.args[0]
                self.assertIn('Diagrama actual (JSON):', instruction)
                self.assertIn('"name":"Cliente"', instruction)
                self.assertIn('No recrees entidades existentes', instruction)
                response = await c.post("/api/ai/diagram/interpret", json={"prompt": "Agrega telefono"})
                self.assertEqual(response.status_code, 200)
                self.assertNotIn('Diagrama actual (JSON):', generate.call_args.args[0])

    def test_request_accepts_optional_context(self):
        self.assertIsNone(InterpretRequest(prompt="hola").diagram)

    async def test_invalid_context_rejected_before_generation(self):
        with patch("app.services.ai_service.AIService.generate", new=AsyncMock()) as generate:
            async with httpx.AsyncClient(transport=httpx.ASGITransport(app=app), base_url="http://test") as c:
                r = await c.post("/api/ai/diagram/interpret", json={"prompt": "hola", "diagram": {"nodes": []}})
                self.assertEqual(r.status_code, 422)
            generate.assert_not_called()
