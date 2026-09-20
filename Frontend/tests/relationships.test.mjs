import test from 'node:test'
import assert from 'node:assert/strict'
import { cardinalities, CARDINALITY_LABELS, normalizeRelationshipEdge, equivalentRelationship, commitRelationshipEdge, removeRelationshipEdges } from '../src/features/diagram/services/relationshipCardinality.ts'
import { applyDiagramOperations } from '../src/features/diagram/services/applyDiagramOperations.ts'
import { deriveJoinTable } from '../src/features/diagram/services/derivedJoinTable.ts'

const edge = (data = {}) => ({ id: 'e', source: 'a', target: 'b', data })
const entity = name => ({ type: 'ADD_ENTITY', entity: { name, attributes: [] } })
const relation = (sourceEntity = 'Categoria', targetEntity = 'Producto', sourceCardinality = 'ONE_ONE', targetCardinality = 'ZERO_MANY') => ({
  type: 'ADD_RELATIONSHIP', relationship: { sourceEntity, targetEntity, sourceCardinality, targetCardinality },
})
let id = 0
const apply = (nodes, edges, ops) => applyDiagramOperations(nodes, edges, ops, () => String(++id))

for (const [card, label] of Object.entries({ ZERO_ONE: '0..1', ONE_ONE: '1..1', ZERO_MANY: '0..N', ONE_MANY: '1..N' })) {
  test(`${card} label is ${label}`, () => assert.equal(CARDINALITY_LABELS[card], label))
}
test('manual create defaults to one-one, marks dirty and publishes', () => {
  let state = [], dirty = 0, events = []
  commitRelationshipEdge(state, edge(), 'EDGE_CREATED', false, {
    setEdges: next => { state = next }, markDirty: () => dirty++, publish: (...args) => events.push(args),
  })
  assert.deepEqual(state[0].data, { sourceCardinality: 'ONE_ONE', targetCardinality: 'ONE_ONE' })
  assert.equal(state[0].type, 'relationship')
  assert.equal(dirty, 1)
  assert.equal(events[0][0], 'EDGE_CREATED')
})
test('manual edit marks dirty once, emits EDGE_UPDATED, preserves metadata', () => {
  let state = [normalizeRelationshipEdge(edge({ custom: 42 }))], dirty = 0, events = []
  commitRelationshipEdge(state, { ...state[0], data: { ...state[0].data, targetCardinality: 'ZERO_MANY' } }, 'EDGE_UPDATED', false, {
    setEdges: next => { state = next }, markDirty: () => dirty++, publish: (...args) => events.push(args),
  })
  assert.equal(state[0].data.targetCardinality, 'ZERO_MANY')
  assert.equal(state[0].data.custom, 42)
  assert.equal(dirty, 1)
  assert.equal(events[0][0], 'EDGE_UPDATED')
})
test('manual create and edit preserve N:M cardinalities and custom names', () => {
  let state = [], events = []
  const many = edge({ sourceCardinality: 'ZERO_MANY', targetCardinality: 'ONE_MANY', name: 'materias', joinTableName: 'alumno_materia' })
  assert.equal(commitRelationshipEdge(state, many, 'EDGE_CREATED', false, {
    setEdges: next => { state = next }, markDirty: () => {}, publish: (...args) => events.push(args),
  }), true)
  assert.deepEqual(state[0].data, {
    sourceCardinality: 'ZERO_MANY', targetCardinality: 'ONE_MANY', name: 'materias', joinTableName: 'alumno_materia',
  })
  assert.equal(commitRelationshipEdge(state, { ...state[0], data: { ...state[0].data, name: 'materiasOptativas' } }, 'EDGE_UPDATED', false, {
    setEdges: next => { state = next }, markDirty: () => {}, publish: (...args) => events.push(args),
  }), true)
  assert.equal(state[0].data.name, 'materiasOptativas')
  assert.deepEqual(events.map(([kind]) => kind), ['EDGE_CREATED', 'EDGE_UPDATED'])
  const restored = normalizeRelationshipEdge(JSON.parse(JSON.stringify(state[0])))
  assert.deepEqual(restored.data, state[0].data)
  assert.equal(restored.id, state[0].id)
})
test('deleting a relationship removes only the selected edge', () => {
  const current = [normalizeRelationshipEdge(edge()), normalizeRelationshipEdge({ ...edge(), id: 'other' })]
  const result = removeRelationshipEdges(current, new Set(['e']))
  assert.deepEqual(result.map(item => item.id), ['other'])
  assert.equal(current.length, 2)
})
test('remote update changes cardinality and preserves selection without reemitting', () => {
  let state = [{ ...normalizeRelationshipEdge(edge()), selected: true }], dirty = 0
  commitRelationshipEdge(state, edge({ sourceCardinality: 'ZERO_ONE', targetCardinality: 'ONE_MANY' }), 'EDGE_UPDATED', true, {
    setEdges: next => { state = next }, markDirty: () => dirty++, publish: () => assert.fail('remote echo'),
  })
  assert.equal(state[0].selected, true)
  assert.equal(state[0].data.sourceCardinality, 'ZERO_ONE')
  assert.equal(dirty, 1)
})
test('no-op edit does not mark dirty or publish', () => {
  const original = normalizeRelationshipEdge(edge())
  assert.equal(commitRelationshipEdge([original], original, 'EDGE_UPDATED', false, {
    setEdges: () => assert.fail(), markDirty: () => assert.fail(), publish: () => assert.fail(),
  }), false)
})
test('legacy cardinalities normalize and JSON roundtrip retains new endpoints', () => {
  for (const [legacy, expected] of Object.entries({ ONE_TO_ONE: ['ONE_ONE', 'ONE_ONE'], ONE_TO_MANY: ['ONE_ONE', 'ZERO_MANY'], MANY_TO_ONE: ['ZERO_MANY', 'ONE_ONE'], MANY_TO_MANY: ['ZERO_MANY', 'ZERO_MANY'] })) {
    const original = edge({ relationshipType: legacy, custom: 'kept' })
    const normalized = normalizeRelationshipEdge(original)
    assert.deepEqual([normalized.data.sourceCardinality, normalized.data.targetCardinality], expected)
    assert.equal(normalized.data.relationshipType, undefined)
    assert.equal(original.data.relationshipType, legacy)
    assert.deepEqual(normalizeRelationshipEdge(JSON.parse(JSON.stringify(normalized))), normalized)
  }
})
test('invalid or incomplete explicit cardinality never defaults silently', () => {
  for (const data of [{ sourceCardinality: 'OTHER', targetCardinality: 'ONE_ONE' }, { sourceCardinality: 'ONE_ONE' }, { relationshipType: 'OTHER' }]) assert.throws(() => cardinalities(data))
})
test('AI batch creates entities then relates them transactionally', () => {
  const r = apply([], [], [entity('Categoria'), entity('Producto'), relation('CATEGORIA', 'producto')])
  assert.equal(r.edges.length, 1)
  assert.equal(r.edges[0].data.targetCardinality, 'ZERO_MANY')
  assert.deepEqual(r.events.map(e => e.type), ['NODE_CREATED', 'NODE_CREATED', 'EDGE_CREATED'])
})
test('equivalent reversed relationship is not duplicated; minima remain distinct', () => {
  const r = apply([], [], [entity('Categoria'), entity('Producto'), relation(), relation('Producto', 'Categoria', 'ZERO_MANY', 'ONE_ONE'), relation('Categoria', 'Producto', 'ONE_ONE', 'ONE_MANY')])
  assert.equal(r.edges.length, 2)
  assert.equal(equivalentRelationship(r.edges[0], { ...r.edges[0], source: r.edges[0].target, target: r.edges[0].source, data: { sourceCardinality: 'ZERO_MANY', targetCardinality: 'ONE_ONE' } }), true)
})
test('two named N:M relationships between the same entities remain distinct', () => {
  const base = apply([], [], [entity('Alumno'), entity('Materia')])
  const first = { ...relation('Alumno', 'Materia', 'ZERO_MANY', 'ZERO_MANY'), relationship: {
    ...relation('Alumno', 'Materia', 'ZERO_MANY', 'ZERO_MANY').relationship, name: 'materiasObligatorias',
  } }
  const second = { ...relation('Alumno', 'Materia', 'ZERO_MANY', 'ZERO_MANY'), relationship: {
    ...relation('Alumno', 'Materia', 'ZERO_MANY', 'ZERO_MANY').relationship, name: 'materiasOptativas',
  } }
  const result = apply(base.nodes, [], [first, second])
  assert.equal(result.edges.length, 2)
  assert.deepEqual(result.edges.map(item => item.data.name), ['materiasObligatorias', 'materiasOptativas'])
})
test('legacy edge prevents duplicate AI relationship', () => {
  const r = apply([], [], [entity('Categoria'), entity('Producto')])
  const legacy = { id: 'old', source: r.nodes[0].id, target: r.nodes[1].id, data: { relationshipType: 'ONE_TO_MANY' } }
  assert.equal(apply(r.nodes, [legacy], [relation()]).events.length, 0)
})
test('missing endpoint and self reference reject batch without mutation', () => {
  const r = apply([], [], [entity('Categoria')]), before = structuredClone(r)
  assert.throws(() => apply(r.nodes, r.edges, [entity('Other'), relation()]))
  assert.throws(() => apply(r.nodes, r.edges, [relation('Categoria', 'Categoria')]))
  assert.deepEqual(r, before)
})

