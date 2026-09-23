import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { applyDiagramOperations, parseDiagramAiResponse, prepareDiagramAiProposal } from '../src/features/diagram/services/applyDiagramOperations.ts'

const node = (id, name) => ({ id, type: 'entity', position: { x: id === 'student' ? 0 : 400, y: 0 },
  data: { id, name, attributes: [{ id: `${id}-pk`, name: 'id', type: 'INTEGER', primaryKey: true, nullable: false }] } })
const edge = (overrides = {}) => ({ id: 'student-subject', source: 'student', target: 'subject',
  data: { sourceCardinality: 'ZERO_MANY', targetCardinality: 'ZERO_MANY', joinTableName: 'alumno_materia' }, ...overrides })
const conversion = (attributes = []) => ({ type: 'CONVERT_MANY_TO_MANY_ASSOCIATION', conversion: {
  relationshipId: 'student-subject', sourceEntity: 'Alumno', targetEntity: 'Materia',
  associationEntityName: 'Inscripcion', attributes,
} })
const attr = (name, dataType) => ({ name, dataType, primaryKey: false, nullable: true })
const ids = () => { let value = 0; return () => String(++value) }

test('AI conversion reuses manual conversion and emits one atomic collaborative batch', () => {
  const result = applyDiagramOperations([node('student', 'Alumno'), node('subject', 'Materia')], [edge()],
    [conversion([attr('nota', 'Integer'), attr('fechaInscripcion', 'Date')])], ids())
  assert.equal(result.events.length, 1)
  assert.equal(result.events[0].type, 'DIAGRAM_BATCH_APPLIED')
  assert.equal(result.edges.some(item => item.id === 'student-subject'), false)
  assert.equal(result.edges.length, 2)
  const association = result.nodes.find(item => item.data.association)
  assert.equal(association.data.name, 'Inscripcion')
  assert.deepEqual(association.data.attributes.map(item => [item.name, item.type]),
    [['id', 'INTEGER'], ['nota', 'INTEGER'], ['fechaInscripcion', 'DATE']])
})

test('conversion without own attributes only creates generated PK', () => {
  const result = applyDiagramOperations([node('student', 'Alumno'), node('subject', 'Materia')], [edge()],
    [conversion()], ids())
  assert.deepEqual(result.nodes.find(item => item.data.association).data.attributes.map(item => item.name), ['id'])
})

test('real Spring response prepares a proposal for persisted N:M endpoints without PKs', () => {
  const relationshipId = '80a1806d-8275-40c8-aefa-47725d3b6eed'
  const nodes = [
    { id: 'entity-a8d6f2bc-3d6d-49dd-b544-58cf5d0d3eff', type: 'entity', position: { x: -28, y: 139.5 },
      data: { id: 'entity-a8d6f2bc-3d6d-49dd-b544-58cf5d0d3eff', name: 'Alumno', attributes: [] } },
    { id: 'entity-23b3ed62-9391-4c51-b765-d9caac02bac2', type: 'entity', position: { x: 707, y: 124 },
      data: { id: 'entity-23b3ed62-9391-4c51-b765-d9caac02bac2', name: 'Materia', attributes: [] } },
  ]
  const edges = [{ id: relationshipId, type: 'relationship', source: nodes[0].id, target: nodes[1].id,
    data: { sourceCardinality: 'ONE_MANY', targetCardinality: 'ONE_MANY' } }]
  const response = { operations: [{ type: 'CONVERT_MANY_TO_MANY_ASSOCIATION', conversion: {
    relationshipId, sourceEntity: 'Alumno', targetEntity: 'Materia',
    associationEntityName: 'Inscripcion', attributes: [],
  } }] }
  const original = structuredClone({ nodes, edges })

  const proposal = prepareDiagramAiProposal(response, nodes, edges)
  assert.equal(proposal.operations.length, 1)
  assert.equal(proposal.preview.events[0].type, 'DIAGRAM_BATCH_APPLIED')
  assert.deepEqual({ nodes, edges }, original, 'preparing or cancelling the proposal must not mutate the document')

  const accepted = applyDiagramOperations(nodes, edges, proposal.operations, ids())
  assert.equal(accepted.edges.some(item => item.id === relationshipId), false)
  assert.equal(accepted.edges.length, 2)
  assert.equal(accepted.nodes.find(item => item.data.association).data.name, 'Inscripcion')
})

test('invented ID, non N:M, duplicate entity and already converted state reject without partial mutation', () => {
  const nodes = [node('student', 'Alumno'), node('subject', 'Materia')]
  const edges = [edge()]
  const before = structuredClone({ nodes, edges })
  assert.throws(() => applyDiagramOperations(nodes, edges, [{ ...conversion(), conversion: {
    ...conversion().conversion, relationshipId: 'invented',
  } }], ids()), /no existe/)
  assert.throws(() => applyDiagramOperations(nodes, [{ ...edge(), data: {
    sourceCardinality: 'ONE_ONE', targetCardinality: 'ZERO_MANY',
  } }], [conversion()], ids()), /no es N:M/)
  assert.throws(() => applyDiagramOperations([...nodes, node('existing', 'Inscripcion')], edges, [conversion()], ids()), /Ya existe/)
  const converted = applyDiagramOperations(nodes, edges, [conversion()], ids())
  assert.throws(() => applyDiagramOperations(converted.nodes, converted.edges, [conversion()], ids()), /ya fue convertida/)
  assert.deepEqual({ nodes, edges }, before)
})

test('strict parser rejects scalar FK, generated PK and unknown conversion fields', () => {
  assert.throws(() => parseDiagramAiResponse({ operations: [{ ...conversion([{
    name: 'alumnoId', dataType: 'Integer', primaryKey: true, nullable: false,
  }]), conversion: { ...conversion().conversion, attributes: [{
    name: 'alumnoId', dataType: 'Integer', primaryKey: true, nullable: false,
  }] } }] }))
  assert.throws(() => parseDiagramAiResponse({ operations: [{ ...conversion(), conversion: {
    ...conversion().conversion, invented: true,
  } }] }))
})

test('editor requires explicit acceptance and cancellation is non-mutating', () => {
  const editor = readFileSync(new URL('../src/features/diagram/pages/DiagramEditorPage.tsx', import.meta.url), 'utf8')
  const dialog = readFileSync(new URL('../src/features/diagram/components/DiagramAiProposalDialog.tsx', import.meta.url), 'utf8')
  assert.match(editor, /setPendingProposal\(\s*\{\s*operations/)
  assert.match(editor, /prepareDiagramAiProposal/)
  assert.match(editor, /acceptAiProposal/)
  assert.match(editor, /Propuesta cancelada; no se realizaron cambios/)
  assert.match(editor, /publishEvent\(event\.type,\s*\{\s*document:/)
  assert.match(dialog, /Aplicar cambios/)
  assert.match(dialog, /Cancelar/)
  assert.doesNotMatch(dialog, /setNodes|setEdges/)
})
