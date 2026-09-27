import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { applyDiagramOperations, parseDiagramAiResponse } from '../src/features/diagram/services/applyDiagramOperations.ts'
import { validateAssociationDocument } from '../src/features/diagram/services/associationConversion.ts'

let id = 0
const apply = (document, operations) => applyDiagramOperations(document.nodes, document.edges, operations, () => `crud-${++id}`)
const empty = () => ({ nodes: [], edges: [] })
const create = name => ({ type: 'ADD_ENTITY', entity: { name, attributes: [] } })
const attr = (name, dataType = 'String', primaryKey = false, nullable = true) => ({ name, dataType, primaryKey, nullable })

test('Gemini interpretation failures use a stage-specific message', () => {
  const editor = readFileSync(new URL('../src/features/diagram/pages/DiagramEditorPage.tsx', import.meta.url), 'utf8')
  assert.match(editor, /code === 502[\s\S]*No se pudo interpretar la instrucción\./)
})

test('entity CRUD is semantic, atomic and cascades dependent relationships', () => {
  let result = apply(empty(), [create('Cliente'), create('Venta')])
  result = apply(result, [{ type: 'RENAME_ENTITY', entityName: 'Cliente', newName: 'Persona' }, {
    type: 'ADD_RELATIONSHIP', relationship: { sourceEntity: 'Persona', targetEntity: 'Venta',
      sourceCardinality: 'ONE_ONE', targetCardinality: 'ZERO_MANY' },
  }])
  assert.deepEqual(result.nodes.map(node => node.data.name), ['Persona', 'Venta'])
  assert.equal(result.edges.length, 1)
  result = apply(result, [{ type: 'DELETE_ENTITY', entityName: 'Venta' }])
  assert.deepEqual(result.nodes.map(node => node.data.name), ['Persona'])
  assert.equal(result.edges.length, 0)
  assert.equal(result.events[0].type, 'DIAGRAM_BATCH_APPLIED')
})

test('attribute CRUD covers rename, type, PK, nullable and deletion', () => {
  let result = apply(empty(), [{ type: 'ADD_ENTITY', entity: { name: 'Producto', attributes: [attr('stock', 'Integer')] } }])
  result = apply(result, [
    { type: 'RENAME_ATTRIBUTE', entityName: 'Producto', attributeName: 'stock', newName: 'cantidad' },
    { type: 'CHANGE_ATTRIBUTE_TYPE', entityName: 'Producto', attributeName: 'cantidad', dataType: 'Double' },
    { type: 'SET_ATTRIBUTE_PRIMARY_KEY', entityName: 'Producto', attributeName: 'cantidad', value: true },
  ])
  let attribute = result.nodes[0].data.attributes[0]
  assert.deepEqual({ name: attribute.name, type: attribute.type, primaryKey: attribute.primaryKey, nullable: attribute.nullable },
    { name: 'cantidad', type: 'DECIMAL', primaryKey: true, nullable: false })
  assert.throws(() => apply(result, [{ type: 'SET_ATTRIBUTE_NULLABLE', entityName: 'Producto', attributeName: 'cantidad', value: true }]),
    /PK.*null/)
  result = apply(result, [
    { type: 'SET_ATTRIBUTE_PRIMARY_KEY', entityName: 'Producto', attributeName: 'cantidad', value: false },
    { type: 'SET_ATTRIBUTE_NULLABLE', entityName: 'Producto', attributeName: 'cantidad', value: true },
    { type: 'DELETE_ATTRIBUTE', entityName: 'Producto', attributeName: 'cantidad' },
  ])
  assert.equal(result.nodes[0].data.attributes.length, 0)
})

test('relationship CRUD updates orientation safely and rejects ambiguity', () => {
  let result = apply(empty(), [create('Usuario'), create('Venta'), {
    type: 'ADD_RELATIONSHIP', relationship: { sourceEntity: 'Usuario', targetEntity: 'Venta',
      sourceCardinality: 'ONE_ONE', targetCardinality: 'ZERO_MANY', name: 'compras' },
  }])
  result = apply(result, [{ type: 'UPDATE_RELATIONSHIP', relationship: { sourceEntity: 'Usuario', targetEntity: 'Venta',
    name: 'compras', sourceCardinality: 'ONE_ONE', targetCardinality: 'ONE_ONE' } }])
  assert.deepEqual([result.edges[0].data.sourceCardinality, result.edges[0].data.targetCardinality], ['ONE_ONE', 'ONE_ONE'])
  result = apply(result, [{ type: 'DELETE_RELATIONSHIP', relationship: {
    sourceEntity: 'Venta', targetEntity: 'Usuario', name: 'compras' } }])
  assert.equal(result.edges.length, 0)

  const ambiguous = apply(empty(), [create('A'), create('B'),
    { type: 'ADD_RELATIONSHIP', relationship: { sourceEntity: 'A', targetEntity: 'B', sourceCardinality: 'ONE_ONE', targetCardinality: 'ONE_ONE', name: 'x' } },
    { type: 'ADD_RELATIONSHIP', relationship: { sourceEntity: 'A', targetEntity: 'B', sourceCardinality: 'ONE_ONE', targetCardinality: 'ZERO_MANY', name: 'y' } }])
  assert.throws(() => apply(ambiguous, [{ type: 'DELETE_RELATIONSHIP', relationship: { sourceEntity: 'A', targetEntity: 'B' } }]),
    /más de una relación/)
})

test('association creation and deletion reuse the structural contract', () => {
  let result = apply(empty(), [create('Producto'), create('Venta'), {
    type: 'CREATE_ASSOCIATION', association: { sourceEntity: 'Producto', targetEntity: 'Venta',
      associationEntityName: 'DetalleVenta', attributes: [attr('cantidad', 'Integer', false, false)] },
  }])
  const association = result.nodes.find(node => node.data.name === 'DetalleVenta')
  assert.ok(association?.data.association)
  assert.equal(association.data.attributes.some(attribute => attribute.name === 'cantidad'), true)
  assert.equal(result.edges.length, 2)
  assert.doesNotThrow(() => validateAssociationDocument(result.nodes, result.edges))
  result = apply(result, [{ type: 'DELETE_ASSOCIATION', associationEntityName: 'DetalleVenta' }])
  assert.deepEqual(result.nodes.map(node => node.data.name), ['Producto', 'Venta'])
  assert.equal(result.edges.length, 0)
})

test('a failing later action rolls the complete plan back', () => {
  const original = apply(empty(), [create('Producto')])
  const snapshot = structuredClone(original)
  assert.throws(() => apply(original, [
    { type: 'ADD_ATTRIBUTE', entityName: 'Producto', attribute: attr('stock', 'Integer') },
    { type: 'DELETE_ENTITY', entityName: 'Inexistente' },
  ]))
  assert.deepEqual(original, snapshot)
})

test('structured plan rejects unknown, incomplete, invalid type and invalid cardinality', () => {
  const invalid = [
    { operations: [{ type: 'UNKNOWN' }] },
    { operations: [{ type: 'DELETE_ATTRIBUTE', entityName: 'A' }] },
    { operations: [{ type: 'CHANGE_ATTRIBUTE_TYPE', entityName: 'A', attributeName: 'x', dataType: 'SQL' }] },
    { operations: [{ type: 'UPDATE_RELATIONSHIP', relationship: { sourceEntity: 'A', targetEntity: 'B',
      sourceCardinality: 'OTHER', targetCardinality: 'ONE_ONE' } }] },
  ]
  for (const plan of invalid) assert.throws(() => parseDiagramAiResponse(plan))
})
