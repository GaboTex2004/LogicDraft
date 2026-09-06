import json
import unittest
from unittest.mock import AsyncMock, patch

import httpx

from app.main import app
from app.schemas.diagram import InterpretResponse
from app.services.diagram_ai_service import parse_operations
from app.services.diagram_normalizer import DiagramConflictError, normalize_operations


def attribute(name="correo", data_type="String", **flags):
    return {"name": name, "dataType": data_type, **flags}


def entity(*attributes):
    return {"type": "ADD_ENTITY", "entity": {"name": "Cliente", "attributes": list(attributes)}}


def add(attr=None, name="Cliente"):
    return {"type": "ADD_ATTRIBUTE", "entityName": name, "attribute": attr or attribute()}


def relation(source="Cliente", target="Pedido", kind="ONE_TO_MANY"):
    return {"type": "ADD_RELATIONSHIP", "relationship": {
        "sourceEntity": source, "targetEntity": target, "sourceCardinality": "ONE_ONE",
        "targetCardinality": "ZERO_MANY" if kind == "ONE_TO_MANY" else "ONE_ONE",
    }}


def parse(*operations):
    return parse_operations(json.dumps({"operations": list(operations)}))


class DiagramNormalizerTests(unittest.TestCase):
    def test_entity_and_redundant_attribute_in_both_orders(self):
        for operations in [(entity(attribute()), add()), (add(), entity(attribute()))]:
            with self.subTest(operations=operations):
                result = parse(*operations)
                self.assertEqual([op.type for op in result.operations], ["ADD_ENTITY"])
                self.assertEqual(result.operations[0].entity.attributes[0].name, "correo")

    def test_duplicates_inside_entity_keep_first_spelling(self):
        result = parse(entity(attribute("Correo", "varchar"), attribute("CORREO")))
        self.assertEqual(len(result.operations[0].entity.attributes), 1)
        self.assertEqual(result.operations[0].entity.attributes[0].name, "Correo")

    def test_duplicate_add_attribute_case_insensitive(self):
        result = parse(add(attribute("Correo")), add(attribute("CORREO"), "CLIENTE"))
        self.assertEqual(len(result.operations), 1)
        self.assertEqual(result.operations[0].attribute.name, "Correo")

    def test_duplicate_relationship_preserves_direction_and_type(self):
        result = parse(relation(), relation("CLIENTE", "PEDIDO"),
                       relation("Pedido", "Cliente"), relation(kind="ONE_TO_ONE"))
        self.assertEqual(len(result.operations), 3)

    def test_type_conflicts_in_all_locations(self):
        integer, string = attribute("edad", "Integer"), attribute("EDAD", "String")
        for operations in [
            [add(integer), add(string)],
            [entity(integer, string)],
            [entity(integer), add(string)],
            [add(string), entity(integer)],
        ]:
            with self.subTest(operations=operations), self.assertRaises(DiagramConflictError):
                parse(*operations)

    def test_flags_are_real_conflicts(self):
        for flags in [{"primaryKey": True}, {"nullable": False}]:
            with self.subTest(flags=flags), self.assertRaises(DiagramConflictError):
                parse(add(), add(attribute(**flags)))

    def test_order_of_survivors_and_other_entities(self):
        result = parse(entity(attribute()), add(), add(attribute("telefono")),
                       relation(), relation(), add(name="Pedido"))
        self.assertEqual([op.type for op in result.operations],
                         ["ADD_ENTITY", "ADD_ATTRIBUTE", "ADD_RELATIONSHIP", "ADD_ATTRIBUTE"])
        self.assertEqual(result.operations[-1].entityName, "Pedido")

    def test_idempotent_and_does_not_mutate_input(self):
        original = InterpretResponse.model_validate({"operations": [
            entity(attribute(), attribute("CORREO")), add(), relation(), relation(),
        ]})
        before = original.model_dump()
        normalized = normalize_operations(original)
        self.assertEqual(original.model_dump(), before)
        self.assertEqual(normalize_operations(normalized), normalized)

    def test_empty_operations(self):
        self.assertEqual(parse().operations, [])


class DiagramNormalizationHttpTests(unittest.IsolatedAsyncioTestCase):
    async def test_endpoint_returns_deduplicated_operations(self):
        with patch("app.services.ai_service.AIService.generate",
                   new=AsyncMock(return_value=json.dumps({"operations": [entity(attribute()), add()]}))):
            async with httpx.AsyncClient(transport=httpx.ASGITransport(app=app), base_url="http://test") as client:
                response = await client.post("/api/ai/diagram/interpret", json={"prompt": "Crea Cliente"})
        self.assertEqual(response.status_code, 200)
        self.assertEqual(len(response.json()["operations"]), 1)

    async def test_conflict_is_controlled_http_error(self):
        with patch("app.services.ai_service.AIService.generate",
                   new=AsyncMock(return_value=json.dumps({"operations": [
                       add(attribute("edad", "Integer")), add(attribute("edad", "String")),
                   ]}))):
            async with httpx.AsyncClient(transport=httpx.ASGITransport(app=app), base_url="http://test") as client:
                response = await client.post("/api/ai/diagram/interpret", json={"prompt": "Agrega edad"})
        self.assertEqual(response.status_code, 502)
        self.assertEqual(response.json(), {
            "detail": "La respuesta de IA contiene definiciones de atributos en conflicto.",
        })
