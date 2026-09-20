import test from 'node:test'
import assert from 'node:assert/strict'
import { applyDiagramOperations, AI_TYPE_MAP, parseDiagramAiResponse } from '../src/features/diagram/services/applyDiagramOperations.ts'

const attr = (name = 'id', dataType = 'Long') => ({ name, dataType, primaryKey: name === 'id', nullable: name !== 'id' })
const entity = (name = 'Cliente', attributes = [attr()]) => ({ type: 'ADD_ENTITY', entity: { name, attributes } })
const attribute = (entityName = 'Cliente', a = attr('telefono', 'String')) => ({ type: 'ADD_ATTRIBUTE', entityName, attribute: a })
const relationship = (targetEntity = 'Pedido') => ({ type: 'ADD_RELATIONSHIP', relationship: { sourceEntity: 'Cliente', targetEntity, sourceCardinality: 'ONE_ONE', targetCardinality: 'ZERO_MANY' } })
let nextId = 0
const apply = (nodes, edges, operations) => applyDiagramOperations(nodes, edges, operations, () => String(++nextId))
const initial = () => apply([], [], [entity(), entity('Pedido')])

test('ADD_ENTITY creates real entity node and preserves attributes', () => {
  const r = apply([], [], [entity('Cliente', [attr(), attr('correo', 'String')])])
  assert.equal(r.nodes[0].type, 'entity')
  assert.equal(r.nodes[0].data.id, r.nodes[0].id)
  assert.equal(r.nodes[0].data.attributes[0].primaryKey, true)
  assert.equal(r.nodes[0].data.attributes[0].nullable, false)
  assert.equal(r.nodes[0].data.attributes[1].type, 'VARCHAR')
  assert.equal(r.events[0].type, 'NODE_CREATED')
})
test('duplicate entity name is case insensitive and rejected', () => {
  assert.throws(() => apply(initial().nodes, [], [entity('CLIENTE')]))
})
test('ADD_ATTRIBUTE uses existing node and SQL type', () => {
  const r = apply(initial().nodes, [], [attribute('cLiEnTe')])
  assert.equal(r.nodes[0].data.attributes.at(-1).type, 'VARCHAR')
  assert.equal(r.events[0].type, 'NODE_UPDATED')
})
test('two attributes for different entities are both applied', () => {
  const original = initial()
  const r = apply(original.nodes, [], [
    attribute('Cliente', attr('codigo', 'String')),
    attribute('Pedido', attr('codigo', 'String')),
  ])
  assert.deepEqual(r.nodes.map(node => node.data.attributes.at(-1).name), ['codigo', 'codigo'])
  assert.equal(r.events.length, 2)
})
test('ADD_ATTRIBUTE missing entity fails', () => assert.throws(() => apply([], [], [attribute()])))
test('identical attributes do not duplicate', () => {
  const r = apply(initial().nodes, [], [attribute(), attribute('CLIENTE', attr('TELEFONO', 'String'))])
  assert.equal(r.nodes[0].data.attributes.length, 2)
  assert.equal(r.events.length, 1)
})
test('conflicting duplicate attribute fails', () => {
  assert.throws(() => apply(initial().nodes, [], [attribute('Cliente', attr('id', 'String'))]))
})
test('relationship resolves IDs and stores cardinality', () => {
  const original = initial()
  const r = apply(original.nodes, [], [relationship()])
  assert.equal(r.edges[0].source, original.nodes[0].id)
  assert.equal(r.edges[0].target, original.nodes[1].id)
  assert.equal(r.edges[0].data.sourceCardinality, 'ONE_ONE')
  assert.equal(r.edges[0].data.targetCardinality, 'ZERO_MANY')
  assert.equal(r.events[0].type, 'EDGE_CREATED')
})
test('AI creates a named N:M relation with one derived join table definition', () => {
  const base = apply([], [], [entity('Alumno'), entity('Materia')])
  const operation = { type: 'ADD_RELATIONSHIP', relationship: {
    sourceEntity: 'Alumno', targetEntity: 'Materia', sourceCardinality: 'ZERO_MANY',
    targetCardinality: 'ONE_MANY', name: 'materias', joinTableName: 'alumno_materia',
  } }
  const result = apply(base.nodes, [], [operation])
  assert.equal(result.edges.length, 1)
  assert.deepEqual(result.edges[0].data, {
    sourceCardinality: 'ZERO_MANY', targetCardinality: 'ONE_MANY',
    name: 'materias', joinTableName: 'alumno_materia',
  })
})
test('AI models an associative entity explicitly instead of inventing join attributes', () => {
  const operations = [
    entity('Alumno'), entity('Materia'), entity('Inscripcion', [attr(), attr('fecha', 'Date'), attr('nota', 'Double')]),
    { type: 'ADD_RELATIONSHIP', relationship: { sourceEntity: 'Alumno', targetEntity: 'Inscripcion', sourceCardinality: 'ONE_ONE', targetCardinality: 'ZERO_MANY' } },
    { type: 'ADD_RELATIONSHIP', relationship: { sourceEntity: 'Materia', targetEntity: 'Inscripcion', sourceCardinality: 'ONE_ONE', targetCardinality: 'ZERO_MANY' } },
  ]
  const result = apply([], [], operations)
  assert.equal(result.nodes.length, 3)
  assert.equal(result.edges.length, 2)
  assert.deepEqual(result.nodes[2].data.attributes.map(item => item.name), ['id', 'fecha', 'nota'])
})
test('relationship with missing target fails', () => assert.throws(() => apply(initial().nodes, [], [relationship('Persona')])))
test('batch runs in dependency order', () => {
  const r = apply([], [], [entity(), entity('Pedido'), attribute(), relationship()])
  assert.equal(r.nodes.length, 2)
  assert.equal(r.edges.length, 1)
  assert.deepEqual(r.events.map(e => e.type), ['NODE_CREATED', 'NODE_CREATED', 'NODE_UPDATED', 'EDGE_CREATED'])
})
test('two entities and their N:M relationship are applied in one batch', () => {
  const manyToMany = { type: 'ADD_RELATIONSHIP', relationship: {
    sourceEntity: 'Alumno', targetEntity: 'Materia',
    sourceCardinality: 'ZERO_MANY', targetCardinality: 'ZERO_MANY',
  } }
  const r = apply([], [], [entity('Alumno'), entity('Materia'), manyToMany])
  assert.equal(r.nodes.length, 2)
  assert.equal(r.edges.length, 1)
  assert.deepEqual(r.events.map(event => event.type), ['NODE_CREATED', 'NODE_CREATED', 'EDGE_CREATED'])
})
test('failure after a valid operation leaves original graph unchanged', () => {
  const original = initial()
  const before = structuredClone(original)
  assert.throws(() => apply(original.nodes, original.edges, [attribute(), relationship('Persona')]))
  assert.deepEqual(original, before)
})
test('all AI types map to supported SQL types', () => {
  assert.deepEqual(AI_TYPE_MAP, { String: 'VARCHAR', Long: 'BIGINT', Integer: 'INTEGER', Double: 'DECIMAL', Boolean: 'BOOLEAN', Date: 'DATE', DateTime: 'TIMESTAMP' })
  const r = apply([], [], [entity('Tipos', Object.keys(AI_TYPE_MAP).map(t => attr(t, t)))])
  assert.deepEqual(r.nodes[0].data.attributes.map(a => a.type), Object.values(AI_TYPE_MAP))
})
test('repeated edges are no-ops', () => {
  const r = apply(initial().nodes, [], [relationship(), relationship()])
  assert.equal(r.edges.length, 1)
})
test('new nodes receive different free positions', () => {
  const r = initial()
  assert.ok(r.nodes[1].position.y > r.nodes[0].position.y + 100)
})
test('empty operations produce no events', () => assert.equal(apply([], [], []).events.length, 0))
test('unknown operation and undefined fields rejected', () => {
  for (const operations of [[{ type: 'DELETE_ENTITY' }], [{ type: 'ADD_ENTITY' }], [attribute('Cliente', { name: 'x', dataType: 'sql' })]]) {
    assert.throws(() => parseDiagramAiResponse({ operations }))
  }
})
test('existing node nested data not mutated on success', () => {
  const original = initial()
  const before = structuredClone(original.nodes)
  apply(original.nodes, [], [attribute()])
  assert.deepEqual(original.nodes, before)
})
