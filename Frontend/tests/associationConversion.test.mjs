import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import {
  AssociationConversionError, associationProtectedNodeIds, convertManyToManyAssociation,
  structuralRelationshipIds, validateAssociationDocument,
} from '../src/features/diagram/services/associationConversion.ts'
import { associationClassProjection } from '../src/features/diagram/services/associationClassProjection.ts'

const node = (id, name, type = 'INTEGER') => ({
  id, type: 'entity', position: { x: id === 'student' ? 0 : 400, y: 0 },
  data: { id, name, attributes: [{ id: `${id}-id`, name: 'id', type, primaryKey: true, nullable: false }] },
})
const manyToMany = (overrides = {}) => ({ id: 'student-subject', source: 'student', target: 'subject', type: 'relationship',
  data: { sourceCardinality: 'ZERO_MANY', targetCardinality: 'ONE_MANY', joinTableName: 'alumno_materia' }, ...overrides })
const ids = values => { let index = 0; return () => values[index++] }

test('conversion replaces one N:M with one editable association and two structural relationships', () => {
  const result = convertManyToManyAssociation([node('student', 'Alumno'), node('subject', 'Materia', 'BIGINT')],
    [manyToMany()], 'student-subject', 'Inscripcion', ids(['association', 'pk', 'student-edge', 'subject-edge']))
  assert.equal(result.document.nodes.length, 3)
  assert.equal(result.document.edges.length, 2)
  assert.equal(result.document.edges.some(edge => edge.id === 'student-subject'), false)
  assert.equal(result.document.version, 2)
  const association = result.document.nodes.find(item => item.id === result.associationNodeId)
  assert.equal(association.data.name, 'Inscripcion')
  assert.equal(association.data.kind, 'ASSOCIATION_CLASS')
  assert.deepEqual(association.data.attributes, [{ id: 'attribute-pk', name: 'id', type: 'INTEGER', primaryKey: true, nullable: false }])
  assert.equal(association.data.association.tableName, 'alumno_materia')
  assert.deepEqual(association.data.association.endpoints.map(endpoint => endpoint.foreignKeyName), ['alumno_id', 'materia_id'])
  assert.equal(association.data.association.sourceMultiplicity, 'ZERO_MANY')
  assert.equal(association.data.association.targetMultiplicity, 'ONE_MANY')
  assert.deepEqual(result.document.edges.map(edge => [edge.data.sourceCardinality, edge.data.targetCardinality]),
    [['ONE_ONE', 'ONE_MANY'], ['ONE_ONE', 'ZERO_MANY']])
  validateAssociationDocument(result.document.nodes, result.document.edges)
})

test('AssociationClass projects one main association and hides its second structural edge', () => {
  const document = convertManyToManyAssociation([node('student', 'Alumno'), node('subject', 'Materia')],
    [manyToMany()], 'student-subject', 'Inscripcion', ids(['a', 'pk', 'se', 'te'])).document
  const primary = associationClassProjection(document.edges[0], document.nodes, document.edges)
  const secondary = associationClassProjection(document.edges[1], document.nodes, document.edges)
  assert.deepEqual({ source: primary.sourceNodeId, target: primary.targetNodeId,
    sourceMultiplicity: primary.sourceMultiplicity, targetMultiplicity: primary.targetMultiplicity },
  { source: 'student', target: 'subject', sourceMultiplicity: 'ZERO_MANY', targetMultiplicity: 'ONE_MANY' })
  assert.equal(primary.hidden, false)
  assert.equal(secondary.hidden, true)
  assert.equal(document.edges.length, 2)
})

test('an EA AssociationClass keeps structural FK attributes without requiring a synthetic PK', () => {
  const document = convertManyToManyAssociation([node('student', 'Alumno'), node('subject', 'Materia')],
    [manyToMany()], 'student-subject', 'Inscripcion', ids(['a', 'pk', 'se', 'te'])).document
  const association = document.nodes.find(item => item.data.association)
  association.data.association.externalMetadata = { source: 'enterprise-architect', externalId: 'EAID_connector' }
  association.data.attributes = association.data.association.endpoints.map((endpoint, index) => ({
    id: `EAID_fk_${index}`,
    name: endpoint.foreignKeyName,
    type: 'INTEGER',
    primaryKey: false,
    nullable: false,
    externalMetadata: { source: 'enterprise-architect', externalId: `EAID_attribute_${index}` },
  }))
  assert.doesNotThrow(() => validateAssociationDocument(document.nodes, document.edges))
  assert.deepEqual(association.data.attributes.map(attribute => attribute.name), ['alumno_id', 'materia_id'])
})

