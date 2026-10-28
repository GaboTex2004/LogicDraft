import assert from 'node:assert/strict'
import test from 'node:test'
import { readFileSync } from 'node:fs'

const editor = readFileSync(new URL('../src/features/diagram/pages/DiagramEditorPage.tsx', import.meta.url), 'utf8')
const api = readFileSync(new URL('../src/features/diagram/api/diagramApi.ts', import.meta.url), 'utf8')
const collaboration = readFileSync(new URL('../src/features/collaboration/hooks/useProjectCollaboration.ts', import.meta.url), 'utf8')

test('save sends the confirmed server revision separately from document version', () => {
  assert.match(api, /baseRevision:\s*number \| null/)
  assert.match(api, /\{ \.\.\.document, baseRevision,/)
  assert.match(editor, /serverRevisionRef\.current = saved\.revision/)
})

test('409 preserves local canvas and requires explicit resynchronization', () => {
  const conflict = editor.slice(editor.indexOf('requestError.response?.status === 409'))
  assert.match(conflict, /setSaveStatus\("conflict"\)/)
  assert.match(conflict, /Tus cambios locales se conservaron/)
  assert.match(editor, /window\.confirm\([\s\S]*window\.location\.reload/)
  assert.doesNotMatch(conflict.slice(0, conflict.indexOf('.finally')), /setNodes\(/)
})

test('repeated websocket event IDs are ignored', () => {
  assert.match(collaboration, /seenEventIds\.current\.has\(event\.eventId\)/)
  assert.match(collaboration, /seenEventIds\.current\.add\(event\.eventId\)/)
})
