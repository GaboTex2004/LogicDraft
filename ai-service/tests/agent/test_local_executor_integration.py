"""Opt-in local inference; never downloads a model or loads an env file."""
import os

import pytest

from app.services.agent_service import AgentService
from app.services.ai_service import AIService
from app.services.providers.ollama_provider import OllamaProvider
from .test_execution_plan import COMPOUND, RELATION, request


@pytest.fixture
def anyio_backend():
    return "asyncio"


@pytest.mark.skipif(os.environ.get("LOGICDRAFT_TEST_LOCAL_EXECUTOR") != "1", reason="Opt-in local Ollama inference")
@pytest.mark.anyio
@pytest.mark.parametrize("message,expected", [(RELATION, ["ADD_RELATIONSHIP"]),
                                              (COMPOUND, ["DELETE_ATTRIBUTE", "ADD_ATTRIBUTE"])])
async def test_installed_llama_executor_with_exact_user_instructions(message, expected):
    service = AgentService(AIService(OllamaProvider("http://127.0.0.1:11434", "llama3.2:latest", 90)))
    result = await service.ask(request(message))
    assert result.outcome == "PROPOSAL", result.answer
    assert [op.type for op in result.operations] == expected
    if message == RELATION:
        relation = result.operations[0].relationship
        assert (relation.sourceEntity, relation.targetEntity) == ("Producto", "Categoria")
        assert (relation.sourceCardinality, relation.targetCardinality) == ("ZERO_MANY", "ONE_ONE")
    else:
        assert result.operations[0].attributeName == "Precio de venta"
        assert result.operations[1].entityName == "compra"
        assert result.operations[1].attribute.name == "CANTIDAD"
        assert result.operations[1].attribute.dataType == "Integer"
