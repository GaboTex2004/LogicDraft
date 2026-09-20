import copy
import json
import unittest
from unittest.mock import AsyncMock, patch

import httpx
from app.main import app
from app.schemas.diagram import InterpretRequest
from app.schemas.data_types import normalize_data_type, normalize_type_aliases
from app.services.diagram_ai_service import parse_operations
from app.services.providers.base import ProviderResponseError


PROMPT = 'crea una entidad "Cliente" con atributo "ID" tipo Integer y que sea primary key, otro atributo "Nombre" tipo Varchar'
EXAMPLE = {"operations": [{"type": "ADD_ENTITY", "entity": {
    "name": "Cliente", "attributes": [
        {"name": "ID", "dataType": "Integer", "primaryKey": True, "nullable": False},
        {"name": "Nombre", "dataType": "Varchar", "primaryKey": False, "nullable": True},
    ]}}]}
LOGGER = "app.services.diagram_ai_service"


class AliasTests(unittest.TestCase):
    def test_all_requested_aliases(self):
        groups = {
            "String": ["Varchar", "VARCHAR", "varchar(255)", "VARCHAR ( 32 )", "CHAR", "TEXT", "STRING", "string"],
            "Integer": ["INT", "INTEGER", "integer"],
            "Long": ["BIGINT", "LONG", "long"],
            "Double": ["FLOAT", "DOUBLE", "DOUBLE PRECISION", "DECIMAL", "NUMERIC", "REAL", "double"],
            "Boolean": ["BOOL", "BOOLEAN", "boolean"],
            "Date": ["DATE", "date"],
            "DateTime": ["DATETIME", "TIMESTAMP", "TIMESTAMP WITHOUT TIME ZONE", "TIMESTAMP WITH TIME ZONE", "datetime"],
        }
        for canonical, aliases in groups.items():
            for alias in aliases:
                with self.subTest(alias=alias):
                    document = copy.deepcopy(EXAMPLE)
                    document["operations"][0]["entity"]["attributes"][1]["dataType"] = alias
                    result = parse_operations(json.dumps(document))
                    self.assertEqual(result.operations[0].entity.attributes[1].dataType, canonical)
                    self.assertEqual(normalize_data_type(canonical), canonical)

    def test_exact_example_preserves_names_pk_and_nullable(self):
        expected = copy.deepcopy(EXAMPLE)
        expected["operations"][0]["entity"]["attributes"][1]["dataType"] = "String"
        self.assertEqual(parse_operations(json.dumps(EXAMPLE)).model_dump(), expected)

    def test_multiple_aliases_and_standalone_attribute_before_dedup(self):
        document = copy.deepcopy(EXAMPLE)
        document["operations"][0]["entity"]["attributes"][0]["dataType"] = "INT"
        document["operations"].append({"type": "ADD_ATTRIBUTE", "entityName": "Cliente",
            "attribute": {"name": "Nombre", "dataType": "varchar(255)"}})
        document["operations"].append({"type": "ADD_ATTRIBUTE", "entityName": "Cliente",
            "attribute": {"name": "Activo", "dataType": "BOOL"}})
        result = parse_operations(json.dumps(document))
        self.assertEqual(len(result.operations), 2)
        self.assertEqual(result.operations[1].attribute.dataType, "Boolean")

    def test_recursive_context_is_idempotent_and_does_not_mutate(self):
        document = {"diagram": {"entities": [EXAMPLE["operations"][0]["entity"]], "relationships": []}}
        original = copy.deepcopy(document)
        normalized = normalize_type_aliases(document)
        self.assertEqual(normalize_type_aliases(normalized), normalized)
        self.assertEqual(document, original)
        request = InterpretRequest.model_validate({"prompt": PROMPT, **document})
        self.assertEqual(request.diagram.entities[0].attributes[1].dataType, "String")

    def test_unknown_and_malformed_types_are_not_coerced(self):
        for value in ["BananaType", "varchar(0)", "varchar(foo)", "varchar(255) garbage", None, 12, True, {}]:
            with self.subTest(value=value):
                document = copy.deepcopy(EXAMPLE)
                document["operations"][0]["entity"]["attributes"][1]["dataType"] = value
                with self.assertLogs(LOGGER, level="WARNING"), self.assertRaises(ProviderResponseError):
                    parse_operations(json.dumps(document))

    def test_diagnostics_do_not_log_raw_json_or_jwt(self):
        secret = "eyJhbGciOiJIUzI1NiJ9.secret.signature"
        with self.assertLogs(LOGGER, level="WARNING") as logs, self.assertRaises(ProviderResponseError):
            parse_operations(secret)
        self.assertNotIn(secret, " ".join(logs.output))
        document = copy.deepcopy(EXAMPLE)
        document["operations"][0]["entity"]["attributes"][1]["dataType"] = secret
        with self.assertLogs(LOGGER, level="WARNING") as logs, self.assertRaises(ProviderResponseError):
            parse_operations(json.dumps(document))
        self.assertNotIn(secret, " ".join(logs.output))
        self.assertIn("<redacted>", " ".join(logs.output))

    def test_strict_flags_and_extra_fields_remain_rejected(self):
        for key, value in [("primaryKey", "true"), ("unexpected", "value")]:
            document = copy.deepcopy(EXAMPLE)
            document["operations"][0]["entity"]["attributes"][0][key] = value
            with self.assertLogs(LOGGER, level="WARNING"), self.assertRaises(ProviderResponseError):
                parse_operations(json.dumps(document))


class AliasEndpointTests(unittest.IsolatedAsyncioTestCase):
    async def test_exact_prompt_with_and_without_context(self):
        for context in [None, {"entities": [], "relationships": []}]:
            with self.subTest(context=context):
                body = {"prompt": PROMPT}
                if context is not None:
                    body["diagram"] = context
                with patch("app.services.ai_service.AIService.generate", new=AsyncMock(return_value=json.dumps(EXAMPLE))) as generate:
                    async with httpx.AsyncClient(transport=httpx.ASGITransport(app=app), base_url="http://test") as client:
                        response = await client.post("/api/ai/diagram/interpret", json=body)
                self.assertEqual(response.status_code, 200, response.text)
                attributes = response.json()["operations"][0]["entity"]["attributes"]
                self.assertEqual(attributes[0], EXAMPLE["operations"][0]["entity"]["attributes"][0])
                self.assertEqual(attributes[1]["dataType"], "String")
                self.assertIn(json.dumps(PROMPT), generate.call_args.args[0])
                self.assertIn("Conserva exactamente mayusculas y minusculas", generate.call_args.args[0])

    async def test_unknown_type_returns_controlled_502_and_useful_log(self):
        document = copy.deepcopy(EXAMPLE)
        document["operations"][0]["entity"]["attributes"][1]["dataType"] = "BananaType"
        with patch("app.services.ai_service.AIService.generate", new=AsyncMock(return_value=json.dumps(document))):
            with self.assertLogs(LOGGER, level="WARNING") as logs:
                async with httpx.AsyncClient(transport=httpx.ASGITransport(app=app), base_url="http://test") as client:
                    response = await client.post("/api/ai/diagram/interpret", json={"prompt": PROMPT, "diagram": {"entities": [], "relationships": []}})
        self.assertEqual(response.status_code, 502)
        self.assertEqual(response.json(), {"detail": "La IA devolvio JSON que no cumple el contrato de operaciones."})
        self.assertIn("unsupported dataType 'BananaType'", " ".join(logs.output))
