import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { cloneAssociationMetadata, parseAssociationMetadata } from '../src/features/diagram/services/associationMetadata.ts'

const valid = {
  kind: 'MANY_TO_MANY_ASSOCIATION',
  tableName: 'alumno_materia',
  uniquePair: true,
  endpoints: [
    { role: 'SOURCE', entityId: 'alumno', relationshipId: 'alumno-inscripcion', foreignKeyName: 'alumno_id' },
    { role: 'TARGET', entityId: 'materia', relationshipId: 'materia-inscripcion', foreignKeyName: 'materia_id' },
  ],
}

test('association metadata survives a complete JSON roundtrip', () => {
  assert.deepEqual(parseAssociationMetadata(JSON.parse(JSON.stringify(valid))), valid)
  const copy = cloneAssociationMetadata(valid)
  copy.endpoints[0].entityId = 'changed'
  assert.equal(valid.endpoints[0].entityId, 'alumno')
})

test('association metadata rejects malformed shape and keeps absence optional', () => {
  for (const invalid of [
    undefined,
    { ...valid, kind: 'OTHER' },
    { ...valid, uniquePair: false },
    { ...valid, tableName: 'Alumno Materia' },
    { ...valid, endpoints: valid.endpoints.slice(0, 1) },
    { ...valid, endpoints: valid.endpoints.map(item => ({ ...item, role: 'SOURCE' })) },
    { ...valid, endpoints: [valid.endpoints[0], { ...valid.endpoints[1], foreignKeyName: 'Materia ID' }] },
  ]) assert.equal(parseAssociationMetadata(invalid), null)
})

test('editor serialization and collaborative reconstruction preserve optional metadata', () => {
  const editor = readFileSync(new URL('../src/features/diagram/pages/DiagramEditorPage.tsx', import.meta.url), 'utf8')
  assert.match(editor, /association: cloneAssociationMetadata\(node\.data\.association\)/)
  assert.match(editor, /parseAssociationMetadata\(data\.association\)/)
  assert.match(editor, /data\.association !== undefined && !association/)
  assert.match(editor, /nodes: content\.nodes\.map\(normalizeStoredNode\)/)
})
