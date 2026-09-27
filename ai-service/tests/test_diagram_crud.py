import json
import unittest
from types import SimpleNamespace
from unittest.mock import patch

from pydantic import ValidationError

from app.schemas.diagram import DiagramContext, DiagramSelection, EntityDefinition, InterpretResponse
from app.services.diagram_ai_service import DiagramAIService
from app.services.providers.gemini_provider import GeminiProvider, adapt_schema_for_gemini


def attribute(name="cantidad", data_type="Integer"):
    return {"name": name, "dataType": data_type, "primaryKey": False, "nullable": True}


class DiagramCrudSchemaTests(unittest.TestCase):
    def test_gemini_receives_an_adapted_json_schema_without_mutating_the_contract(self):
        schema = {"properties": {"type": {"const": "ADD_ENTITY"}, "name": {"pattern": "x+"}}}
        adapted = adapt_schema_for_gemini(schema)
        self.assertEqual(adapted["properties"]["type"]["enum"], ["ADD_ENTITY"])
        self.assertNotIn("pattern", adapted["properties"]["name"])
        self.assertIn("const", schema["properties"]["type"])

    def test_full_diagram_schema_is_flattened_for_gemini_structured_output(self):
        adapted = adapt_schema_for_gemini(InterpretResponse.model_json_schema())
        encoded = json.dumps(adapted)
        self.assertNotIn('"$defs"', encoded)
        self.assertNotIn('"$ref"', encoded)
        self.assertNotIn('"oneOf"', encoded)
        self.assertNotIn('"anyOf"', encoded)
        self.assertNotIn('"type": "null"', encoded)
        operation = adapted["properties"]["operations"]["items"]
        self.assertEqual(operation["required"], ["type"])
        self.assertIn("ADD_ENTITY", operation["properties"]["type"]["enum"])
        self.assertIn("RENAME_ENTITY", operation["properties"]["type"]["enum"])

    def test_accepts_complete_closed_crud_contract(self):
        operations = [
            {"type": "DELETE_ENTITY", "entityName": "Obsoleto"},
            {"type": "RENAME_ENTITY", "entityName": "Cliente", "newName": "Persona"},
            {"type": "DELETE_ATTRIBUTE", "entityName": "Persona", "attributeName": "telefono"},
            {"type": "RENAME_ATTRIBUTE", "entityName": "Persona", "attributeName": "nombre", "newName": "razonSocial"},
            {"type": "CHANGE_ATTRIBUTE_TYPE", "entityName": "Producto", "attributeName": "precio", "dataType": "decimal"},
            {"type": "SET_ATTRIBUTE_PRIMARY_KEY", "entityName": "Persona", "attributeName": "id", "value": True},
            {"type": "SET_ATTRIBUTE_NULLABLE", "entityName": "Persona", "attributeName": "correo", "value": True},
            {"type": "DELETE_RELATIONSHIP", "relationship": {"sourceEntity": "Persona", "targetEntity": "Venta"}},
            {"type": "UPDATE_RELATIONSHIP", "relationship": {"sourceEntity": "Persona", "targetEntity": "Venta",
             "sourceCardinality": "ONE_ONE", "targetCardinality": "ONE_ONE"}},
            {"type": "CREATE_ASSOCIATION", "association": {"sourceEntity": "Producto", "targetEntity": "Venta",
             "associationEntityName": "DetalleVenta", "attributes": [attribute()]}},
            {"type": "DELETE_ASSOCIATION", "associationEntityName": "DetalleAnterior"},
        ]
        result = InterpretResponse.model_validate({"operations": operations})
        self.assertEqual(len(result.operations), 11)
        self.assertEqual(result.operations[4].dataType, "Double")

    def test_rejects_unknown_incomplete_and_unsafe_values(self):
        invalid = [
            {"type": "UNKNOWN"},
            {"type": "DELETE_ATTRIBUTE", "entityName": "A"},
            {"type": "CHANGE_ATTRIBUTE_TYPE", "entityName": "A", "attributeName": "x", "dataType": "SQL"},
            {"type": "UPDATE_RELATIONSHIP", "relationship": {"sourceEntity": "A", "targetEntity": "B",
             "sourceCardinality": "OTHER", "targetCardinality": "ONE_ONE"}},
            {"type": "CREATE_ASSOCIATION", "association": {"sourceEntity": "A", "targetEntity": "B",
             "associationEntityName": "AB", "attributes": [attribute("id") | {"primaryKey": True}]}},
        ]
        for operation in invalid:
            with self.subTest(operation=operation), self.assertRaises(ValidationError):
                InterpretResponse.model_validate({"operations": [operation]})


