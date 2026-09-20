import unittest
from types import SimpleNamespace
from unittest.mock import AsyncMock

from app.schemas.agent import AgentAskRequest
from app.services.agent_service import AgentService


def request(message: str, selected: str | None = None) -> AgentAskRequest:
    return AgentAskRequest.model_validate({
        "message": message,
        "context": {
            "projectId": 10, "projectName": "Peluqueria", "diagramId": 30,
            "selectedNodeId": selected, "selectedEdgeId": None,
            "entities": [
                {"id": "personal", "name": "Personal", "attributes": [
                    {"name": "ID", "dataType": "Integer", "primaryKey": True, "nullable": False},
                    {"name": "Nombre", "dataType": "String", "primaryKey": False, "nullable": False},
                ]},
                {"id": "corte", "name": "Corte", "attributes": [
                    {"name": "ID", "dataType": "Integer", "primaryKey": True, "nullable": False},
                    {"name": "Precio", "dataType": "Double", "primaryKey": False, "nullable": False},
                ]},
            ],
            "relationships": [{
                "id": "r1", "sourceNodeId": "personal", "targetNodeId": "corte",
                "sourceEntity": "Personal", "targetEntity": "Corte",
                "sourceCardinality": "ONE_ONE", "targetCardinality": "ZERO_MANY",
            }],
            "recentEvents": [],
        },
    })


class FactualAgentTests(unittest.IsolatedAsyncioTestCase):
    def setUp(self):
        self.provider = AsyncMock(return_value="Respuesta basada en el contexto recibido.")
        self.service = AgentService(SimpleNamespace(generate=self.provider))

    async def test_entity_count_uses_llm(self):
        self.provider.return_value = "El diagrama contiene 2 entidades."
        result = await self.service.ask(request("Cuantas entidades tengo?"))
        self.assertIn("2 entidades", result.answer)
        self.provider.assert_awaited_once()

    async def test_entity_names_are_answered_by_llm_with_real_context(self):
        self.provider.return_value = "Las entidades actuales son Personal y Corte."
        result = await self.service.ask(request("Que entidades tengo?"))
        self.assertIn("Personal", result.answer)
        self.assertIn("Corte", result.answer)
        prompt = self.provider.call_args.args[0]
        self.assertIn('"name":"Personal"', prompt)
        self.assertIn('"name":"Precio"', prompt)

    async def test_attributes_primary_key_relations_and_selection_are_deterministic(self):
        questions = [
            ("Que atributos tiene Corte?", ("ID", "Precio")),
            ("Cual es la primary key de Corte?", ("ID",)),
            ("Con que esta relacionada Corte?", ("Personal", "0..N", "1..1")),
            ("Cual es la entidad seleccionada?", ("Corte",)),
        ]
        for question, expected in questions:
            with self.subTest(question=question):
                self.provider.return_value = " ".join(expected)
                result = await self.service.ask(request(question, "corte"))
                for value in expected:
                    self.assertIn(value, result.answer)
        self.assertEqual(self.provider.await_count, len(questions))

    async def test_conceptual_question_uses_llm(self):
        self.provider.return_value = "Podrías considerar Cliente para asociar preferencias, si el negocio lo requiere."
        result = await self.service.ask(request("Que entidad podria faltarme para modelar una peluqueria?"))
        self.assertIn("Cliente", result.answer)
        self.provider.assert_awaited_once()
