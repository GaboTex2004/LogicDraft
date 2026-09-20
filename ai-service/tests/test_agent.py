import copy
import json
import unittest
from types import SimpleNamespace
from unittest.mock import AsyncMock, patch

import httpx

from app.main import app
from app.schemas.agent import AgentAskRequest
from app.services.agent_service import AgentService
from app.services.providers.base import ProviderConnectionError


def payload():
    return {
        "message": "¿Cómo mejorarías esta relación?",
        "context": {
            "projectId": 10,
            "projectName": "Tienda",
            "diagramId": 30,
            "selectedNodeId": "producto",
            "selectedEdgeId": "rel-1",
            "entities": [
                {"id": "categoria", "name": "Categoria", "attributes": [
                    {"name": "ID", "dataType": "Integer", "primaryKey": True, "nullable": False}
                ]},
                {"id": "producto", "name": "Producto", "attributes": [
                    {"name": "Precio", "dataType": "Double", "primaryKey": False, "nullable": True}
                ]},
            ],
            "relationships": [{
                "id": "rel-1", "sourceNodeId": "categoria", "targetNodeId": "producto",
                "sourceEntity": "Categoria", "targetEntity": "Producto",
                "sourceCardinality": "ONE_ONE", "targetCardinality": "ZERO_MANY",
            }],
            "recentEvents": [{
                "type": "NODE_SELECTED", "nodeId": "producto", "edgeId": None,
                "timestamp": "2026-09-05T12:00:00Z",
            }],
        },
    }


class AgentTests(unittest.IsolatedAsyncioTestCase):
    async def test_endpoint_receives_context_and_returns_text(self):
        generated = 'Producto pertenece a Categoria.'
        with patch("app.services.ai_service.AIService.generate", new=AsyncMock(return_value=generated)) as generate:
            async with httpx.AsyncClient(transport=httpx.ASGITransport(app=app), base_url="http://test") as client:
                response = await client.post("/api/agent/ask", json=payload())
        self.assertEqual(response.status_code, 200)
        self.assertEqual(response.json(), {"answer": "Producto pertenece a Categoria.", "operations": []})
        self.assertEqual(len(generate.call_args.args), 1)

    async def test_prompt_contains_selection_relationships_cardinality_and_events(self):
        model = AsyncMock(return_value='Respuesta contextual')
        request = AgentAskRequest.model_validate(payload())
        before = copy.deepcopy(request.model_dump(mode="json"))
        result = await AgentService(SimpleNamespace(generate=model)).ask(request)
        prompt = model.call_args.args[0]
        self.assertEqual(result.answer, "Respuesta contextual")
        self.assertIn('"selectedEntity":{"id":"producto"', prompt)
        self.assertIn('"selectedRelationship":{"id":"rel-1"', prompt)
        self.assertIn('"targetCardinality":"ZERO_MANY"', prompt)
        self.assertIn('"type":"NODE_SELECTED"', prompt)
        self.assertNotIn("ADD_RELATIONSHIP", prompt)
        self.assertIn("únicamente texto conversacional", prompt)
        self.assertEqual(request.model_dump(mode="json"), before)

    async def test_prompt_contains_bounded_conversation(self):
        body = payload()
        body["message"] = "Agrega una entidad Profesor"
        body["conversation"] = [{"role": "user", "text": "¿Qué contiene mi diagrama?"}]
        model = AsyncMock(return_value='{"answer":"Propuesta preparada","operations":['
                                      '{"type":"ADD_ENTITY","entity":{"name":"Profesor","attributes":[]}}]}')
        await AgentService(SimpleNamespace(generate=model)).ask(AgentAskRequest.model_validate(body))
        self.assertIn('"conversation":[{"role":"user"', model.call_args.args[0])

    async def test_provider_error_is_controlled(self):
        with patch("app.services.ai_service.AIService.generate", new=AsyncMock(side_effect=ProviderConnectionError("private"))):
            async with httpx.AsyncClient(transport=httpx.ASGITransport(app=app), base_url="http://test") as client:
                response = await client.post("/api/agent/ask", json=payload())
        self.assertEqual(response.status_code, 503)
        self.assertNotIn("private", response.text)

    async def test_project_without_diagram_works(self):
        body = payload()
        body["context"].update({"diagramId": None, "selectedNodeId": None, "selectedEdgeId": None,
                                "entities": [], "relationships": []})
        generated = 'Aun no hay diagrama.'
        with patch("app.services.ai_service.AIService.generate", new=AsyncMock(return_value=generated)):
            async with httpx.AsyncClient(transport=httpx.ASGITransport(app=app), base_url="http://test") as client:
                response = await client.post("/api/agent/ask", json=body)
        self.assertEqual(response.status_code, 200)

    async def test_event_limit_and_unknown_event_are_rejected_before_model(self):
        for mutation in ("limit", "unknown"):
            body = payload()
            if mutation == "limit":
                body["context"]["recentEvents"] = body["context"]["recentEvents"] * 26
            else:
                body["context"]["recentEvents"][0]["type"] = "MOUSE_MOVED"
            with patch("app.services.ai_service.AIService.generate", new=AsyncMock()) as generate:
                async with httpx.AsyncClient(transport=httpx.ASGITransport(app=app), base_url="http://test") as client:
                    response = await client.post("/api/agent/ask", json=body)
            self.assertEqual(response.status_code, 422)
            generate.assert_not_called()

    async def test_agent_returns_an_actionable_many_to_many_operation(self):
        body = payload()
        body["context"]["entities"] = [
            {"id": "student", "name": "Alumno", "attributes": []},
            {"id": "subject", "name": "Materia", "attributes": []},
        ]
        body["context"]["relationships"] = []
        body["message"] = "Relaciona Alumno y Materia de muchos a muchos"
        generated = json.dumps({"answer": "Relacion N:M propuesta.", "operations": [{
            "type": "ADD_RELATIONSHIP", "relationship": {
                "sourceEntity": "Alumno", "targetEntity": "Materia",
                "sourceCardinality": "ZERO_MANY", "targetCardinality": "ZERO_MANY",
                "name": "materias", "joinTableName": "alumno_materia",
            },
        }]})
        with patch("app.services.ai_service.AIService.generate", new=AsyncMock(return_value=generated)):
            async with httpx.AsyncClient(transport=httpx.ASGITransport(app=app), base_url="http://test") as client:
                response = await client.post("/api/agent/ask", json=body)
        self.assertEqual(response.status_code, 200)
        relation = response.json()["operations"][0]["relationship"]
        self.assertEqual(relation["sourceCardinality"], "ZERO_MANY")
        self.assertEqual(relation["targetCardinality"], "ZERO_MANY")
        self.assertEqual(relation["joinTableName"], "alumno_materia")


if __name__ == "__main__":
    unittest.main()
