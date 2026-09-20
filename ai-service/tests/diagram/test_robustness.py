import json
import unittest
from types import SimpleNamespace
from unittest.mock import AsyncMock

from app.schemas.diagram import DiagramContext, EntityDefinition
from app.services.diagram_ai_service import DiagramAIService, DiagramIncompleteResponseError


PROMPT = (
    "crea 3 clases una con nombre Categoria con atributos ID primary key y Nombre que se conecte con Corte, "
    "otra clase con nombre Peluqueria con atributo ID primary key tipo Integer y Nombre tipo Varchar y "
    "Ubicacion tipo Varchar y la ultima clase con nombre Usuario con atributo ID tipo Integer como primary key "
    "y Nombre tipo Varchar"
)
CONTEXT = DiagramContext(entities=[EntityDefinition(name="Corte", attributes=[])], relationships=[])


def entity(name: str) -> dict:
    return {"type": "ADD_ENTITY", "entity": {"name": name, "attributes": [
        {"name": "ID", "dataType": "Integer", "primaryKey": True, "nullable": False},
    ]}}


def empty_entity(name: str) -> dict:
    return {"type": "ADD_ENTITY", "entity": {"name": name, "attributes": []}}


def complete_batch() -> str:
    return json.dumps({"operations": [
        entity("Categoria"), entity("Peluqueria"), entity("Usuario"),
        {"type": "ADD_RELATIONSHIP", "relationship": {
            "sourceEntity": "Categoria", "targetEntity": "Corte",
            "sourceCardinality": "ONE_ONE", "targetCardinality": "ONE_ONE",
        }},
    ]})


def attribute(entity_name: str, name: str) -> dict:
    return {"type": "ADD_ATTRIBUTE", "entityName": entity_name, "attribute": {
        "name": name, "dataType": "String", "primaryKey": False, "nullable": True,
    }}


def relationship(source: str, target: str) -> dict:
    return {"type": "ADD_RELATIONSHIP", "relationship": {
        "sourceEntity": source, "targetEntity": target,
        "sourceCardinality": "ZERO_MANY", "targetCardinality": "ZERO_MANY",
    }}


