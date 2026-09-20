import json
import unittest
from pydantic import ValidationError
from app.schemas.diagram import InterpretRequest
from app.services.diagram_ai_service import parse_operations
from app.services.providers.base import ProviderResponseError


class DiagramContractTests(unittest.TestCase):
    def test_three_operations(self):
        operations = [
            {"type": "ADD_ENTITY", "entity": {"name": "Cliente", "attributes": [{"name": "id", "dataType": "bigint", "primaryKey": True, "nullable": False}]}},
            {"type": "ADD_ATTRIBUTE", "entityName": "Cliente", "attribute": {"name": "telefono", "dataType": "varchar"}},
            {"type": "ADD_RELATIONSHIP", "relationship": {"sourceEntity": "Cliente", "targetEntity": "Pedido", "sourceCardinality": "ONE_ONE", "targetCardinality": "ZERO_MANY"}},
        ]
        result = parse_operations(json.dumps({"operations": operations}))
        self.assertEqual(result.operations[0].entity.attributes[0].dataType, "Long")
        self.assertEqual(result.operations[1].attribute.dataType, "String")
        self.assertEqual(result.operations[2].relationship.targetCardinality, "ZERO_MANY")

    def test_safe_fence_and_empty_operations(self):
        self.assertEqual(parse_operations('```json\n{"operations":[]}\n```').operations, [])

    def test_many_to_many_and_associative_entity_operations_are_supported(self):
        many = {"type": "ADD_RELATIONSHIP", "relationship": {
            "sourceEntity": "Alumno", "targetEntity": "Materia",
            "sourceCardinality": "ZERO_MANY", "targetCardinality": "ONE_MANY",
            "name": "materias", "joinTableName": "alumno_materia",
        }}
        associative = [
            {"type": "ADD_ENTITY", "entity": {"name": "Inscripcion", "attributes": [
                {"name": "fecha", "dataType": "Date", "primaryKey": False, "nullable": False},
                {"name": "nota", "dataType": "Double", "primaryKey": False, "nullable": True},
            ]}},
            {"type": "ADD_RELATIONSHIP", "relationship": {"sourceEntity": "Alumno",
                "targetEntity": "Inscripcion", "sourceCardinality": "ONE_ONE", "targetCardinality": "ZERO_MANY"}},
            {"type": "ADD_RELATIONSHIP", "relationship": {"sourceEntity": "Materia",
                "targetEntity": "Inscripcion", "sourceCardinality": "ONE_ONE", "targetCardinality": "ZERO_MANY"}},
        ]
        result = parse_operations(json.dumps({"operations": [many, *associative]}))
        self.assertEqual(result.operations[0].relationship.name, "materias")
        self.assertEqual(result.operations[0].relationship.joinTableName, "alumno_materia")
        self.assertEqual(result.operations[1].entity.name, "Inscripcion")
        self.assertEqual(len(result.operations), 4)

    def test_invalid_outputs(self):
        for content in [
            "not json", '{"operations":[{"type":"DELETE_ENTITY"}]}',
            '{"operations":[{"type":"ADD_ENTITY","entity":{"name":"","attributes":[]}}]}',
            '{"operations":[{"type":"ADD_ATTRIBUTE","entityName":"Cliente","attribute":{"name":"x","dataType":"SQL"}}]}',
            '{"operations":[{"type":"ADD_RELATIONSHIP","relationship":{"sourceEntity":"A","targetEntity":"B","relationshipType":"OTHER"}}]}',
            '{"operations":[],"sql":"DROP TABLE x"}',
        ]:
            with self.subTest(content=content), self.assertRaises(ProviderResponseError):
                parse_operations(content)

    def test_blank_prompt(self):
        with self.assertRaises(ValidationError):
            InterpretRequest(prompt="  ")


if __name__ == "__main__":
    unittest.main()
