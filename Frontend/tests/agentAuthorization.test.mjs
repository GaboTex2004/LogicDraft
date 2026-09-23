import assert from 'node:assert/strict'
import test from 'node:test'
import { readFileSync } from 'node:fs'
import { classifyAgentIntent } from '../src/features/agent/services/agentIntent.ts'
import { prepareAgentResponse, proposalAcceptanceError } from '../src/features/agent/services/agentProposal.ts'
import { applyDiagramOperations } from '../src/features/diagram/services/applyDiagramOperations.ts'

const professor = {
  type: 'ADD_ENTITY',
  entity: { name: 'Profesor', attributes: [] },
}
const modelResponse = {
  answer: 'ADD_ENTITY y ADD_RELATIONSHIP',
  operations: [professor],
}

test('preguntas y sugerencias ambiguas nunca autorizan operaciones inventadas', () => {
  for (const message of [
    '¿Qué podría agregar a mi diagrama?',
    '¿Cuáles entidades y relaciones?',
    'Sí, pero ¿cuáles podría agregar?',
    '¿Qué tal si hacemos una entidad Docente?',
    'Podríamos agregar algo de horarios',
    'Profesor',
  ]) {
    const originalNodes = []
    const prepared = prepareAgentResponse(message, modelResponse, originalNodes, [])
    assert.equal(prepared.proposal, null, message)
    assert.deepEqual(prepared.response.operations, [], message)
    assert.equal(prepared.response.answer.includes('ADD_ENTITY'), false, message)
    assert.deepEqual(originalNodes, [], message)
  }
})

test('solo una instrucción explícita produce propuesta y no muta antes de aceptar', () => {
  assert.equal(classifyAgentIntent('Agrega una entidad Profesor'), 'MODIFICATION')
  const originalNodes = []
  const prepared = prepareAgentResponse('Agrega una entidad Profesor', {
    answer: 'Preparé una propuesta.', operations: [professor],
  }, originalNodes, [])
  assert.ok(prepared.proposal)
  assert.deepEqual(originalNodes, [])
  assert.equal(prepared.proposal.preview.nodes[0].data.name, 'Profesor')

  // Cancel means not committing the preview; acceptance applies exactly once.
  assert.deepEqual(originalNodes, [])
  const accepted = applyDiagramOperations(originalNodes, [], prepared.proposal.operations, () => 'profesor')
  assert.equal(accepted.nodes.length, 1)
  assert.equal(accepted.events.length, 1)
  assert.throws(() => applyDiagramOperations(accepted.nodes, accepted.edges, prepared.proposal.operations))
})

test('viewer and stale revision are rejected before applying a pending proposal', () => {
  assert.match(proposalAcceptanceError(2, 2, false), /permisos/)
  assert.match(proposalAcceptanceError(1, 2, true), /cambio/)
  assert.equal(proposalAcceptanceError(2, 2, true), null)
})

test('consultas informativas se distinguen de instrucciones explícitas', () => {
  assert.equal(classifyAgentIntent('Explícame cómo se relacionan Alumno y Materia'), 'INFORMATIONAL')
  assert.equal(classifyAgentIntent('Crea una relación entre Profesor y Materia'), 'MODIFICATION')
  assert.equal(classifyAgentIntent('Convierte la relación N:M entre Alumno y Materia en Inscripcion'), 'MODIFICATION')
})

test('el flujo React revalida permisos y solo publica después de aceptar', () => {
  const editor = readFileSync(new URL('../src/features/diagram/pages/DiagramEditorPage.tsx', import.meta.url), 'utf8')
  const handlerStart = editor.indexOf('async function handleAgentAsk')
  const acceptStart = editor.indexOf('async function acceptAiProposal')
  const permissionCheck = editor.indexOf('await authorizeProjectAgentEdit', acceptStart)
  const commit = editor.indexOf('commitDiagramOperations(proposal.operations)', permissionCheck)
  const publish = editor.indexOf('publishEvent(event.type', editor.indexOf('function commitDiagramOperations'))
  assert.ok(handlerStart >= 0)
  assert.match(editor.slice(handlerStart), /prepareAgentResponse\(\s*message,\s*response/)
  assert.ok(permissionCheck > acceptStart)
  assert.ok(commit > permissionCheck)
  assert.ok(publish >= 0)
  assert.match(editor, /Ollama no está disponible/)
  assert.match(editor, /Ollama tardó demasiado/)
  assert.match(editor, /handleAiPrompt[\s\S]*prepareDiagramAiProposal/,
    'la barra inferior conserva su flujo independiente')
})