class DiagramCrudPromptTests(unittest.IsolatedAsyncioTestCase):
    async def test_selection_is_semantic_context_and_provider_is_mocked(self):
        captured = {}

        async def provider(prompt, schema):
            captured["prompt"] = prompt
            captured["schema"] = schema
            return json.dumps({"operations": [{"type": "RENAME_ENTITY", "entityName": "Producto", "newName": "Item"}]})

        context = DiagramContext(entities=[EntityDefinition(name="Producto", attributes=[])], relationships=[])
        selection = DiagramSelection(kind="ENTITY", entityName="Producto")
        result = await DiagramAIService(type("FakeAi", (), {"generate": staticmethod(provider)})()).interpret(
            "renombrala como Item", context, selection)
        self.assertEqual(result.operations[0].type, "RENAME_ENTITY")
        self.assertIn('"entityName":"Producto"', captured["prompt"])
        self.assertIn("RENAME_ENTITY", json.dumps(captured["schema"]))

    async def test_minimum_written_crud_cases_produce_structured_plans(self):
        cases = [
            (
                "Crea una entidad Cliente",
                None,
                {"operations": [{"type": "ADD_ENTITY", "entity": {"name": "Cliente", "attributes": []}}]},
                "ADD_ENTITY",
            ),
            (
                "Agrega stock INTEGER a Producto",
                DiagramContext(entities=[EntityDefinition(name="Producto", attributes=[])], relationships=[]),
                {"operations": [{"type": "ADD_ATTRIBUTE", "entityName": "Producto", "attribute": attribute("stock")}]},
                "ADD_ATTRIBUTE",
            ),
            (
                "Cambia Cliente a Persona",
                DiagramContext(entities=[EntityDefinition(name="Cliente", attributes=[])], relationships=[]),
                {"operations": [{"type": "RENAME_ENTITY", "entityName": "Cliente", "newName": "Persona"}]},
                "RENAME_ENTITY",
            ),
        ]
        for prompt, context, provider_response, expected_type in cases:
            async def provider(_instruction, _schema, response=provider_response):
                return json.dumps(response)

            with self.subTest(prompt=prompt):
                result = await DiagramAIService(
                    type("FakeAi", (), {"generate": staticmethod(provider)})()
                ).interpret(prompt, context)
                self.assertEqual(result.operations[0].type, expected_type)

    async def test_multiple_written_actions_stay_in_one_ordered_plan(self):
        response = {"operations": [
            {"type": "ADD_ENTITY", "entity": {"name": "Cliente", "attributes": []}},
            {"type": "ADD_ENTITY", "entity": {"name": "Pedido", "attributes": []}},
            {"type": "ADD_RELATIONSHIP", "relationship": {
                "sourceEntity": "Cliente", "targetEntity": "Pedido",
                "sourceCardinality": "ONE_ONE", "targetCardinality": "ZERO_MANY",
            }},
        ]}

        async def provider(_instruction, _schema):
            return json.dumps(response)

        result = await DiagramAIService(
            type("FakeAi", (), {"generate": staticmethod(provider)})()
        ).interpret("Crea Cliente, Pedido y relaciónalos uno a muchos")
        self.assertEqual([operation.type for operation in result.operations], [
            "ADD_ENTITY", "ADD_ENTITY", "ADD_RELATIONSHIP",
        ])


class GeminiProviderRequestTests(unittest.IsolatedAsyncioTestCase):
    async def test_gemini_uses_json_schema_without_tools_or_afc(self):
        calls = []

        class FakeModels:
            async def generate_content(self, **kwargs):
                calls.append(kwargs)
                return SimpleNamespace(text='{"operations":[]}')

        class FakeClient:
            def __init__(self):
                self.aio = SimpleNamespace(models=FakeModels())

            def close(self):
                return None

        with patch("app.services.providers.gemini_provider.genai.Client", return_value=FakeClient()):
            result = await GeminiProvider("test-key", "gemini-test", 10).generate(
                "Crea una entidad Cliente",
                InterpretResponse.model_json_schema(),
            )

        self.assertEqual(result, '{"operations":[]}')
        request = calls[0]
        config = request["config"]
        self.assertEqual(request["model"], "gemini-test")
        self.assertEqual(request["contents"], "Crea una entidad Cliente")
        self.assertIsNone(config.tools)
        self.assertTrue(config.automatic_function_calling.disable)
        self.assertEqual(config.response_mime_type, "application/json")
        encoded = json.dumps(config.response_json_schema)
        self.assertNotIn('"oneOf"', encoded)
        self.assertNotIn('"$ref"', encoded)


if __name__ == "__main__":
    unittest.main()
