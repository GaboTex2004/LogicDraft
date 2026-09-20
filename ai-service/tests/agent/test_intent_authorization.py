import json
import unittest
from types import SimpleNamespace
from unittest.mock import AsyncMock

from app.schemas.agent import AgentAskRequest
from app.services.agent_service import AgentService
from app.services.providers.base import ProviderConnectionError, ProviderResponseError
from app.skills.agent.intent import AgentIntent, classify_agent_intent


def request(message: str, conversation: list[dict[str, str]] | None = None) -> AgentAskRequest:
    return AgentAskRequest.model_validate({
        "message": message,
        "conversation": conversation or [],
        "context": {
            "projectId": 7, "projectName": "Universidad",
            "projectDescription": "Gestión académica de alumnos y materias",
            "diagramId": 9, "selectedNodeId": None, "selectedEdgeId": None,
            "entities": [
                {"id": "a", "name": "Alumno", "attributes": [
                    {"name": "id", "dataType": "Long", "primaryKey": True, "nullable": False},
                ]},
                {"id": "m", "name": "Materia", "attributes": [
                    {"name": "codigo", "dataType": "String", "primaryKey": False, "nullable": False},
                ]},
                {"id": "i", "name": "Inscripcion", "attributes": [
                    {"name": "fecha", "dataType": "Date", "primaryKey": False, "nullable": False},
                ]},
            ],
            "relationships": [{
                "id": "r1", "sourceNodeId": "a", "targetNodeId": "i",
                "sourceEntity": "Alumno", "targetEntity": "Inscripcion",
                "sourceCardinality": "ONE_ONE", "targetCardinality": "ZERO_MANY",
            }],
            "associations": [{
                "entityName": "Inscripcion", "tableName": "alumno_materia",
                "endpointEntityNames": ["Alumno", "Materia"],
                "structuralRelationshipIds": ["r1", "r2"],
            }],
            "recentEvents": [],
        },
    })


class AgentIntentAuthorizationTests(unittest.IsolatedAsyncioTestCase):
    async def test_informational_question_calls_model_with_complete_context_and_no_schema(self):
        provider = AsyncMock(return_value=(
            "Podrías evaluar un estado en Inscripcion para distinguir solicitudes pendientes y confirmadas."
        ))
        result = await AgentService(SimpleNamespace(generate=provider)).ask(
            request("¿Qué podría agregar a mi diagrama?"),
        )
        self.assertEqual(result.operations, [])
        self.assertIn("Inscripcion", result.answer)
        provider.assert_awaited_once()
        self.assertEqual(len(provider.call_args.args), 1)
        prompt = provider.call_args.args[0]
        for expected in ('"description":"Gestión académica', '"name":"codigo"',
                         '"sourceCardinality":"ONE_ONE"', '"entityName":"Inscripcion"'):
            self.assertIn(expected, prompt)
        self.assertNotIn("ADD_ENTITY", prompt)

    async def test_follow_up_history_reaches_model_and_changes_answer(self):
        provider = AsyncMock(return_value=(
            "Lo recomendaría porque permite conocer el ciclo de vida de cada inscripción."
        ))
        result = await AgentService(SimpleNamespace(generate=provider)).ask(request(
            "¿Por qué recomendarías eso?",
            [{"role": "user", "text": "¿Qué podría agregar?"},
             {"role": "agent", "text": "Podrías evaluar un atributo estado en Inscripcion."}],
        ))
        self.assertEqual(result.operations, [])
        self.assertIn("ciclo de vida", result.answer)
        prompt = provider.call_args.args[0]
        self.assertIn("Podrías evaluar un atributo estado", prompt)
        self.assertIn("¿Por qué recomendarías eso?", prompt)

    async def test_internal_operation_names_are_rejected_not_replaced_by_template(self):
        provider = AsyncMock(return_value="Usaría ADD_ENTITY para resolverlo.")
        with self.assertRaises(ProviderResponseError):
            await AgentService(SimpleNamespace(generate=provider)).ask(request("¿Qué opinas?"))

    async def test_ambiguous_request_calls_conversation_model_but_never_returns_operations(self):
        provider = AsyncMock(return_value="¿Quieres analizar el papel de Profesor o agregarlo al modelo?")
        result = await AgentService(SimpleNamespace(generate=provider)).ask(request("Profesor"))
        self.assertEqual(result.operations, [])
        self.assertIn("analizar", result.answer)

    async def test_provider_disconnect_is_propagated_without_fake_analysis(self):
        provider = AsyncMock(side_effect=ProviderConnectionError("offline"))
        with self.assertRaises(ProviderConnectionError):
            await AgentService(SimpleNamespace(generate=provider)).ask(request("¿Qué podría mejorar?"))

    async def test_explicit_edit_still_uses_structured_schema(self):
        generated = json.dumps({
            "answer": "Propuesta.",
            "operations": [{"type": "ADD_ENTITY", "entity": {"name": "Profesor", "attributes": []}}],
        })
        provider = AsyncMock(return_value=generated)
        result = await AgentService(SimpleNamespace(generate=provider)).ask(request("Agrega una entidad Profesor"))
        self.assertEqual(len(result.operations), 1)
        self.assertEqual(len(provider.call_args.args), 2)

    def test_classifier_requires_explicit_imperative(self):
        cases = {
            "¿Qué podría agregar?": AgentIntent.INFORMATIONAL,
            "Explícame el modelo": AgentIntent.INFORMATIONAL,
            "Podríamos agregar horarios": AgentIntent.AMBIGUOUS,
            "¿Qué tal si hacemos Docente?": AgentIntent.INFORMATIONAL,
            "Profesor": AgentIntent.AMBIGUOUS,
            "Agrega una entidad Profesor": AgentIntent.MODIFICATION,
            "Convierte la relación N:M en Inscripcion": AgentIntent.MODIFICATION,
        }
        for message, expected in cases.items():
            with self.subTest(message=message):
                self.assertEqual(classify_agent_intent(message), expected)


if __name__ == "__main__":
    unittest.main()
