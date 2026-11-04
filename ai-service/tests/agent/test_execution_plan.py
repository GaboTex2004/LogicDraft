import json
from types import SimpleNamespace
from unittest.mock import AsyncMock

import pytest

from app.schemas.agent import AgentAskRequest
from app.services.agent_service import AgentService
from app.skills.agent.context import build_diagram_context
from app.skills.agent.execution_plan import cardinalities, plan_execution
from app.skills.agent.intent import AgentIntent, classify_agent_intent
from app.skills.diagram.completeness import extract_expectation


RELATION = "@LogicDraft agrega una relacion entre la clase Producto y categoria donde una categoria tiene artos productos y un producto si o si en una categoria"
COMPOUND = '@LogicDraft elimina el atributo "Precio de venta" de la clase Producto y crea un nuevo atributo en la clase compra con el nombre de "CANTIDAD" tipo int'


@pytest.fixture
def anyio_backend():
    return "asyncio"


def request(message=RELATION):
    return AgentAskRequest.model_validate({"message": message, "context": {
        "projectId": 1, "projectName": "Tienda", "diagramId": 2,
        "entities": [], "relationships": [], "recentEvents": [],
        "uml": {"version": 2, "nodes": [
            {"id": "p", "data": {"name": "Producto", "kind": "CLASS", "attributes": [
                {"id": "price", "name": "Precio de venta", "type": "Double"}], "methods": [
                {"id": "m", "name": "buscar", "returnType": "String", "parameters": [
                    {"id": "param", "name": "codigo", "type": "String"}]}]}},
            {"id": "c", "data": {"name": "Categoria", "kind": "CLASS"}},
            {"id": "a", "data": {"name": "compra", "kind": "ASSOCIATION_CLASS", "association": {
                "kind": "MANY_TO_MANY_ASSOCIATION", "tableName": "compra", "uniquePair": True,
                "endpoints": [{"role": "SOURCE", "entityId": "p", "relationshipId": "r1", "foreignKeyName": "producto_id"},
                              {"role": "TARGET", "entityId": "c", "relationshipId": "r2", "foreignKeyName": "categoria_id"}]}}},
            {"id": "s", "data": {"name": "Estado", "kind": "ENUMERATION", "literals": ["ACTIVO"]}},
        ], "edges": []}}})


def body(operations):
    return json.dumps({"answer": "Abre el editor y hazlo manualmente. Ya lo hice.", "operations": operations})


@pytest.mark.parametrize("message", [RELATION, COMPOUND, RELATION + " ¿correcto?"])
def test_exact_commands_are_mutations(message):
    assert classify_agent_intent(message) is AgentIntent.MODIFICATION


@pytest.mark.parametrize("message", ["¿Cómo creo una relación?", "¿Qué pasaría si elimino Producto?"])
def test_questions_are_informational(message):
    assert classify_agent_intent(message) is AgentIntent.INFORMATIONAL


def test_exact_relation_plan_and_completeness():
    ctx = build_diagram_context(request())
    plan = plan_execution(RELATION, ctx)
    assert plan.reason is None
    assert len(plan.operations) == 1
    assert plan.operations[0]["relationship"] == {"sourceEntity": "Producto", "targetEntity": "Categoria",
        "sourceCardinality": "ZERO_MANY", "targetCardinality": "ONE_ONE"}
    assert extract_expectation(RELATION, ctx).relationship_pairs == (("producto", "categoria"),)


def test_exact_compound_preserves_association_class_and_case():
    ctx = build_diagram_context(request(COMPOUND))
    plan = plan_execution(COMPOUND, ctx)
    assert plan.reason is None
    assert [op["type"] for op in plan.operations] == ["DELETE_ATTRIBUTE", "ADD_ATTRIBUTE"]
    assert plan.operations[0]["attributeName"] == "Precio de venta"
    assert plan.operations[1]["entityName"] == "compra"
    assert plan.operations[1]["attribute"]["name"] == "CANTIDAD"
    assert plan.operations[1]["attribute"]["dataType"] == "Integer"
    expectation = extract_expectation(COMPOUND, ctx)
    assert expectation.attribute_targets == (("compra", "cantidad"),)
    assert expectation.entity_names == ()


