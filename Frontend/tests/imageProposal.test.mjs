import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import {
  parseImageProposal,
  imageProposalAcceptanceError,
  resolveAiPromptErrorMessage,
} from '../src/features/diagram/services/imageProposal.ts'

const proposal = () => ({ proposalId: '12345678-1234-1234-1234-123456789abc', baseRevision: 4,
  matched: ['Cliente [existing-id]'], additions: ['Atributo Cliente.correo'], conflicts: [], warnings: [], canApply: true })

test('image proposal parser preserves base revision and never modifies a canvas', () => {
  const raw = proposal()
  const before = structuredClone(raw)
  assert.deepEqual(parseImageProposal(raw), before)
  assert.deepEqual(raw, before)
})
test('general-agent operations cannot enter the image proposal contract', () => {
  for (const type of ['DELETE_ENTITY', 'UPDATE_ATTRIBUTE', 'ADD_ENTITY']) {
    assert.throws(() => parseImageProposal({ operations: [{ type }] }))
    assert.throws(() => parseImageProposal({ ...proposal(), operations: [{ type }] }))
  }
})
test('conflicts disable confirmation even if the remote flag is malicious', () => {
  assert.throws(() => parseImageProposal({ ...proposal(), conflicts: ['Nombre ambiguo'] }))
  const value = { ...proposal(), conflicts: ['Nombre ambiguo'], canApply: false }
  assert.ok(imageProposalAcceptanceError(parseImageProposal(value), 4, 2, 2, 2, false))
})
test('repeated image with no additions has no executable proposal', () => {
  const value = parseImageProposal({ ...proposal(), additions: [], canApply: false })
  assert.ok(imageProposalAcceptanceError(value, 4, 2, 2, 2, false))
})
test('stale server or local revisions block image acceptance', () => {
  assert.ok(imageProposalAcceptanceError(proposal(), 5, 2, 2, 2, false))
  assert.ok(imageProposalAcceptanceError(proposal(), 4, 3, 2, 3, false))
  assert.ok(imageProposalAcceptanceError(proposal(), 4, 3, 3, 2, false))
})
test('permission, saving or conflict block acceptance', () => {
  assert.ok(imageProposalAcceptanceError(proposal(), 4, 2, 2, 2, true))
  assert.equal(imageProposalAcceptanceError(proposal(), 4, 2, 2, 2, false), null)
})
test('null base revision is valid only for a diagram not yet persisted', () => {
  const value = parseImageProposal({ ...proposal(), baseRevision: null })
  assert.equal(imageProposalAcceptanceError(value, null, 0, 0, 0, false), null)
  assert.ok(imageProposalAcceptanceError(value, 0, 0, 0, 0, false))
})
test('editor keeps image proposals separate and only synchronizes after server acceptance', () => {
  const source = readFileSync(new URL('../src/features/diagram/pages/DiagramEditorPage.tsx', import.meta.url), 'utf8')
  const accept = source.slice(source.indexOf('async function acceptImageProposal()'), source.indexOf('const handleNodesChange'))
  assert.match(accept, /await acceptDiagramImage/)
  assert.doesNotMatch(accept, /commitDiagramOperations|applyDiagramOperations|setNodes\(|setEdges\(/)
  assert.match(source, /onCancel=\{\(\) => \{ setPendingImageProposal\(null\); setAiError\(""\); \}\}/)
})

test('resolveAiPromptErrorMessage classifies 413, 415, 422, 429, 502, 503, 504 and preserves backend mensaje', () => {
  assert.equal(
    resolveAiPromptErrorMessage({ isAxiosError: true, response: { status: 413, data: {} } }, true),
    'La imagen supera el límite de 5 MB.',
  )
  assert.equal(
    resolveAiPromptErrorMessage({ isAxiosError: true, response: { status: 415, data: {} } }, true),
    'Formato de imagen no permitido. Utiliza PNG, JPG o WEBP.',
  )
  assert.equal(
    resolveAiPromptErrorMessage(
      { isAxiosError: true, response: { status: 502, data: { mensaje: 'Gemini devolvió una respuesta que no cumple el contrato image-v1.' } } },
      true,
    ),
    'Gemini devolvió una respuesta que no cumple el contrato image-v1.',
  )
  assert.equal(
    resolveAiPromptErrorMessage(
      { isAxiosError: true, response: { status: 503, data: { mensaje: 'Gemini alcanzó su límite de solicitudes o cuota. Inténtalo más tarde.' } } },
      true,
    ),
    'Gemini alcanzó su límite de solicitudes o cuota. Inténtalo más tarde.',
  )
  assert.equal(
    resolveAiPromptErrorMessage({ isAxiosError: true, response: { status: 504, data: {} } }, true),
    'La interpretación de la imagen excedió el tiempo de espera.',
  )
  assert.equal(
    resolveAiPromptErrorMessage({ isAxiosError: true, code: 'ECONNABORTED' }, true),
    'La interpretación de la imagen excedió el tiempo de espera.',
  )
  assert.equal(
    resolveAiPromptErrorMessage(new Error('Contrato de propuesta de imagen invalido.'), true),
    'Contrato de propuesta de imagen invalido.',
  )
})

test('editor always exits loading state in handleAiPrompt finally block', () => {
  const source = readFileSync(new URL('../src/features/diagram/pages/DiagramEditorPage.tsx', import.meta.url), 'utf8')
  const promptFn = source.slice(
    source.indexOf('async function handleAiPrompt('),
    source.indexOf('async function acceptImageProposal()'),
  )
  assert.match(promptFn, /finally\s*\{\s*aiRequest\.current\s*=\s*null;\s*setAiLoading\(false\);\s*\}/)
})