const diagramEntity = (id, name, type) => ({
  id, name, attributes: [{ id: `${id}-id`, name: 'id', type, primaryKey: true, nullable: false }],
})

test('N:M visualization derives table, FK types and generated UNIQUE without creating model objects', () => {
  const relationship = edge({ sourceCardinality: 'ZERO_MANY', targetCardinality: 'ONE_MANY' })
  const before = structuredClone(relationship)
  assert.deepEqual(deriveJoinTable(relationship, diagramEntity('a', 'Alumno', 'INTEGER'), diagramEntity('b', 'Materia', 'BIGINT')), {
    name: 'alumno_materia',
    columns: [
      { name: 'alumno_id', type: 'INTEGER', referencedEntity: 'Alumno' },
      { name: 'materia_id', type: 'BIGINT', referencedEntity: 'Materia' },
    ],
    uniqueConstraint: 'uk_alumno_materia_pair',
  })
  assert.deepEqual(relationship, before)
})

test('custom join table survives JSON persistence and drives the derived visualization', () => {
  const saved = JSON.stringify(edge({ sourceCardinality: 'ZERO_MANY', targetCardinality: 'ZERO_MANY', joinTableName: 'matriculas_alumno' }))
  const restored = normalizeRelationshipEdge(JSON.parse(saved))
  const table = deriveJoinTable(restored, diagramEntity('a', 'Alumno', 'INTEGER'), diagramEntity('b', 'Materia', 'INTEGER'))
  assert.equal(table?.name, 'matriculas_alumno')
  assert.equal(table?.uniqueConstraint, 'uk_matriculas_alumno_pair')
})