@pytest.mark.parametrize("word", ["muchos", "varios", "hartos", "artos"])
def test_spanish_quantifiers_are_not_classifiers(word):
    assert cardinalities(f"una categoria tiene {word} productos y un producto pertenece obligatoriamente a una categoria",
                         "Producto", "Categoria") == ("ZERO_MANY", "ONE_ONE")


@pytest.mark.parametrize("notation,expected", [
    ("producto 0..1 categoria 1..1", ("ZERO_ONE", "ONE_ONE")),
    ("producto 0..N categoria 1..N", ("ZERO_MANY", "ONE_MANY")),
    ("1:1", ("ONE_ONE", "ONE_ONE")), ("1:N", ("ONE_ONE", "ZERO_MANY")),
    ("N:M", ("ZERO_MANY", "ZERO_MANY")),
    ("una categoria tiene al menos un producto y un producto si o si tiene una categoria", ("ONE_MANY", "ONE_ONE")),
])
def test_cardinalities(notation, expected):
    assert cardinalities(notation.lower(), "Producto", "Categoria") == expected


@pytest.mark.parametrize("message", ["Crea un atributo en Producto llamado Stock de tipo INTEGER",
    "Añade a Producto el campo Stock int"])
def test_owner_before_attribute(message):
    plan = plan_execution(message, build_diagram_context(request(message)))
    assert plan.operations[0]["attribute"]["name"] == "Stock"


@pytest.mark.anyio
@pytest.mark.parametrize("message", [RELATION, COMPOUND])
async def test_exact_instructions_provider_to_validated_proposal(message):
    req = request(message)
    plan = plan_execution(message, build_diagram_context(req))
    generate = AsyncMock(return_value=body(list(plan.operations)))
    result = await AgentService(SimpleNamespace(generate=generate)).ask(req)
    assert result.outcome == "PROPOSAL"
    assert len(result.operations) == len(plan.operations)
    assert "Ya lo hice" not in result.answer
    assert result.repairAttempts == 0
    generate.assert_not_awaited()


@pytest.mark.anyio
async def test_verified_plan_does_not_depend_on_invalid_model_response():
    generate = AsyncMock(return_value=body([]))

    result = await AgentService(
        SimpleNamespace(generate=generate)
    ).ask(request())

    assert result.outcome == "PROPOSAL"
    assert len(result.operations) == 1
    assert result.operations[0].type == "ADD_RELATIONSHIP"
    generate.assert_not_awaited()


@pytest.mark.anyio
async def test_compound_plan_preserves_both_operations_without_provider():
    generate = AsyncMock(return_value=body([]))

    result = await AgentService(
        SimpleNamespace(generate=generate)
    ).ask(request(COMPOUND))

    assert result.outcome == "PROPOSAL"
    assert [op.type for op in result.operations] == [
        "DELETE_ATTRIBUTE",
        "ADD_ATTRIBUTE",
    ]
    generate.assert_not_awaited()

@pytest.mark.anyio
async def test_missing_classifier_is_clarified_without_provider_or_implicit_creation():
    req = request()
    req.context.uml.nodes = req.context.uml.nodes[:1]
    generate = AsyncMock()
    result = await AgentService(SimpleNamespace(generate=generate)).ask(req)
    assert result.outcome == "NEEDS_CLARIFICATION" and not result.operations
    assert "categoria" in result.answer
    generate.assert_not_awaited()


