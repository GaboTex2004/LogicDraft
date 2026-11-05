import copy
import json
import os
from time import perf_counter
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import AsyncMock
import pytest

from app.schemas.agent import AgentAskRequest
from app.services.agent_service import AgentService
from app.skills.agent.structure_grounding import owned_attributes

FIXTURE = json.loads((Path(__file__).resolve().parents[3] / 'backend/src/test/resources/ai/compound-uml.json').read_text(encoding='utf-8'))


@pytest.fixture
def anyio_backend():
    return 'asyncio'


def request(message):
    return AgentAskRequest.model_validate({'message': message, 'context': {'projectId': 14, 'projectName': 'Fixture',
        'entities': [], 'relationships': [], 'recentEvents': [], 'uml': {'version': 2, 'nodes': [], 'edges': []}}})


@pytest.mark.anyio
async def test_four_classes_twelve_attributes_three_relations_have_one_pk_only():
    provider = AsyncMock(return_value=json.dumps(FIXTURE['structure']))
    result = await AgentService(SimpleNamespace(generate=provider)).ask(request(FIXTURE['message']))
    assert result.outcome == 'PROPOSAL', result.answer
    assert [op.type for op in result.operations] == ['ADD_ENTITY'] * 4 + ['ADD_RELATIONSHIP'] * 3
    assert sum(len(op.entity.attributes) for op in result.operations[:4]) == 12
    assert [[a.primaryKey for a in op.entity.attributes] for op in result.operations[:4]] == [[True, False, False]] + [[False]*3]*3
    provider.assert_awaited_once()


@pytest.mark.anyio
@pytest.mark.parametrize('corruption', ['omit_class', 'invent_attribute', 'swap_owners', 'wrong_endpoint', 'omit_relation', 'duplicate_relation'])
async def test_incomplete_or_invented_structure_is_rejected_atomically(corruption):
    structure = copy.deepcopy(FIXTURE['structure'])
    if corruption == 'omit_class': structure['classes'].pop()
    elif corruption == 'invent_attribute': structure['classes'][0]['attributes'][1]['name'] = 'inventado'
    elif corruption == 'swap_owners':
        structure['classes'][0]['attributes'][2], structure['classes'][1]['attributes'][1] = structure['classes'][1]['attributes'][1], structure['classes'][0]['attributes'][2]
    elif corruption == 'wrong_endpoint': structure['relationships'][0]['target'] = 'Producto'
    elif corruption == 'omit_relation': structure['relationships'].pop()
    else: structure['relationships'].append(structure['relationships'][0])
    provider = AsyncMock(return_value=json.dumps(structure))
    result = await AgentService(SimpleNamespace(generate=provider)).ask(request(FIXTURE['message']))
    assert result.outcome == 'NEEDS_CLARIFICATION'
    assert not result.operations
    assert provider.await_count == 2


def test_attribute_identity_includes_owner_even_when_name_and_type_repeat():
    attributes = owned_attributes('Cliente: ID Integer clave primaria, nombre String. Pedido: ID Integer, nombre String.', ['Cliente', 'Pedido'])
    assert attributes == [('cliente', 'id', 'Integer', True), ('cliente', 'nombre', 'String', False),
                          ('pedido', 'id', 'Integer', False), ('pedido', 'nombre', 'String', False)]


@pytest.mark.anyio
async def test_new_class_can_reference_existing_class_without_counting_it_as_new():
    req = request('Crea una clase Pedido con atributos ID Integer, fecha Date relacionada con la clase Cliente. Cliente(1..1) con Pedido(0..N).')
    req.context.uml = None
    from app.schemas.agent import AgentEntity
    req.context.entities = [AgentEntity(id='existing-client', name='Cliente', attributes=[])]
    body = {'classes': [{'name': 'Pedido', 'attributes': [
        {'name': 'ID', 'dataType': 'Integer', 'primaryKey': False}, {'name': 'fecha', 'dataType': 'Date', 'primaryKey': False}]}],
        'relationships': [{'source': 'Cliente', 'target': 'Pedido', 'sourceCardinality': 'ONE_ONE', 'targetCardinality': 'ZERO_MANY'}]}
    provider = AsyncMock(return_value=json.dumps(body))
    result = await AgentService(SimpleNamespace(generate=provider)).ask(req)
    assert result.outcome == 'PROPOSAL', result.answer
    assert [op.type for op in result.operations] == ['ADD_ENTITY', 'ADD_RELATIONSHIP']
    provider.assert_awaited_once()


@pytest.mark.anyio
async def test_mixed_creation_and_existing_attribute_use_general_operation_contract():
    req = request('Crea una clase Extra con atributo codigo String y agrega un atributo saldo Double en la clase Cliente')
    req.context.uml = None
    from app.schemas.agent import AgentEntity
    req.context.entities = [AgentEntity(id='existing-client', name='Cliente', attributes=[])]
    body = {'answer': 'Propuesta', 'operations': [
        {'type': 'ADD_ENTITY', 'entity': {'name': 'Extra', 'kind': 'CLASS', 'attributes': [{'name': 'codigo', 'dataType': 'String'}]}},
        {'type': 'ADD_ATTRIBUTE', 'entityName': 'Cliente', 'attribute': {'name': 'saldo', 'dataType': 'Double'}}]}
    result = await AgentService(SimpleNamespace(generate=AsyncMock(return_value=json.dumps(body)))).ask(req)
    assert result.outcome == 'PROPOSAL', result.answer
    assert [op.type for op in result.operations] == ['ADD_ENTITY', 'ADD_ATTRIBUTE']


@pytest.mark.anyio
async def test_underspecified_quantity_only_is_clarified_without_inventing_members():
    provider = AsyncMock()
    result = await AgentService(SimpleNamespace(generate=provider)).ask(request(
        '@LogicDraft crea cuatro clases UML: Cliente, Pedido, Producto y DetallePedido. Cada clase tiene tres atributos y deben existir tres relaciones entre ellas.'))
    assert result.outcome == 'NEEDS_CLARIFICATION'
    assert not result.operations
    provider.assert_not_awaited()


@pytest.mark.skipif(os.environ.get('LOGICDRAFT_TEST_COMPOUND_OLLAMA') != '1', reason='Opt-in local Ollama inference')
@pytest.mark.anyio
async def test_real_ollama_compound_interpretation():
    from app.services.ai_service import AIService
    from app.services.providers.ollama_provider import OllamaProvider
    started = perf_counter()
    result = await AgentService(AIService(OllamaProvider('http://127.0.0.1:11434', 'llama3.2:latest', 180))).ask(request(FIXTURE['message']))
    print(f'Local Ollama compound elapsedSeconds={perf_counter()-started:.2f} repairAttempts={result.repairAttempts}')
    assert result.outcome == 'PROPOSAL', result.answer
    assert [op.type for op in result.operations] == ['ADD_ENTITY']*4 + ['ADD_RELATIONSHIP']*3
    assert {op.entity.name: len(op.entity.attributes) for op in result.operations[:4]} == {e['name']: 3 for e in FIXTURE['structure']['classes']}
    assert sum(a.primaryKey for op in result.operations[:4] for a in op.entity.attributes) == 1