test('legacy MANY_TO_MANY reconstructs a derived table while non-N:M stays unchanged', () => {
  const source = diagramEntity('a', 'Orden de Compra', 'INTEGER')
  const target = diagramEntity('b', 'Artículo', 'VARCHAR')
  const legacy = edge({ relationshipType: 'MANY_TO_MANY' })
  assert.equal(deriveJoinTable(legacy, source, target)?.name, 'orden_de_compra_articulo')
  assert.equal(deriveJoinTable(edge({ relationshipType: 'ONE_TO_MANY' }), source, target), null)
})

test('AI-created N:M uses the same single relationship as visualization source', () => {
  const created = apply([], [], [entity('Alumno'), entity('Materia'), relation('Alumno', 'Materia', 'ZERO_MANY', 'ZERO_MANY')])
  created.nodes[0].data.attributes.push({ id: 'a-id', name: 'id', type: 'INTEGER', primaryKey: true })
  created.nodes[1].data.attributes.push({ id: 'b-id', name: 'id', type: 'INTEGER', primaryKey: true })
  assert.equal(created.edges.length, 1)
  assert.equal(deriveJoinTable(created.edges[0], created.nodes[0].data, created.nodes[1].data)?.name, 'alumno_materia')
  assert.equal(created.nodes.length, 2)
})
