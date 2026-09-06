import json
import unittest
from unittest.mock import AsyncMock, patch
import httpx
from pydantic import ValidationError
from app.main import app
from app.schemas.diagram import ContextRelationship, InterpretResponse, RelationshipDefinition
from app.services.diagram_ai_service import parse_operations


def relation(source='Cliente', target='Categoria', a='ONE_ONE', b='ONE_ONE'):
    return {'type': 'ADD_RELATIONSHIP', 'relationship': {
        'sourceEntity': source, 'targetEntity': target, 'sourceCardinality': a, 'targetCardinality': b}}


class CardinalityTests(unittest.TestCase):
    def test_all_endpoint_combinations(self):
        for a in ['ZERO_ONE', 'ONE_ONE', 'ZERO_MANY', 'ONE_MANY']:
            for b in ['ZERO_ONE', 'ONE_ONE', 'ZERO_MANY', 'ONE_MANY']:
                expected = relation(a=a, b=b)
                self.assertEqual(parse_operations(json.dumps(expected)).model_dump(), {'operations': [expected]})

    def test_invalid_missing_lowercase_and_legacy_output_rejected(self):
        for data in [
            {'sourceEntity': 'A', 'targetEntity': 'B'},
            {'sourceEntity': 'A', 'targetEntity': 'B', 'relationshipType': 'ONE_TO_ONE'},
            {**relation()['relationship'], 'targetCardinality': 'many'},
            {**relation()['relationship'], 'targetCardinality': '0..N'},
            {**relation()['relationship'], 'targetCardinality': None},
            {**relation()['relationship'], 'extra': True},
        ]:
            with self.subTest(data=data), self.assertRaises(ValidationError):
                RelationshipDefinition.model_validate(data)

    def test_legacy_context_maps_without_legacy_output_field(self):
        for kind, pair in {'ONE_TO_ONE': ('ONE_ONE', 'ONE_ONE'), 'ONE_TO_MANY': ('ONE_ONE', 'ZERO_MANY'),
                           'MANY_TO_ONE': ('ZERO_MANY', 'ONE_ONE'), 'MANY_TO_MANY': ('ZERO_MANY', 'ZERO_MANY'),
                           None: ('ONE_ONE', 'ONE_ONE')}.items():
            result = ContextRelationship.model_validate({'sourceEntity': 'A', 'targetEntity': 'B', 'relationshipType': kind})
            self.assertEqual((result.sourceCardinality, result.targetCardinality), pair)
            self.assertNotIn('relationshipType', result.model_dump())

    def test_reversed_equivalence_and_minima(self):
        result = parse_operations(json.dumps({'operations': [
            relation(a='ONE_ONE', b='ZERO_MANY'), relation('CATEGORIA', 'cliente', 'ZERO_MANY', 'ONE_ONE'),
            relation(a='ONE_ONE', b='ONE_MANY')]}))
        self.assertEqual(len(result.operations), 2)

    def test_entities_then_relation_survive_in_order(self):
        operations = [{'type': 'ADD_ENTITY', 'entity': {'name': name, 'attributes': []}} for name in ['Categoria', 'Producto']]
        operations.append(relation('Categoria', 'Producto', 'ONE_ONE', 'ZERO_MANY'))
        self.assertEqual(parse_operations(json.dumps({'operations': operations})).model_dump(), {'operations': operations})


class CardinalityEndpointTests(unittest.IsolatedAsyncioTestCase):
    async def test_natural_language_cases_share_strict_pipeline(self):
        # Mocked model boundary: checks contract and instructions, not LLM accuracy.
        cases = [
            ('conecta Cliente con Categoria', relation()),
            ('conecta Cliente con Categoria uno a uno', relation()),
            ('un Cliente puede tener muchos Pedidos', relation('Cliente', 'Pedido', 'ONE_ONE', 'ZERO_MANY')),
            ('cada Pedido pertenece a un Cliente', relation('Pedido', 'Cliente')),
            ('un Usuario puede tener cero o un Perfil', relation('Usuario', 'Perfil', 'ONE_ONE', 'ZERO_ONE')),
            ('una Categoria tiene uno o muchos Productos', relation('Categoria', 'Producto', 'ONE_ONE', 'ONE_MANY')),
        ]
        for prompt, operation in cases:
            with self.subTest(prompt=prompt), patch('app.services.ai_service.AIService.generate', new=AsyncMock(return_value=json.dumps(operation))) as generate:
                async with httpx.AsyncClient(transport=httpx.ASGITransport(app=app), base_url='http://test') as client:
                    response = await client.post('/api/ai/diagram/interpret', json={'prompt': prompt, 'diagram': {
                        'entities': [{'name': name, 'attributes': []} for name in ['Cliente', 'Categoria', 'Pedido', 'Usuario', 'Perfil', 'Producto']], 'relationships': []}})
                self.assertEqual(response.status_code, 200)
                self.assertEqual(response.json(), {'operations': [operation]})
                instruction = generate.call_args.args[0]
                schema = generate.call_args.args[1]
                self.assertIn('Sin cardinalidad o uno a uno: ONE_ONE/ONE_ONE', instruction)
                self.assertIn('Conecta Cliente con Categoria', instruction)
                self.assertIn('ONE_MANY', instruction)
                self.assertIn('Diagrama actual (JSON)', instruction)
                self.assertIn('devuelve unicamente ADD_RELATIONSHIP', instruction)
                self.assertNotIn('$defs', instruction)
                self.assertIn('Contrato exacto, sin otros campos', instruction)
                self.assertEqual(schema, InterpretResponse.model_json_schema())
