import copy
import json
import unittest
from unittest.mock import AsyncMock, patch

import httpx
from app.main import app
from app.services.diagram_ai_service import parse_operations
from app.services.diagram_response_shape import canonicalize_diagram_response_shape
from app.services.providers.base import ProviderResponseError


ENTITY = {"type": "ADD_ENTITY", "entity": {"name": "Cliente", "attributes": [
    {"name": "ID", "dataType": "Integer", "primaryKey": True, "nullable": False},
    {"name": "Nombre", "dataType": "Varchar", "primaryKey": False, "nullable": True},
]}}
ATTRIBUTE = {"type": "ADD_ATTRIBUTE", "entityName": "Cliente",
             "attribute": {"name": "telefono", "dataType": "VARCHAR(255)"}}
RELATIONSHIP = {"type": "ADD_RELATIONSHIP", "relationship": {
    "sourceEntity": "Cliente", "targetEntity": "Pedido", "sourceCardinality": "ONE_ONE", "targetCardinality": "ZERO_MANY"}}
LOGGER = "app.services.diagram_ai_service"
PROMPT = 'crea una entidad "Cliente" con atributo "ID" tipo Integer y que sea primary key, otro atributo "Nombre" tipo Varchar'


class ShapeTests(unittest.TestCase):
    def test_correct_envelope_unchanged(self):
        document = {"operations": [ENTITY]}
        self.assertEqual(canonicalize_diagram_response_shape(document), document)

    def test_direct_entity(self):
        self.assertEqual(canonicalize_diagram_response_shape(ENTITY), {"operations": [ENTITY]})

    def test_direct_attribute(self):
        result = parse_operations(json.dumps(ATTRIBUTE))
        self.assertEqual(result.operations[0].attribute.dataType, "String")

    def test_direct_relationship(self):
        result = parse_operations(json.dumps(RELATIONSHIP))
        self.assertEqual(result.operations[0].relationship.targetCardinality, "ZERO_MANY")

    def test_direct_list(self):
        self.assertEqual(canonicalize_diagram_response_shape([ENTITY]), {"operations": [ENTITY]})
        self.assertEqual(parse_operations("[]").operations, [])

    def test_multiple_operations_keep_order(self):
        operations = [ENTITY, ATTRIBUTE, RELATIONSHIP]
        result = parse_operations(json.dumps(operations))
        self.assertEqual([op.type for op in result.operations], [op["type"] for op in operations])

    def test_fundamental_example_alias_names_and_pk_preserved(self):
        expected = copy.deepcopy(ENTITY)
        expected["entity"]["attributes"][1]["dataType"] = "String"
        self.assertEqual(parse_operations(json.dumps(ENTITY)).model_dump(), {"operations": [expected]})

    def test_unknown_json_and_unobserved_singular_envelope_rejected(self):
        for data in [None, True, 1, "text", {}, {"resultado": [ENTITY]}, {"operation": ENTITY},
                     {"entities": [ENTITY["entity"]], "relationships": []}, [ENTITY, 3]]:
            with self.subTest(data=data), self.assertLogs(LOGGER), self.assertRaises(ProviderResponseError):
                parse_operations(json.dumps(data))

    def test_unknown_type_rejected(self):
        for value in ["DELETE_ENTITY", None, [], {}]:
            with self.subTest(value=value), self.assertLogs(LOGGER), self.assertRaises(ProviderResponseError):
                parse_operations(json.dumps({**ENTITY, "type": value}))

    def test_entity_extra_rejected(self):
        data = copy.deepcopy(ENTITY)
        data["entity"]["unexpected"] = True
        with self.assertLogs(LOGGER) as logs, self.assertRaises(ProviderResponseError):
            parse_operations(json.dumps(data))
        self.assertIn("operations.0.ADD_ENTITY.entity.unexpected", " ".join(logs.output))

    def test_unknown_data_type_rejected(self):
        data = copy.deepcopy(ATTRIBUTE)
        data["attribute"]["dataType"] = "BananaType"
        with self.assertLogs(LOGGER), self.assertRaises(ProviderResponseError):
            parse_operations(json.dumps(data))

    def test_incomplete_or_invalid_contents_rejected(self):
        for data in [
            {"type": "ADD_ENTITY", "entity": {"name": "Cliente"}},
            {**ATTRIBUTE, "attribute": {"name": "x"}},
            {**ATTRIBUTE, "attribute": {"name": "x", "dataType": "INT", "primaryKey": "true"}},
            {**RELATIONSHIP, "relationship": {**RELATIONSHIP["relationship"], "relationshipType": "INVALID"}},
            {**ENTITY, "extra": 1}, {"operations": [ENTITY], "extra": 1},
            {"operations": [], **ENTITY}, {"operations": ENTITY},
        ]:
            with self.subTest(data=data), self.assertLogs(LOGGER), self.assertRaises(ProviderResponseError):
                parse_operations(json.dumps(data))

    def test_idempotent_and_no_mutation(self):
        for data in [ENTITY, [ENTITY, ATTRIBUTE], {"operations": [ENTITY]}, {"entities": []}]:
            original = copy.deepcopy(data)
            canonical = canonicalize_diagram_response_shape(data)
            self.assertEqual(canonicalize_diagram_response_shape(canonical), canonical)
            self.assertEqual(data, original)

    def test_complete_fence_is_removed_before_canonicalization(self):
        self.assertEqual(len(parse_operations('```json\n' + json.dumps(ENTITY) + '\n```').operations), 1)

    def test_dedup_runs_after_shape_and_alias_normalization(self):
        result = parse_operations(json.dumps([ATTRIBUTE, ATTRIBUTE]))
        self.assertEqual(len(result.operations), 1)

    def test_logs_structure_keys_locations_without_values(self):
        secret = "eyJhbGciOiJIUzI1NiJ9.secret.signature"
        data = {"entities": [], "relationships": [], "Authorization": secret, secret: "password-value"}
        with self.assertLogs(LOGGER) as logs, self.assertRaises(ProviderResponseError):
            parse_operations(json.dumps(data))
        output = " ".join(logs.output)
        self.assertIn("structure=dict", output)
        self.assertIn("topLevelKeys=['entities', 'relationships'", output)
        self.assertIn("error=missing location=operations", output)
        self.assertIn("error=extra_forbidden location=entities", output)
        self.assertNotIn(secret, output)
        self.assertNotIn("password-value", output)
        with self.assertLogs(LOGGER) as logs, self.assertRaises(ProviderResponseError):
            parse_operations('[42]')
        self.assertIn("structure=list", " ".join(logs.output))


