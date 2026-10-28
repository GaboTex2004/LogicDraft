import assert from 'node:assert/strict'
import test from 'node:test'
import { readFileSync } from 'node:fs'
import {
  CURSOR_TTL_MS,
  CURSOR_UPDATE_INTERVAL_MS,
  CursorPublisherThrottle,
  RemoteCursorStore,
  projectFlowToViewport,
} from '../src/features/collaboration/services/remoteCursors.ts'
import { applyLiveDiagramEvent } from '../src/features/collaboration/services/liveDiagramEvents.ts'
import { convertManyToManyAssociation } from '../src/features/diagram/services/associationConversion.ts'

function makeEvent(type, payload, overrides = {}) {
  return {
    eventId: overrides.eventId ?? crypto.randomUUID(),
    type,
    userId: overrides.userId ?? 7,
    name: overrides.name ?? 'Antony',
    projectId: overrides.projectId ?? 10,
    clientId: overrides.clientId ?? 'remote-client-1',
    timestamp: '2026-10-28T10:00:00Z',
    payload,
    message: null,
  }
}

function classNode(id, name, x = 100, y = 120) {
  return {
    id,
    type: 'entity',
    position: { x, y },
    data: {
      id,
      kind: 'CLASS',
      name,
      attributes: [
        { id: `${id}-pk`, name: 'id', type: 'BIGINT', primaryKey: true, visibility: 'private' },
      ],
      methods: [
        { id: `${id}-m1`, name: 'validar', visibility: 'public', returnType: 'boolean', parameters: [] },
      ],
    },
  }
}

test('remote cursors show name and color, ignore own clientId, and differentiate simultaneous sessions of same user', () => {
  const store = new RemoteCursorStore()

  // Own cursor is never rendered as remote
  assert.equal(
    store.applyEvent(
      makeEvent('CURSOR_MOVED', { x: 50, y: 60, sessionId: 'stomp-self' }, { clientId: 'my-tab' }),
      'my-tab',
      1_000,
    ),
    false,
  )
  assert.equal(store.getSnapshot().length, 0)

  // Two simultaneous tabs belonging to Antony (userId = 7) + one tab belonging to José (userId = 8)
  store.applyEvent(
    makeEvent('CURSOR_MOVED', { x: 120, y: 240, sessionId: 'stomp-A1' }, { userId: 7, name: 'Antony', clientId: 'tab-A1' }),
    'my-tab',
    1_000,
  )
  store.applyEvent(
    makeEvent('CURSOR_MOVED', { x: 320, y: 400, sessionId: 'stomp-A2' }, { userId: 7, name: 'Antony', clientId: 'tab-A2' }),
    'my-tab',
    1_010,
  )
  store.applyEvent(
    makeEvent('CURSOR_MOVED', { x: 500, y: 180, sessionId: 'stomp-J1' }, { userId: 8, name: 'José', clientId: 'tab-J1' }),
    'my-tab',
    1_020,
  )

  const cursors = store.getSnapshot()
  assert.equal(cursors.length, 3)
  const antonyCursors = cursors.filter((cursor) => cursor.userId === 7)
  assert.equal(antonyCursors.length, 2)
  assert.notEqual(antonyCursors[0].label, antonyCursors[1].label)
  assert.notEqual(antonyCursors[0].color, antonyCursors[1].color)
  assert.deepEqual(
    antonyCursors.map((cursor) => cursor.label),
    ['Antony (1)', 'Antony (2)'],
  )

  // Closing one of Antony's tabs removes only that session's cursor; the other tab reverts to "Antony"
  store.applyEvent(
    makeEvent('CURSOR_LEFT', { sessionId: 'stomp-A1' }, { userId: 7, name: 'Antony', clientId: 'tab-A1' }),
    'my-tab',
    1_100,
  )
  const afterTabClose = store.getSnapshot()
  assert.equal(afterTabClose.length, 2)
  assert.equal(afterTabClose.find((cursor) => cursor.userId === 7)?.label, 'Antony')

  // Expiration removes stale cursors while keeping fresh ones
  store.applyEvent(
    makeEvent('CURSOR_MOVED', { x: 510, y: 190, sessionId: 'stomp-J1' }, { userId: 8, name: 'José', clientId: 'tab-J1' }),
    'my-tab',
    10_000,
  )
  store.pruneExpired(1_010 + CURSOR_TTL_MS + 1)
  assert.deepEqual(store.getSnapshot().map((cursor) => cursor.name), ['José'])

  // USER_LEFT removes any remaining cursors for that user
  store.applyEvent(makeEvent('USER_LEFT', null, { userId: 8, name: 'José', clientId: null }), 'my-tab')
  assert.equal(store.getSnapshot().length, 0)
})