@pytest.mark.anyio
@pytest.mark.parametrize("cardinality,outcome", [("ZERO_MANY", "ALREADY_SATISFIED"), ("ONE_ONE", "NEEDS_CLARIFICATION")])
async def test_existing_relation_never_duplicated_or_silently_updated(cardinality, outcome):
    data = request().model_dump(mode="json", exclude_none=True)
    data["context"]["uml"]["edges"] = [{"id": "real-edge-id", "source": "p", "target": "c", "data": {
        "sourceCardinality": cardinality, "targetCardinality": "ONE_ONE"}}]
    req = AgentAskRequest.model_validate(data)
    generate = AsyncMock()
    result = await AgentService(SimpleNamespace(generate=generate)).ask(req)
    assert result.outcome == outcome and not result.operations
    generate.assert_not_awaited()
    if cardinality != "ZERO_MANY":
        plan = plan_execution(RELATION.replace("agrega", "modifica"), build_diagram_context(req))
        assert plan.operations[0]["type"] == "UPDATE_RELATIONSHIP"
        assert plan.operations[0]["relationship"]["id"] == "real-edge-id"


def test_v1_context_remains_supported():
    data = request().model_dump(mode="json", exclude_none=True)
    data["context"]["uml"] = {"version": 1, "nodes": [
        {"id": "p", "data": {"name": "Producto", "attributes": []}},
        {"id": "c", "data": {"name": "Categoria", "attributes": []}}], "edges": []}
    assert plan_execution(RELATION, build_diagram_context(AgentAskRequest.model_validate(data))).operations


@pytest.mark.anyio
@pytest.mark.parametrize("message,operation", [
    ("Crea la clase Nueva", {"type": "ADD_ENTITY", "entity": {"name": "Nueva", "kind": "CLASS", "attributes": []}}),
    ("Renombra la clase Producto a Articulo", {"type": "RENAME_ENTITY", "entityName": "Producto", "newName": "Articulo"}),
    ("Elimina la clase Categoria", {"type": "DELETE_ENTITY", "entityName": "Categoria"}),
    ('Cambia el atributo "Precio de venta" de Producto a Integer', {"type": "CHANGE_ATTRIBUTE_TYPE", "entityName": "Producto", "attributeName": "Precio de venta", "dataType": "Integer"}),
    ("Crea el metodo contar en Producto", {"type": "ADD_METHOD", "entityName": "Producto", "method": {"name": "contar", "returnType": "Integer"}}),
    ("Modifica el metodo buscar de Producto a retorno Integer", {"type": "UPDATE_METHOD", "entityName": "Producto", "methodName": "buscar", "returnType": "Integer"}),
    ("Elimina el metodo buscar de Producto", {"type": "DELETE_METHOD", "entityName": "Producto", "methodName": "buscar"}),
    ("Agrega el parametro limite al metodo buscar de Producto", {"type": "ADD_METHOD_PARAMETER", "entityName": "Producto", "methodName": "buscar", "parameter": {"name": "limite", "type": "Integer"}}),
    ("Modifica el parametro codigo del metodo buscar de Producto a Integer", {"type": "UPDATE_METHOD_PARAMETER", "entityName": "Producto", "methodName": "buscar", "parameterName": "codigo", "newType": "Integer"}),
    ("Elimina el parametro codigo del metodo buscar de Producto", {"type": "DELETE_METHOD_PARAMETER", "entityName": "Producto", "methodName": "buscar", "parameterName": "codigo"}),
    ("Agrega el literal INACTIVO a Estado", {"type": "ADD_ENUM_LITERAL", "entityName": "Estado", "literal": "INACTIVO"}),
    ("Renombra el literal ACTIVO de Estado a VIGENTE", {"type": "RENAME_ENUM_LITERAL", "entityName": "Estado", "literal": "ACTIVO", "newName": "VIGENTE"}),
    ("Elimina el literal ACTIVO de la enumeracion Estado", {"type": "DELETE_ENUM_LITERAL", "entityName": "Estado", "literal": "ACTIVO"}),
])
async def test_existing_crud_families_survive_agent_pipeline(message, operation):
    generate = AsyncMock(return_value=body([operation]))
    result = await AgentService(SimpleNamespace(generate=generate)).ask(request(message))
    assert result.outcome == "PROPOSAL", result.answer
    assert [op.type for op in result.operations] == [operation["type"]]
    generate.assert_awaited_once()