class DiagramRobustnessTests(unittest.IsolatedAsyncioTestCase):
    async def test_reported_single_entity_phrasings_are_complete_without_repair(self):
        cases = [
            ("Crea una entidad llamada Docente.", "Docente", []),
            ("Créame una clase llamada Curso.", "Curso", []),
            ("Crea una entidad llamada Asignatura con un atributo nombre de tipo STRING.",
             "Asignatura", ["nombre"]),
        ]
        for prompt, expected_name, expected_attributes in cases:
            attributes = [
                {"name": "id", "dataType": "Integer", "primaryKey": True, "nullable": False},
                {"name": "nombre", "dataType": "String", "primaryKey": False, "nullable": True},
            ]
            raw = {"operations": [
                {"type": "ADD_ENTITY", "entity": {"name": expected_name, "attributes": attributes}},
                relationship(expected_name, "Categoria"),
            ]}
            model = AsyncMock(return_value=json.dumps(raw))
            result = await DiagramAIService(SimpleNamespace(generate=model)).interpret(prompt)
            self.assertEqual(len(result.operations), 1, prompt)
            self.assertEqual(result.operations[0].entity.name, expected_name, prompt)
            self.assertEqual([item.name for item in result.operations[0].entity.attributes],
                             expected_attributes, prompt)
            self.assertEqual(model.await_count, 1, prompt)

    async def test_equivalent_creation_phrasings_are_name_independent(self):
        for prompt, name in [
            ("Crear una entidad denominada Biblioteca.", "Biblioteca"),
            ("Genera una clase llamada Factura.", "Factura"),
            ("Añade una entidad llamada Inventario.", "Inventario"),
        ]:
            model = AsyncMock(return_value=json.dumps({"operations": [entity(name)]}))
            result = await DiagramAIService(SimpleNamespace(generate=model)).interpret(prompt)
            self.assertEqual(result.operations[0].entity.name, name)
            self.assertEqual(result.operations[0].entity.attributes, [])

    async def test_question_and_negated_creation_never_become_modifications(self):
        for prompt in ["No crees una entidad llamada Temporal.", "Explica qué entidades existen."]:
            model = AsyncMock(return_value=json.dumps({"operations": [entity("Temporal")]}))
            result = await DiagramAIService(SimpleNamespace(generate=model)).interpret(prompt)
            self.assertEqual(result.operations, [])

    async def test_single_attribute_remains_valid(self):
        context = DiagramContext(entities=[EntityDefinition(name="Alumno", attributes=[])], relationships=[])
        model = AsyncMock(return_value=json.dumps({"operations": [attribute("Alumno", "nombre")]}))
        result = await DiagramAIService(SimpleNamespace(generate=model)).interpret(
            "Agrega un atributo llamado nombre de tipo STRING a la entidad Alumno.", context)
        self.assertEqual(len(result.operations), 1)
        self.assertEqual(model.await_count, 1)

    async def test_two_attributes_for_different_entities_are_repaired_as_a_complete_batch(self):
        context = DiagramContext(entities=[
            EntityDefinition(name="Alumno", attributes=[]),
            EntityDefinition(name="Materia", attributes=[]),
        ], relationships=[])
        incomplete = json.dumps({"operations": [attribute("Alumno", "codigo")]})
        repaired = json.dumps({"operations": [
            attribute("Alumno", "codigo"), attribute("Materia", "codigo"),
        ]})
        model = AsyncMock(side_effect=[incomplete, repaired])
        result = await DiagramAIService(SimpleNamespace(generate=model)).interpret(
            "Agrega un atributo llamado codigo de tipo STRING a la entidad Alumno "
            "y un atributo llamado codigo de tipo STRING a la entidad Materia.", context)
        self.assertEqual([(item.entityName, item.attribute.name) for item in result.operations],
                         [("Alumno", "codigo"), ("Materia", "codigo")])
        self.assertEqual(model.await_count, 2)
        self.assertIn("materia.codigo", model.await_args_list[1].args[0])

    async def test_three_attribute_operations_are_preserved(self):
        context = DiagramContext(entities=[
            EntityDefinition(name=name, attributes=[]) for name in ("Alumno", "Materia", "Docente")
        ], relationships=[])
        operations = [attribute("Alumno", "codigo"), attribute("Materia", "codigo"),
                      attribute("Docente", "codigo")]
        model = AsyncMock(return_value=json.dumps({"operations": operations}))
        result = await DiagramAIService(SimpleNamespace(generate=model)).interpret(
            "Agrega un atributo codigo a la entidad Alumno, otro atributo codigo a la entidad Materia "
            "y otro atributo codigo a la entidad Docente.", context)
        self.assertEqual(len(result.operations), 3)
        self.assertEqual(model.await_count, 1)

    async def test_two_new_entities_must_precede_their_many_to_many_relation(self):
        incomplete = json.dumps({"operations": [empty_entity("Alumno"), relationship("Alumno", "Materia")]})
        repaired = json.dumps({"operations": [
            empty_entity("Alumno"), empty_entity("Materia"), relationship("Alumno", "Materia"),
        ]})
        model = AsyncMock(side_effect=[incomplete, repaired])
        result = await DiagramAIService(SimpleNamespace(generate=model)).interpret(
            "Crea las entidades Alumno y Materia y una relacion N:M entre ambas.",
            DiagramContext(entities=[], relationships=[]),
        )
        self.assertEqual([item.type for item in result.operations],
                         ["ADD_ENTITY", "ADD_ENTITY", "ADD_RELATIONSHIP"])
        self.assertEqual(model.await_count, 2)

    async def test_unrequested_attributes_are_not_accepted_for_new_entities(self):
        model = AsyncMock(return_value=json.dumps({"operations": [entity("Producto")]}))
        result = await DiagramAIService(SimpleNamespace(generate=model)).interpret("Crea la entidad Producto")
        self.assertEqual(result.operations[0].entity.attributes, [])
        self.assertEqual(model.await_count, 1)

    async def test_complex_three_class_batch_is_complete_with_existing_corte(self):
        model = AsyncMock(return_value=complete_batch())
        result = await DiagramAIService(SimpleNamespace(generate=model)).interpret(PROMPT, CONTEXT)
        self.assertEqual([op.entity.name for op in result.operations if op.type == "ADD_ENTITY"],
                         ["Categoria", "Peluqueria", "Usuario"])
        self.assertEqual(result.operations[-1].relationship.targetEntity, "Corte")

    async def test_one_of_two_entities_is_detected_and_repaired(self):
        incomplete = json.dumps({"operations": [empty_entity("Producto")]})
        repaired = json.dumps({"operations": [empty_entity("Producto"), empty_entity("Categoria")]})
        model = AsyncMock(side_effect=[incomplete, repaired])
        result = await DiagramAIService(SimpleNamespace(generate=model)).interpret(
            "crea 2 entidades Producto y Categoria")
        self.assertEqual(len(result.operations), 2)
        self.assertEqual(model.await_count, 2)
        self.assertIn("lote COMPLETO", model.await_args_list[1].args[0])

    async def test_invalid_json_can_be_repaired(self):
        model = AsyncMock(side_effect=["{invalid", json.dumps({"operations": [empty_entity("Producto")]})])
        result = await DiagramAIService(SimpleNamespace(generate=model)).interpret("crea 1 entidad Producto")
        self.assertEqual(result.operations[0].entity.name, "Producto")
        self.assertEqual(model.await_count, 2)

    async def test_failed_repair_has_only_one_retry(self):
        incomplete = json.dumps({"operations": [empty_entity("Producto")]})
        model = AsyncMock(return_value=incomplete)
        with self.assertRaises(DiagramIncompleteResponseError):
            await DiagramAIService(SimpleNamespace(generate=model)).interpret(
                "crea 2 entidades Producto y Categoria")
        self.assertEqual(model.await_count, 2)