class ShapeEndpointTests(unittest.IsolatedAsyncioTestCase):
    async def check_endpoint(self, contextual):
        body = {"prompt": PROMPT}
        if contextual:
            body["diagram"] = {"entities": [], "relationships": []}
        for raw in [ENTITY, [ENTITY], {"operations": [ENTITY]}]:
            with self.subTest(raw=raw), patch("app.services.ai_service.AIService.generate", new=AsyncMock(return_value=json.dumps(raw))):
                async with httpx.AsyncClient(transport=httpx.ASGITransport(app=app), base_url="http://test") as client:
                    response = await client.post("/api/ai/diagram/interpret", json=body)
            self.assertEqual(response.status_code, 200, response.text)
            self.assertEqual(set(response.json()), {"operations"})
            attributes = response.json()["operations"][0]["entity"]["attributes"]
            self.assertEqual(attributes[0], ENTITY["entity"]["attributes"][0])
            self.assertEqual(attributes[1]["dataType"], "String")

    async def test_non_contextual_pipeline(self):
        await self.check_endpoint(False)

    async def test_contextual_pipeline(self):
        await self.check_endpoint(True)

    async def test_invalid_model_diagram_still_returns_generic_502(self):
        with patch("app.services.ai_service.AIService.generate", new=AsyncMock(return_value='{"entities":[],"relationships":[]}')):
            with self.assertLogs(LOGGER):
                async with httpx.AsyncClient(transport=httpx.ASGITransport(app=app), base_url="http://test") as client:
                    response = await client.post("/api/ai/diagram/interpret", json={"prompt": PROMPT})
        self.assertEqual(response.status_code, 502)
        self.assertEqual(response.json(), {"detail": "La IA devolvio JSON que no cumple el contrato de operaciones."})

    async def test_invalid_json_and_incomplete_batch_have_distinct_errors(self):
        with patch("app.services.ai_service.AIService.generate", new=AsyncMock(return_value="{invalid")):
            async with httpx.AsyncClient(transport=httpx.ASGITransport(app=app), base_url="http://test") as client:
                invalid_json = await client.post("/api/ai/diagram/interpret", json={"prompt": "Crea Producto"})
        self.assertEqual(invalid_json.status_code, 502)
        self.assertEqual(invalid_json.json(), {"detail": "La IA devolvio una respuesta que no es JSON valido."})

        incomplete = json.dumps({"operations": [
            {"type": "ADD_ENTITY", "entity": {"name": "Producto", "attributes": []}},
        ]})
        with patch("app.services.ai_service.AIService.generate", new=AsyncMock(return_value=incomplete)):
            async with httpx.AsyncClient(transport=httpx.ASGITransport(app=app), base_url="http://test") as client:
                incomplete_response = await client.post(
                    "/api/ai/diagram/interpret", json={"prompt": "crea 2 entidades Producto y Categoria"})
        self.assertEqual(incomplete_response.status_code, 422)
        self.assertEqual(incomplete_response.json(), {
            "detail": "La IA no pudo completar todas las modificaciones solicitadas.",
        })
