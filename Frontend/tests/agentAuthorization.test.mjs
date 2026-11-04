import assert from 'node:assert/strict'
import test from 'node:test'
import { readFileSync } from 'node:fs'
import { classifyAgentIntent } from '../src/features/agent/services/agentIntent.ts'
import { prepareAgentResponse, proposalAcceptanceError } from '../src/features/agent/services/agentProposal.ts'
import { applyDiagramOperations } from '../src/features/diagram/services/applyDiagramOperations.ts'
import { diagramOperationLabel } from '../src/features/diagram/services/diagramActionPresentation.ts'

test('instrucciones exactas son modificaciones y respuestas vacías no afirman éxito', () => {
  const relation = '@LogicDraft agrega una relacion entre la clase Producto y categoria donde una categoria tiene artos productos y un producto si o si en una categoria'
  const compound = '@LogicDraft elimina el atributo "Precio de venta" de la clase Producto y crea un nuevo atributo en la clase compra con el nombre de "CANTIDAD" tipo int'
  for (const message of [relation, compound, relation + ' ¿correcto?']) {
    assert.equal(classifyAgentIntent(message), 'MODIFICATION')
    const result = prepareAgentResponse(message, { answer: 'Ya lo hice. Usa el editor.', operations: [] }, [], [])
    assert.equal(result.proposal, null)
    assert.equal(result.response.outcome, 'NEEDS_CLARIFICATION')
    assert.doesNotMatch(result.response.answer, /Ya lo hice/)
  }
  const reason = 'No existe Categoria. Indica un clasificador existente.'
  assert.equal(prepareAgentResponse(relation, { answer: reason, operations: [],
    outcome: 'NEEDS_CLARIFICATION' }, [], []).response.answer, reason)
})

test('las propuestas muestran cardinalidades en el extremo correcto', () => {
  const label = diagramOperationLabel({ type: 'ADD_RELATIONSHIP', relationship: {
    sourceEntity: 'Producto', targetEntity: 'Categoria', sourceCardinality: 'ZERO_MANY', targetCardinality: 'ONE_ONE',
  } })
  assert.match(label, /Producto - Categoria/)
  assert.match(label, /origen: 0\.\.N; destino: 1\.\.1/)
})

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

test('el flujo React revalida permisos y deja el guardado y difusión al backend', () => {
  const editor = readFileSync(new URL('../src/features/diagram/pages/DiagramEditorPage.tsx', import.meta.url), 'utf8')
  const handlerStart = editor.indexOf('async function handleAgentAsk')
  const acceptStart = editor.indexOf('async function acceptAiProposal')
  const permissionCheck = editor.indexOf('await authorizeProjectAgentEdit', acceptStart)
  const commit = editor.indexOf('commitDiagramOperations(proposal.operations)', permissionCheck)
  assert.ok(handlerStart >= 0)
  assert.match(editor.slice(handlerStart), /prepareAgentResponse\(\s*message,\s*response/)
  assert.ok(permissionCheck > acceptStart)
  assert.ok(commit > permissionCheck)
  assert.doesNotMatch(editor, /publishEvent\(/)
  assert.match(editor, /DIAGRAM_COMMITTED/)
  assert.match(editor, /Ollama no está disponible/)
  assert.match(editor, /Ollama tardó demasiado/)
  assert.match(editor, /handleAiPrompt[\s\S]*prepareDiagramAiProposal/,
    'la barra inferior conserva su flujo independiente')
})

test('clasifica ediciones consultas sugerencias y negaciones de forma segura', () => {
  for (const message of ['Modifica el atributo nombre', 'Renombra Cliente a Comprador',
    'Elimina la clase Producto', 'Borra temporal', 'Quita la relacion', 'Suprime Obsoleta']) {
    assert.equal(classifyAgentIntent(message), 'MODIFICATION', message)
  }
  for (const message of ['Como elimino una clase?', 'Que pasaria si elimino Producto?',
    'Sugiere mejoras al diagrama', 'Analiza el diagrama']) {
    assert.equal(classifyAgentIntent(message), 'INFORMATIONAL', message)
  }
  for (const message of ['No elimines Cliente', 'Nunca borres Producto']) {
    assert.equal(classifyAgentIntent(message), 'AMBIGUOUS', message)
  }
})
