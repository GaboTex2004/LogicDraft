import json
import unittest
from unittest.mock import AsyncMock, patch

import httpx
from app.main import app
from app.services.diagram_ai_service import parse_operations
from app.services.llm_json_parser import parse_llm_json_response


def entity(name, price=False):
    attributes = [
        {"name": "ID", "dataType": "Integer", "primaryKey": True, "nullable": False},
        {"name": "Nombre", "dataType": "Varchar", "primaryKey": False, "nullable": True},
    ]
    if price:
        attributes.append({"name": "Precio", "dataType": "Decimal", "primaryKey": False, "nullable": True})
    return {"type": "ADD_ENTITY", "entity": {"name": name, "attributes": attributes}}


DOCUMENT = {"operations": [entity("Categoria"), entity("Producto", True)]}
TEXT = json.dumps(DOCUMENT)
LOGGER = "app.services.llm_json_parser"


class ParserTests(unittest.TestCase):
    def test_pure_json(self):
        self.assertEqual(parse_llm_json_response(TEXT), DOCUMENT)

    def test_whitespace(self):
        self.assertEqual(parse_llm_json_response(" \n" + TEXT + "\t "), DOCUMENT)

    def test_complete_fence(self):
        for tag in ["json", "", "JSON"]:
            self.assertEqual(parse_llm_json_response("```" + tag + "\n" + TEXT + "\n\n```"), DOCUMENT)

    def test_prefix(self):
        self.assertEqual(parse_llm_json_response("Aquí tienes el resultado:\n" + TEXT), DOCUMENT)
        self.assertEqual(parse_llm_json_response('Explanation: {"operations":[]}'), {"operations": []})

    def test_suffix(self):
        self.assertEqual(parse_llm_json_response(TEXT + "\nListo."), DOCUMENT)

    def test_prefix_and_suffix(self):
        self.assertEqual(parse_llm_json_response("Texto opcional del modelo\n" + TEXT + "\nEstas son las entidades."), DOCUMENT)

    def test_direct_list(self):
        self.assertEqual(parse_llm_json_response("Resultado:\n" + json.dumps(DOCUMENT["operations"])), DOCUMENT["operations"])

    def test_nested_delimiters_and_escaped_quotes_inside_string(self):
        data = {"x": 'text } [ { \\ "quoted"'}
        self.assertEqual(parse_llm_json_response("Resultado: " + json.dumps(data)), data)

    def test_multiple_entities_order(self):
        result = parse_operations("Texto opcional del modelo\n" + TEXT)
        self.assertEqual([op.entity.name for op in result.operations], ["Categoria", "Producto"])

    def test_decimal(self):
        self.assertEqual(parse_operations(TEXT).operations[1].entity.attributes[2].dataType, "Double")

    def test_varchar(self):
        for op in parse_operations(TEXT).operations:
            self.assertEqual(op.entity.attributes[1].dataType, "String")

    def test_primary_keys(self):
        for op in parse_operations(TEXT).operations:
            self.assertEqual(op.entity.attributes[0].model_dump(), DOCUMENT["operations"][0]["entity"]["attributes"][0])

    def test_truncated_json(self):
        for text in [TEXT[:-1], "Resultado: " + TEXT[:-2], '{"operations": [', '{"broken": {"operations":[]}', '{"x":1 "y":2}']:
            with self.subTest(text=text), self.assertLogs(LOGGER), self.assertRaises(ValueError):
                parse_llm_json_response(text)

    def test_unclosed_quote(self):
        with self.assertLogs(LOGGER), self.assertRaises(ValueError):
            parse_llm_json_response('{"name":"unfinished}')

    def test_multiple_documents_and_non_prose_rejected(self):
        for suffix in [TEXT, " Otra opcion: " + TEXT, " true", " 42", ' "otro"', " ]", " ```", " = output;", " null"]:
            with self.subTest(suffix=suffix), self.assertLogs(LOGGER), self.assertRaises(ValueError):
                parse_llm_json_response(TEXT + suffix)

    def test_garbage_and_invalid_prefix_rejected(self):
        for text in ["not json", "", "true " + TEXT, '"broken ' + TEXT, "const x = " + TEXT, "{broken} " + TEXT]:
            with self.subTest(text=text), self.assertLogs(LOGGER), self.assertRaises(ValueError):
                parse_llm_json_response(text)

    def test_deterministic(self):
        for _ in range(3):
            self.assertEqual(parse_llm_json_response("Resultado: " + TEXT), DOCUMENT)

    def test_duplicate_keys_cannot_silently_drop_operations(self):
        for text in ['{"operations":[],"operations":[]}', 'Resultado: {"operations":[],"operations":[]}', '{"x":{"name":"A","name":"B"}}']:
            with self.subTest(text=text), self.assertLogs(LOGGER), self.assertRaises(ValueError):
                parse_llm_json_response(text)

    def test_observed_malformed_two_entity_output_is_not_repaired(self):
        first, second = DOCUMENT["operations"]
        # Observed shape repeats operations and misses the second operation's closing brace.
        raw = '{"operations":[' + json.dumps(first) + '],"operations":[' + json.dumps(second)[:-1] + ']}'
        with self.assertLogs(LOGGER), self.assertRaises(ValueError):
            parse_llm_json_response(raw)

    def test_debug_preview_redacts_secrets_and_is_bounded(self):
        secret = "eyJhbGciOiJIUzI1NiJ9.secret.signature"
        raw = '{"Authorization":"Bearer ' + secret + '","password":"super-private","x":' + 'secret-value ' * 2000
        with self.assertLogs(LOGGER, level="DEBUG") as logs, self.assertRaises(ValueError):
            parse_llm_json_response(raw)
        output = " ".join(logs.output)
        for value in [secret, "super-private", "secret-value", "Authorization"]:
            self.assertNotIn(value, output)
        self.assertIn("structuralPreview", output)
        self.assertLess(len(output), 2500)


class ParserEndpointTests(unittest.IsolatedAsyncioTestCase):
    async def test_two_entities_with_explanation_in_both_modes(self):
        for context in [None, {"entities": [], "relationships": []}]:
            body = {"prompt": "Crea Categoria y Producto"}
            if context is not None:
                body["diagram"] = context
            with patch("app.services.ai_service.AIService.generate", new=AsyncMock(return_value="Aquí tienes el resultado:\n" + TEXT)) as generate:
                async with httpx.AsyncClient(transport=httpx.ASGITransport(app=app), base_url="http://test") as client:
                    response = await client.post("/api/ai/diagram/interpret", json=body)
            self.assertEqual(response.status_code, 200, response.text)
            self.assertEqual(response.json(), parse_operations(TEXT).model_dump())
            self.assertIn("Crea Cliente con id y nombre, crea Pedido con id y total", generate.call_args.args[0])