test('cursor coordinates project accurately across zoom and pan transforms', () => {
  const flowPoint = { x: 300, y: -120 }
  assert.deepEqual(projectFlowToViewport(flowPoint, { x: 0, y: 0, zoom: 1 }), { x: 300, y: -120 })
  assert.deepEqual(projectFlowToViewport(flowPoint, { x: 140, y: 80, zoom: 0.5 }), { x: 290, y: 20 })
  assert.deepEqual(projectFlowToViewport(flowPoint, { x: -200, y: 50, zoom: 1.75 }), { x: 325, y: -160 })
})

test('cursor publisher throttles to ~20 Hz, flushes trailing move, and cancels pending timer on leave', () => {
  let now = 1_000
  const timers = new Map()
  let nextTimerId = 1
  const sentMoves = []
  let sentLeaves = 0

  const throttle = new CursorPublisherThrottle(
    (point) => sentMoves.push({ ...point, at: now }),
    () => { sentLeaves += 1 },
    CURSOR_UPDATE_INTERVAL_MS,
    {
      now: () => now,
      setTimer: (cb, ms) => {
        const id = nextTimerId++
        timers.set(id, { cb, due: now + ms })
        return id
      },
      clearTimer: (id) => timers.delete(id),
    },
  )

  // Simulate 100 pointer events across 200 ms (500 Hz raw hardware mouse)
  for (let i = 0; i < 100; i++) {
    now = 1_000 + i * 2
    for (const [id, timer] of [...timers.entries()]) {
      if (timer.due <= now) {
        timers.delete(id)
        timer.cb()
      }
    }
    throttle.move({ x: i * 3, y: i * 2 })
  }
  now = 1_260
  for (const [id, timer] of [...timers.entries()]) {
    if (timer.due <= now) {
      timers.delete(id)
      timer.cb()
    }
  }

  // Over ~200 ms at 50 ms budget (20 Hz), we expect 4-5 transmissions including trailing exact point
  assert.ok(sentMoves.length >= 4 && sentMoves.length <= 6, `unexpected count ${sentMoves.length}`)
  assert.deepEqual(
    { x: sentMoves.at(-1).x, y: sentMoves.at(-1).y },
    { x: 99 * 3, y: 99 * 2 },
  )

  // Queue a trailing move and immediately leave the canvas: trailing timer must be cancelled
  now += 10
  throttle.move({ x: 900, y: 900 })
  assert.equal(timers.size, 1)
  throttle.leave()
  assert.equal(timers.size, 0)
  assert.equal(sentLeaves, 1)
})