test('duplicate entity name and missing endpoint reject atomically', () => {
  const nodes = [node('student', 'Alumno'), node('subject', 'Materia')]
  const edges = [manyToMany()]
  const before = structuredClone({ nodes, edges })
  assert.throws(() => convertManyToManyAssociation(nodes, edges, 'student-subject', 'Alumno', ids(['1', '2', '3', '4'])), AssociationConversionError)
  assert.throws(() => convertManyToManyAssociation(nodes, [{ ...manyToMany(), target: 'missing' }], 'student-subject', 'Inscripcion'), /extremos invalidos/)
  assert.deepEqual({ nodes, edges }, before)
})

test('persistence roundtrip keeps metadata, own attributes and protected structural references', () => {
  const converted = convertManyToManyAssociation([node('student', 'Alumno'), node('subject', 'Materia')], [manyToMany()],
    'student-subject', 'Inscripcion', ids(['a', 'pk', 'se', 'te'])).document
  const association = converted.nodes.find(item => item.data.association)
  association.data.attributes.push({ id: 'grade', name: 'nota', type: 'DECIMAL', primaryKey: false, nullable: true })
  const restored = JSON.parse(JSON.stringify(converted))
  validateAssociationDocument(restored.nodes, restored.edges)
  assert.equal(restored.nodes.find(item => item.data.association).data.attributes.at(-1).name, 'nota')
  assert.deepEqual([...structuralRelationshipIds(restored.nodes)].sort(), ['relationship-se', 'relationship-te'])
  assert.deepEqual([...associationProtectedNodeIds(restored.nodes)].sort(), ['entity-a', 'student', 'subject'])
})

test('invalid structural reference and FK collision are rejected', () => {
  const document = convertManyToManyAssociation([node('student', 'Alumno'), node('subject', 'Materia')], [manyToMany()],
    'student-subject', 'Inscripcion', ids(['a', 'pk', 'se', 'te'])).document
  const broken = structuredClone(document)
  broken.nodes.find(item => item.data.association).data.association.endpoints[0].relationshipId = 'missing'
  assert.throws(() => validateAssociationDocument(broken.nodes, broken.edges), /no es valida/)
  const collision = structuredClone(document)
  collision.nodes.find(item => item.data.association).data.attributes.push({ id: 'bad', name: 'alumno id', type: 'VARCHAR', primaryKey: false })
  assert.throws(() => validateAssociationDocument(collision.nodes, collision.edges), /colisiona/)
})

test('legacy 1:1, 1:N and N:M documents remain unchanged', () => {
  const nodes = [node('student', 'Alumno'), node('subject', 'Materia')]
  const relationships = [
    { ...manyToMany(), id: 'one', data: { sourceCardinality: 'ONE_ONE', targetCardinality: 'ONE_ONE' } },
    { ...manyToMany(), id: 'many', data: { sourceCardinality: 'ONE_ONE', targetCardinality: 'ZERO_MANY' } },
    manyToMany(),
  ]
  const before = structuredClone({ nodes, relationships })
  validateAssociationDocument(nodes, relationships)
  assert.deepEqual({ nodes, relationships }, before)
})

test('an imported AssociationClass keeps a warned neutral structural multiplicity editable', () => {
  const document = convertManyToManyAssociation([node('student', 'Alumno'), node('subject', 'Materia')], [manyToMany()],
    'student-subject', 'Inscripcion', ids(['a', 'pk', 'se', 'te'])).document
  document.edges[0].data.targetCardinality = 'ONE_ONE'
  assert.doesNotThrow(() => validateAssociationDocument(document.nodes, document.edges))
  assert.deepEqual([...structuralRelationshipIds(document.nodes)].sort(), ['relationship-se', 'relationship-te'])
})

test('dialog cancellation and collaboration use explicit non-mutating UI paths and one batch event', () => {
  const dialog = readFileSync(new URL('../src/features/diagram/components/AssociationConversionDialog.tsx', import.meta.url), 'utf8')
  const editor = readFileSync(new URL('../src/features/diagram/pages/DiagramEditorPage.tsx', import.meta.url), 'utf8')
  assert.match(dialog, /onCancel/)
  assert.match(dialog, /Cancelar/)
  assert.doesNotMatch(editor, /publishEvent\(/)
  assert.match(editor, /DIAGRAM_COMMITTED/)
  assert.match(editor, /read: \(\) => obtenerDiagrama\(project\.id\)/)
  assert.doesNotMatch(dialog, /setNodes|setEdges/)
})
