import assert from 'node:assert/strict'
import test from 'node:test'
import { readFileSync } from 'node:fs'
import { DiagramSynchronizer } from '../src/features/collaboration/services/diagramSynchronization.ts'
import { validateDiagramDocument } from '../src/features/diagram/services/diagramDocumentValidation.ts'

const document = (name) => ({ version: 2, nodes: [{ id: 'c', type: 'entity', position: { x: 0, y: 0 },
  data: { id: 'c', kind: 'CLASS', name, attributes: [], methods: [] } }], edges: [] })
const snapshot = (revision, name = 'Confirmada') => ({ id: 30, proyectoId: 10, version: 2,
  revision, contenido: document(name), fechaActualizacion: '2026-10-28T00:00:00' })
function deferred() {
  let resolve
  const promise = new Promise(r => { resolve = r })
  return { promise, resolve }
}
function browser(read, revision = 1) {
  const state = { revision, dirty: false, dragging: false, saving: null }
  const applied = [], conflicts = [], errors = []
  let canvas = document('Inicial')
  const sync = new DiagramSynchronizer({ read, state: () => state,
    apply: (saved, author) => {
      validateDiagramDocument(saved.contenido)
      canvas = saved.contenido; state.revision = saved.revision; applied.push({ saved, author })
    },
    conflict: saved => conflicts.push(saved), error: error => errors.push(error),
  })
  return { state, sync, applied, conflicts, errors, canvas: () => canvas }
}

test('two clients receive one atomic confirmed document; duplicates and old responses do not roll back', async () => {
  let server = snapshot(2)
  const a = browser(async () => server), b = browser(async () => server)
  await Promise.all([a.sync.refresh(), b.sync.refresh()])
  assert.deepEqual(a.canvas(), b.canvas())
  assert.equal(a.applied.length, 1)
  await Promise.all([a.sync.refresh(), b.sync.refresh()])
  server = snapshot(1, 'Antigua')
  await b.sync.refresh()
  assert.equal(b.applied.length, 1)
  assert.equal(b.canvas().nodes[0].data.name, 'Confirmada')
})

test('simultaneous local editing or dragging preserves the canvas and base revision on conflict', async () => {
  for (const flag of ['dirty', 'dragging']) {
    const client = browser(async () => snapshot(2))
    client.state[flag] = true
    const before = structuredClone(client.canvas())
    await client.sync.refresh()
    assert.equal(client.conflicts.length, 1)
    assert.equal(client.state.revision, 1)
    assert.equal(client.applied.length, 0)
    assert.deepEqual(client.canvas(), before)
  }
})

test('a local edit while GET is in flight is checked after the response, not overwritten', async () => {
  const response = deferred()
  const client = browser(() => response.promise)
  const request = client.sync.refresh()
  await Promise.resolve()
  client.state.dirty = true
  response.resolve(snapshot(2))
  await request
  assert.equal(client.conflicts.length, 1)
  assert.equal(client.applied.length, 0)
})

test('own commit notification waits for PUT and does not apply the same revision twice', async () => {
  const save = deferred()
  let reads = 0
  const client = browser(async () => { reads++; return snapshot(2) })
  client.state.saving = save.promise
  const refresh = client.sync.refresh()
  await Promise.resolve()
  assert.equal(reads, 0)
  client.state.revision = 2
  client.state.saving = null
  save.resolve(true)
  await refresh
  assert.equal(client.applied.length, 0)
  assert.equal(client.conflicts.length, 0)
})

test('save started during a read defers reconciliation until the save completes', async () => {
  const read = deferred(), save = deferred()
  let reads = 0
  const client = browser(() => ++reads === 1 ? read.promise : Promise.resolve(snapshot(3)))
  const refresh = client.sync.refresh()
  await Promise.resolve()
  client.state.saving = save.promise
  read.resolve(snapshot(2))
  await Promise.resolve()
  assert.equal(client.applied.length, 0)
  client.state.revision = 3
  client.state.saving = null
  save.resolve(true)
  await refresh
  assert.equal(reads, 2)
  assert.equal(client.applied.length, 0)
})