test('live UML events update nodes, drags, attributes, edges and associative classes immediately without invalidating state', () => {
  let nodes = [classNode('prod', 'Producto', 100, 100)]
  let edges = []

  // 1. Live class creation
  const ventas = classNode('ven', 'Ventas', 420, 100)
  let step = applyLiveDiagramEvent(
    makeEvent('NODE_CREATED', { node: ventas }),
    { nodes, edges, dirty: false, conflict: false },
  )
  assert.equal(step.applied, true)
  nodes = step.nodes

  // 2. Live node dragging (during drag)
  step = applyLiveDiagramEvent(
    makeEvent('NODE_MOVED', { nodeId: 'prod', position: { x: 165, y: 145 }, dragging: true }),
    { nodes, edges, dirty: false, conflict: false },
  )
  assert.equal(step.applied, true)
  assert.deepEqual(step.nodes.find((n) => n.id === 'prod').position, { x: 165, y: 145 })
  nodes = step.nodes

  // 3. Live attribute modification
  const updatedProducto = structuredClone(nodes.find((n) => n.id === 'prod'))
  updatedProducto.data.attributes.push({
    id: 'prod-precio',
    name: 'precio',
    type: 'DECIMAL',
    primaryKey: false,
    visibility: 'private',
  })
  step = applyLiveDiagramEvent(
    makeEvent('NODE_UPDATED', { node: updatedProducto }),
    { nodes, edges, dirty: false, conflict: false },
  )
  assert.equal(step.applied, true)
  assert.equal(step.nodes.find((n) => n.id === 'prod').data.attributes.length, 2)
  nodes = step.nodes

  // 4. Invalid provisional frame (duplicate entity name mid-typing) is rejected safely
  const duplicateNameNode = structuredClone(updatedProducto)
  duplicateNameNode.data.name = 'Ventas'
  const rejected = applyLiveDiagramEvent(
    makeEvent('NODE_UPDATED', { node: duplicateNameNode }),
    { nodes, edges, dirty: false, conflict: false },
  )
  assert.equal(rejected.applied, false)
  assert.equal(nodes.find((n) => n.id === 'prod').data.name, 'Producto')

  // 5. Live N:M relationship creation & modification
  const relation = {
    id: 'rel-1',
    source: 'prod',
    target: 'ven',
    type: 'relationship',
    data: {
      sourceCardinality: 'ZERO_MANY',
      targetCardinality: 'ZERO_MANY',
      relationKind: 'ASSOCIATION',
      name: 'incluye',
    },
  }
  step = applyLiveDiagramEvent(
    makeEvent('EDGE_CREATED', { edge: relation }),
    { nodes, edges, dirty: false, conflict: false },
  )
  assert.equal(step.applied, true)
  edges = step.edges
  assert.equal(edges.length, 1)

  // 6. Live associative class conversion batch
  const converted = convertManyToManyAssociation(nodes, edges, 'rel-1', 'DetalleVenta')
  step = applyLiveDiagramEvent(
    makeEvent('DIAGRAM_BATCH_APPLIED', {
      document: { version: 2, nodes: converted.document.nodes, edges: converted.document.edges },
    }),
    { nodes, edges, dirty: false, conflict: false },
  )
  assert.equal(step.applied, true)
  nodes = step.nodes
  edges = step.edges
  assert.equal(nodes.length, 3)
  assert.equal(edges.length, 2)

  // 7. Dirty local draft is never overwritten by remote batch, while remote drag still provides visual overlay
  const dirtyStep = applyLiveDiagramEvent(
    makeEvent('NODE_MOVED', { nodeId: 'ven', position: { x: 600, y: 220 }, dragging: true }),
    { nodes, edges, dirty: true, conflict: false },
  )
  assert.equal(dirtyStep.applied, false)
  assert.deepEqual(dirtyStep.dragOverlay, {
    nodeId: 'ven',
    position: { x: 600, y: 220 },
    dragging: true,
  })
  assert.deepEqual(dirtyStep.nodes.find((n) => n.id === 'ven').position, { x: 420, y: 100 })
})

test('remote cursors overlay never blocks pointer events on React Flow and applyRemoteEvent never marks receiver dirty', () => {
  const styles = readFileSync(new URL('../src/features/diagram/diagram.css', import.meta.url), 'utf8')
  const overlay = readFileSync(new URL('../src/features/collaboration/components/RemoteCursorsOverlay.tsx', import.meta.url), 'utf8')
  const page = readFileSync(new URL('../src/features/diagram/pages/DiagramEditorPage.tsx', import.meta.url), 'utf8')

  assert.match(styles, /\.remote-cursors-layer\s*\{[^}]*pointer-events:\s*none;/s)
  assert.match(styles, /\.remote-cursor\s*\{[^}]*pointer-events:\s*none;/s)
  assert.match(overlay, /useViewport\(\)/)
  assert.match(overlay, /aria-hidden="true"/)

  const remoteStart = page.indexOf('const applyRemoteEvent = useCallback(')
  const remoteEnd = page.indexOf('useProjectCollaboration(project.id, applyRemoteEvent', remoteStart)
  assert.ok(remoteStart > 0 && remoteEnd > remoteStart)
  const applyRemoteBlock = page.slice(remoteStart, remoteEnd)
  assert.doesNotMatch(applyRemoteBlock, /markDirty\(/)
})
