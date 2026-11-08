import test from 'node:test'
import assert from 'node:assert/strict'
import { prepareEditorBarResponse } from '../src/features/diagram/services/editorBarAiResponse.ts'

const operation = { type: 'ADD_ENTITY', entity: { name: 'PersonaQA', kind: 'CLASS', attributes: [] } }
test('la barra prepara propuesta sin mutar el lienzo', () => {
  const nodes = [], edges = []
  const result = prepareEditorBarResponse('Crea una clase PersonaQA', { answer: 'Revisa', operations: [operation], intent: 'MUTATION', outcome: 'PROPOSAL' }, nodes, edges)
  assert.equal(result.proposal.operations.length, 1)
  assert.deepEqual(nodes, []); assert.deepEqual(edges, [])
})
test('consultas nunca se convierten en modificaciones aunque el modelo las envíe', () => {
  const result = prepareEditorBarResponse('¿Qué es una clase?', { answer: 'Una clase describe objetos.', operations: [operation] }, [], [])
  assert.equal(result.proposal, null)
  assert.deepEqual(result.response.operations, [])
})
test('aclaraciones y acciones ya satisfechas conservan su explicación', () => {
  for (const outcome of ['NEEDS_CLARIFICATION', 'ALREADY_SATISFIED']) {
    const result = prepareEditorBarResponse('Crea una clase PersonaQA', { answer: 'Explicación concreta', operations: [], outcome }, [], [])
    assert.equal(result.proposal, null); assert.equal(result.response.answer, 'Explicación concreta')
  }
})
test('respuesta inválida y operaciones malformadas no generan propuestas', () => {
  for (const value of [null, {}, { answer: '', operations: [] }, { answer: 'ok', operations: 'mal' }, { answer: 'ok', operations: [{ type: 'INVENTED' }] }]) {
    assert.throws(() => prepareEditorBarResponse('Crea una clase PersonaQA', value, [], []))
  }
})
