import json
import unittest
from unittest.mock import AsyncMock
from types import SimpleNamespace

from app.schemas.diagram import (
    ContextRelationship, DiagramContext, EntityDefinition,
)
from app.services.diagram_ai_service import DiagramAIService, DiagramIncompleteResponseError, parse_operations


def context(*relationships):
    return DiagramContext(
        entities=[EntityDefinition(name="Alumno", attributes=[]), EntityDefinition(name="Materia", attributes=[])],
        relationships=list(relationships),
    )


def relation(identifier="student-subject", name=None):
    return ContextRelationship(id=identifier, sourceEntity="Alumno", targetEntity="Materia",
                               sourceCardinality="ZERO_MANY", targetCardinality="ZERO_MANY", name=name)


def conversion(attributes=(), relationship_id="student-subject"):
    return {"operations": [{"type": "CONVERT_MANY_TO_MANY_ASSOCIATION", "conversion": {
        "relationshipId": relationship_id,
        "sourceEntity": "Alumno",
        "targetEntity": "Materia",
        "associationEntityName": "Inscripcion",
        "attributes": list(attributes),
    }}]}


def attribute(name, data_type):
    return {"name": name, "dataType": data_type, "primaryKey": False, "nullable": True}


class AssociationConversionAITests(unittest.IsolatedAsyncioTestCase):
    async def test_simple_conversion_with_two_attributes_uses_real_relationship_id(self):
        output = conversion([attribute("nota", "Integer"), attribute("fechaInscripcion", "Date")])
        generate = AsyncMock(return_value=json.dumps(output))
        result = await DiagramAIService(SimpleNamespace(generate=generate)).interpret(
            "Convierte la relacion N:M entre Alumno y Materia en una entidad asociativa llamada Inscripcion, con nota INTEGER y fechaInscripcion DATE",
            context(relation()),
        )
        self.assertEqual(len(result.operations), 1)
        self.assertEqual(result.operations[0].conversion.relationshipId, "student-subject")
        self.assertEqual([item.name for item in result.operations[0].conversion.attributes],
                         ["nota", "fechaInscripcion"])
        initial_prompt = generate.await_args.args[0]
        initial_schema = generate.await_args.args[1]
        self.assertIn('"relationshipId": "student-subject"', initial_prompt)
        self.assertNotIn("ID_REAL_DEL_CONTEXTO", initial_prompt)
        attributes_schema = initial_schema["$defs"]["AssociationConversionDefinition"]["properties"]["attributes"]
        self.assertEqual(attributes_schema["minItems"], 2)
        self.assertEqual(attributes_schema["maxItems"], 2)

    async def test_conversion_without_own_attributes_is_valid(self):
        generate = AsyncMock(return_value=json.dumps(conversion()))
        result = await DiagramAIService(SimpleNamespace(generate=generate)).interpret(
            "Convierte la relacion N:M entre Alumno y Materia en una entidad asociativa llamada Inscripcion",
            context(relation()),
        )
        self.assertEqual(result.operations[0].conversion.attributes, [])
        schema = generate.await_args.args[1]
        attributes_schema = schema["$defs"]["AssociationConversionDefinition"]["properties"]["attributes"]
        self.assertEqual(attributes_schema["maxItems"], 0)

    async def test_invented_id_and_unrequested_attribute_are_rejected_after_bounded_repair(self):
        for output in (
            conversion(relationship_id="invented"),
            conversion([attribute("estado", "String")]),
        ):
            model = AsyncMock(return_value=json.dumps(output))
            with self.assertRaises(DiagramIncompleteResponseError):
                await DiagramAIService(SimpleNamespace(generate=model)).interpret(
                    "Convierte la relacion N:M entre Alumno y Materia en una entidad asociativa llamada Inscripcion",
                    context(relation()),
                )
            self.assertEqual(model.await_count, 2)
            repair_prompt = model.await_args_list[1].args[0]
            self.assertIn("CONVERT_MANY_TO_MANY_ASSOCIATION", repair_prompt)
            self.assertIn('"relationshipId": "student-subject"', repair_prompt)

    async def test_multiple_candidates_and_ambiguous_attribute_request_clarification(self):
        model = AsyncMock(return_value=json.dumps(conversion()))
        with self.assertRaises(DiagramIncompleteResponseError):
            await DiagramAIService(SimpleNamespace(generate=model)).interpret(
                "Convierte la relacion N:M entre Alumno y Materia en una entidad asociativa llamada Inscripcion",
                context(relation("first", "cursa"), relation("second", "aprueba")),
            )
        ambiguous = AsyncMock(return_value=json.dumps(conversion([attribute("nota", "Integer")])))
        with self.assertRaises(DiagramIncompleteResponseError):
            await DiagramAIService(SimpleNamespace(generate=ambiguous)).interpret(
                "Convierte la relacion N:M entre Alumno y Materia en una entidad asociativa llamada Inscripcion con atributo nota",
                context(relation()),
            )

    def test_contract_rejects_generated_pk_and_extra_fields(self):
        invalid_pk = conversion([{"name": "id", "dataType": "Integer", "primaryKey": True, "nullable": False}])
        with self.assertRaises(Exception):
            parse_operations(json.dumps(invalid_pk))
        extra = conversion()
        extra["operations"][0]["conversion"]["foreignKey"] = "invented"
        with self.assertRaises(Exception):
            parse_operations(json.dumps(extra))


if __name__ == "__main__":
    unittest.main()
