import json
import unittest
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import AsyncMock, patch

import httpx
import pytest

from app.main import app
from app.schemas.agent import AgentAskRequest
from app.schemas.diagram import DiagramContext, InterpretResponse
from app.services.agent_service import AgentService
from app.skills.diagram.completeness import validate_completeness
from app.skills.diagram.semantic_safety import SemanticSafetyError, validate_semantic_safety


FIXTURE = json.loads((Path(__file__).resolve().parents[3] /
    "backend/src/test/resources/ai/semantic-safety-cases.json").read_text(encoding="utf-8"))


@pytest.mark.parametrize("case", FIXTURE["cases"], ids=lambda case: case["name"])
def test_java_python_shared_semantic_contract(case):
    context = DiagramContext.model_validate(FIXTURE["context"])
    response = InterpretResponse.model_validate({"operations": case["operations"]})
    before = context.model_dump()
    if case["allowed"]:
        validate_semantic_safety(case["prompt"], context, response)
    else:
        with pytest.raises(SemanticSafetyError):
            validate_semantic_safety(case["prompt"], context, response)
    assert context.model_dump() == before


def request(message):
    context = FIXTURE["context"]
    return AgentAskRequest.model_validate({
        "message": message,
        "context": {"projectId": 1, "projectName": "Test", "diagramId": 2,
                    "entities": [dict(e, id=str(i)) for i, e in enumerate(context["entities"])],
                    "relationships": [], "associations": context["associations"], "recentEvents": []},
    })


def test_completeness_cannot_keep_or_sanitize_an_unsafe_partial_plan():
    case = next(c for c in FIXTURE["cases"] if c["name"] == "compound-unsafe")
    with pytest.raises(SemanticSafetyError):
        validate_completeness(case["prompt"], DiagramContext.model_validate(FIXTURE["context"]),
                              InterpretResponse.model_validate({"operations": case["operations"]}))


class AgentSemanticSafetyTests(unittest.IsolatedAsyncioTestCase):
    async def test_wrong_delete_is_not_repaired_and_endpoint_returns_clarification_422(self):
        body = {"answer": "Eliminar Producto", "operations": [{"type": "DELETE_ENTITY", "entityName": "Producto"}]}
        generate = AsyncMock(return_value=json.dumps(body))
        with patch("app.services.ai_service.AIService.generate", new=generate):
            async with httpx.AsyncClient(transport=httpx.ASGITransport(app=app), base_url="http://test") as client:
                response = await client.post("/api/agent/ask", json=request(
                    "Elimina el atributo precio de venta de Producto.").model_dump(mode="json"))
        assert response.status_code == 422
        assert "Aclara" in response.json()["detail"]
        generate.assert_awaited_once()

    async def test_correct_compound_name_attribute_delete_survives_service(self):
        body = {"answer": "Propuesta", "operations": [{"type": "DELETE_ATTRIBUTE", "entityName": "Producto", "attributeName": "precio de venta"}]}
        generate = AsyncMock(return_value=json.dumps(body))
        result = await AgentService(SimpleNamespace(generate=generate)).ask(request(
            "elimina de Producto el atributo precio de venta"))
        assert [op.type for op in result.operations] == ["DELETE_ATTRIBUTE"]
        generate.assert_awaited_once()