test('reconnect catches up across missed revisions; a new notification during GET is not lost', async () => {
  const first = deferred()
  let reads = 0
  const client = browser(() => ++reads === 1 ? first.promise : Promise.resolve(snapshot(9)))
  const running = client.sync.refresh({ name: 'Ana', revision: 8 })
  await Promise.resolve()
  void client.sync.refresh({ name: 'Luis', revision: 9 })
  first.resolve(snapshot(8))
  await running
  assert.equal(client.state.revision, 9)
  assert.equal(client.applied[0].author, undefined)
  assert.equal(client.applied[1].author, 'Luis')
})

test('network failure preserves draft; retry and first saved diagram recover through REST', async () => {
  let offline = true
  const client = browser(async () => { if (offline) throw new Error('offline'); return snapshot(0) }, null)
  await client.sync.refresh()
  assert.equal(client.errors.length, 1)
  assert.equal(client.applied.length, 0)
  offline = false
  await client.sync.refresh()
  assert.equal(client.state.revision, 0)
})

test('a committed UML snapshot keeps methods, attribute modifiers, EA metadata and relationship ends intact', async () => {
  const saved = snapshot(2)
  const node = saved.contenido.nodes[0]
  node.data.generateCrud = true
  node.data.attributes = [{ id: 'id', name: 'id', type: 'BIGINT', primaryKey: true, visibility: 'private', static: false }]
  node.data.methods = [{ id: 'm', name: 'calcular', visibility: 'public', returnType: 'void', static: true, abstract: false,
    parameters: [{ id: 'p', name: 'total', type: 'Integer' }] }]
  node.data.externalMetadata = { source: 'enterprise-architect', externalId: 'EAID_CLASS' }
  saved.contenido.nodes.push({ id: 'other', type: 'entity', position: { x: 200, y: 0 },
    data: { id: 'other', name: 'Otra', kind: 'CLASS', attributes: [], methods: [] } })
  saved.contenido.edges.push({ id: 'rel', source: 'c', target: 'other', sourceHandle: 'right', targetHandle: 'left',
    data: { relationKind: 'AGGREGATION', aggregationEnd: 'TARGET', sourceCardinality: 'ONE_ONE', targetCardinality: 'ZERO_MANY' } })
  const before = structuredClone(saved.contenido)
  const client = browser(async () => saved)
  await client.sync.refresh()
  assert.deepEqual(client.canvas(), before)
  assert.equal(client.errors.length, 0)
})

test('unmount ignores in-flight responses and invalid revision cannot alter the graph', async () => {
  const read = deferred()
  const client = browser(() => read.promise)
  const running = client.sync.refresh()
  await Promise.resolve()
  client.sync.dispose()
  read.resolve(snapshot(2))
  await running
  assert.equal(client.applied.length, 0)
  const invalid = browser(async () => snapshot(-1))
  await invalid.sync.refresh()
  assert.equal(invalid.errors.length, 1)
  assert.equal(invalid.applied.length, 0)
})

test('editor wires reconnect, missed-event recovery and conflict protection; no draft STOMP publication', () => {
  const page = readFileSync(new URL('../src/features/diagram/pages/DiagramEditorPage.tsx', import.meta.url), 'utf8')
  const transport = readFileSync(new URL('../src/features/collaboration/services/collaborationService.ts', import.meta.url), 'utf8')
  assert.match(page, /project\.id, applyRemoteEvent, synchronize/)
  assert.match(page, /15_000/)
  assert.match(page, /if \(conflictRef\.current\) return/)
  assert.match(page, /Descargar borrador local/)
  assert.doesNotMatch(page, /remoteDocument|publishEvent\(/)
  assert.doesNotMatch(transport, /publishEvent|JSON\.stringify\(\{ type, clientId, payload \}\)/)
})
