import assert from 'node:assert/strict'
import test from 'node:test'
import { readFileSync } from 'node:fs'
import {
  ElementLockStore,
  extractElementLockedRejection,
  formatLockedByMessage,
  requiredLockIdsForAssociationConversion,
  requiredLockIdsForConnection,
  requiredLockIdsForEdgeEdit,
  requiredLockIdsForNodeDelete,
  requiredLockIdsForNodeEdit,
} from '../src/features/collaboration/services/elementLocks.ts'
import {
  canonicalizeDocument,
  diffLocalElementsAgainstBase,
  projectRemotePreviewOntoLocal,
  rebaseLocalOntoServer,
} from '../src/features/collaboration/services/diagramThreeWayMerge.ts'
import { DiagramSynchronizer } from '../src/features/collaboration/services/diagramSynchronization.ts'
import { RemoteCursorStore } from '../src/features/collaboration/services/remoteCursors.ts'
import { applyLiveDiagramEvent } from '../src/features/collaboration/services/liveDiagramEvents.ts'
import { convertManyToManyAssociation } from '../src/features/diagram/services/associationConversion.ts'

function makeClassNode(id, name, x = 100, y = 120, extraAttributes = []) {
  return {
    id,
    type: 'entity',
    position: { x, y },
    data: {
      id,
      kind: 'CLASS',
      name,
      generateCrud: true,
      attributes: [
        { id: `${id}-pk`, name: 'id', type: 'BIGINT', primaryKey: true, visibility: 'private' },
        ...extraAttributes,
      ],
      methods: [
        { id: `${id}-m1`, name: 'validar', visibility: 'public', returnType: 'boolean', parameters: [] },
      ],
    },
  }
}

function makeLock(elementId, overrides = {}) {
  const now = overrides.now ?? 10_000
  const ttlMs = overrides.ttlMs ?? 30_000
  return {
    projectId: overrides.projectId ?? 10,
    elementId,
    userId: overrides.userId ?? 7,
    userName: overrides.userName ?? 'Gabriel',
    clientId: overrides.clientId ?? 'tab-gabriel-1',
    sessionId: overrides.sessionId ?? 'stomp-g1',
    acquiredAt: new Date(now).toISOString(),
    expiresAt: new Date(now + ttlMs).toISOString(),
  }
}

function makeEvent(type, payload, overrides = {}) {
  return {
    eventId: overrides.eventId ?? crypto.randomUUID(),
    type,
    userId: overrides.userId ?? 7,
    name: overrides.name ?? 'Gabriel',
    projectId: overrides.projectId ?? 10,
    clientId: overrides.clientId ?? 'tab-gabriel-1',
    timestamp: '2026-10-28T12:00:00Z',
    payload,
    message: null,
  }
}

test('ElementLockStore handles acquire, foreign lock detection, renewal, non-owner release protection, TTL expiration, disconnect, and multi-session disambiguation', () => {
  const store = new ElementLockStore()

  // 1. Collaborator Gabriel acquires lock on Producto
  store.applyEvent(
    makeEvent('ELEMENT_LOCKED', {
      locks: [makeLock('node-producto', { userId: 7, userName: 'Gabriel', clientId: 'tab-g1', now: 1_000, ttlMs: 30_000 })],
    }),
    'my-tab',
    1_000,
  )

  const prodLock = store.getLock('node-producto')
  assert.ok(prodLock)
  assert.equal(prodLock.ownedBySelf, false)
  assert.equal(prodLock.label, 'Gabriel')
  assert.equal(formatLockedByMessage(prodLock.label), 'Está siendo usado por Gabriel')
  assert.equal(store.findForeignLock(['node-ventas', 'node-producto'])?.userName, 'Gabriel')
  assert.equal(store.findForeignLock(['node-ventas']), null)

  // 2. Local tab acquires lock on Ventas simultaneously
  store.upsertLocks(
    [makeLock('node-ventas', { userId: 8, userName: 'Carlos', clientId: 'my-tab', now: 1_100, ttlMs: 30_000 })],
    'my-tab',
    1_100,
  )
  assert.equal(store.areAllOwnedBySelf(['node-ventas']), true)
  assert.equal(store.areAllOwnedBySelf(['node-producto']), false)
  assert.deepEqual(store.ownedElementIds(), ['node-ventas'])

  // 3. Another user cannot release Gabriel's lock via spoofed/mismatched clientId event
  assert.equal(
    store.applyEvent(
      makeEvent('ELEMENT_UNLOCKED', { elementIds: ['node-producto'] }, { userId: 9, name: 'Intruso', clientId: 'tab-intruso' }),
      'my-tab',
      1_200,
    ),
    false,
  )
  assert.ok(store.getLock('node-producto'))

  // 4. Gabriel opens a second tab and locks Cliente -> disambiguated as Gabriel (1) and Gabriel (2) with distinct colors
  store.applyEvent(
    makeEvent(
      'ELEMENT_LOCKED',
      { locks: [makeLock('node-cliente', { userId: 7, userName: 'Gabriel', clientId: 'tab-g2', now: 1_300, ttlMs: 30_000 })] },
      { userId: 7, name: 'Gabriel', clientId: 'tab-g2' },
    ),
    'my-tab',
    1_300,
  )
  const g1 = store.getLock('node-producto')
  const g2 = store.getLock('node-cliente')
  assert.ok(g1 && g2)
  assert.deepEqual([g1.label, g2.label], ['Gabriel (1)', 'Gabriel (2)'])
  assert.notEqual(g1.color, g2.color)

  // 5. Lease renewal extends Gabriel (1)'s lock past the initial 31_000 ms deadline
  store.applyEvent(
    makeEvent('ELEMENT_LOCKED', {
      locks: [makeLock('node-producto', { userId: 7, userName: 'Gabriel', clientId: 'tab-g1', now: 25_000, ttlMs: 30_000 })],
    }),
    'my-tab',
    25_000,
  )

  // At t = 35_000 ms, unrenewed locks (node-ventas, node-cliente) expire automatically; renewed node-producto survives
  store.pruneExpired(35_000)
  assert.equal(store.getLock('node-ventas'), null)
  assert.equal(store.getLock('node-cliente'), null)
  assert.equal(store.getLock('node-producto')?.label, 'Gabriel')

  // 6. Legitimate release by owner or WebSocket USER_LEFT clears remaining locks
  store.applyEvent(
    makeEvent('ELEMENT_UNLOCKED', { elementIds: ['node-producto'] }, { userId: 7, name: 'Gabriel', clientId: 'tab-g1' }),
    'my-tab',
    35_500,
  )
  assert.equal(store.getSnapshot().length, 0)

  store.upsertLocks(
    [makeLock('node-factura', { userId: 7, userName: 'Gabriel', clientId: 'tab-g1', now: 36_000, ttlMs: 30_000 })],
    'my-tab',
    36_000,
  )
  store.applyEvent(makeEvent('USER_LEFT', null, { userId: 7, name: 'Gabriel', clientId: null }), 'my-tab', 36_100)
  assert.equal(store.getSnapshot().length, 0)
})

test('HTTP 409 ELEMENT_LOCKED translates into friendly Spanish message and never leaks technical HTTP conflict jargon', () => {
  const axiosLikeError = {
    isAxiosError: true,
    response: {
      status: 409,
      data: {
        code: 'ELEMENT_LOCKED',
        mensaje: 'Está siendo usado por Gabriel',
        elementId: 'node-producto',
        lockedByUserId: 7,
        lockedByName: 'Gabriel',
        lockedByClientId: 'tab-g1',
      },
    },
  }
  const rejection = extractElementLockedRejection(axiosLikeError)
  assert.ok(rejection)
  assert.equal(rejection.ownerName, 'Gabriel')
  assert.equal(rejection.elementId, 'node-producto')
  assert.equal(rejection.message, 'Está siendo usado por Gabriel')
  assert.doesNotMatch(rejection.message, /409|Conflict/i)

  // A revision conflict 409 (without ELEMENT_LOCKED code) is NOT misclassified as an element lock
  const revisionConflict409 = {
    isAxiosError: true,
    response: {
      status: 409,
      data: { mensaje: 'El diagrama cambió en el servidor.' },
    },
  }
  assert.equal(extractElementLockedRejection(revisionConflict409), null)
})

test('compound lock helpers return deterministic sorted lock sets for associative classes, relationships, conversions, and deletions', () => {
  const prod = makeClassNode('prod', 'Producto', 100, 100)
  const ven = makeClassNode('ven', 'Ventas', 450, 100)
  const nmEdge = {
    id: 'rel-nm',
    source: 'ven',
    target: 'prod',
    type: 'relationship',
    data: { sourceCardinality: 'ZERO_MANY', targetCardinality: 'ONE_MANY' },
  }

  assert.deepEqual(requiredLockIdsForConnection('ven', 'prod'), ['prod', 'ven'])
  assert.deepEqual(requiredLockIdsForAssociationConversion(nmEdge), ['prod', 'rel-nm', 'ven'])

  const converted = convertManyToManyAssociation([prod, ven], [nmEdge], 'rel-nm', 'DetalleVenta')
  const assocNode = converted.document.nodes.find((n) => n.id === converted.associationNodeId)
  const structuralEdgeIds = assocNode.data.association.endpoints.map((ep) => ep.relationshipId)

  const assocEditLocks = requiredLockIdsForNodeEdit(assocNode.id, converted.document.nodes)
  assert.deepEqual(assocEditLocks, [assocNode.id, ...structuralEdgeIds].sort())

  // Editing one structural edge locks the associative class and all its structural edges atomically
  assert.deepEqual(
    requiredLockIdsForEdgeEdit(structuralEdgeIds[0], converted.document.nodes),
    [assocNode.id, ...structuralEdgeIds].sort(),
  )

  // Deleting a class with incident edges locks the class and every dependent edge
  const extraEdge = {
    id: 'rel-extra',
    source: 'prod',
    target: 'ven',
    type: 'relationship',
    data: { sourceCardinality: 'ONE_ONE', targetCardinality: 'ZERO_MANY', name: 'referencia' },
  }
  const deleteLocks = requiredLockIdsForNodeDelete('prod', [prod, ven], [extraEdge])
  assert.deepEqual(deleteLocks, ['prod', 'rel-extra'])
})

test('two collaborators editing different classes converge automatically via DiagramSynchronizer + 3-way rebase without false conflict banners', async () => {
  const initialNodes = [
    makeClassNode('prod', 'Producto', 100, 100),
    makeClassNode('ven', 'Ventas', 420, 100),
  ]
  const initialDoc = canonicalizeDocument(initialNodes, [])

  // Simulated authoritative server state starting at revision 1
  let serverState = {
    id: 55,
    proyectoId: 10,
    version: 2,
    revision: 1,
    contenido: structuredClone(initialDoc),
    fechaActualizacion: '2026-10-28T12:00:00Z',
  }

  function createCollaboratorClient(name) {
    let confirmedBase = structuredClone(initialDoc)
    let nodes = structuredClone(initialNodes)
    let edges = []
    let revision = 1
    let dirty = false
    let dragging = false
    let conflicts = 0
    const syncMessages = []

    const sync = new DiagramSynchronizer({
      read: async () => structuredClone(serverState),
      state: () => ({ revision, dirty, dragging, saving: null }),
      apply: (saved, author) => {
        confirmedBase = canonicalizeDocument(saved.contenido.nodes, saved.contenido.edges)
        nodes = structuredClone(saved.contenido.nodes)
        edges = structuredClone(saved.contenido.edges)
        revision = saved.revision
        dirty = false
        syncMessages.push(`apply:${author ?? 'server'}:${saved.revision}`)
      },
      reconcileDirty: (saved, author) => {
        const rebased = rebaseLocalOntoServer(confirmedBase, saved.contenido, nodes, edges)
        if (rebased.outcome === 'conflict') return 'conflict'
        confirmedBase = canonicalizeDocument(saved.contenido.nodes, saved.contenido.edges)
        revision = saved.revision
        nodes = rebased.nodes
        edges = rebased.edges
        dirty = rebased.stillDirty
        syncMessages.push(`rebased:${author ?? 'server'}:${saved.revision}`)
        return 'rebased'
      },
      conflict: () => {
        conflicts += 1
      },
      error: (err) => {
        throw err
      },
    })

    return {
      name,
      sync,
      getNodes: () => nodes,
      getRevision: () => revision,
      isDirty: () => dirty,
      getConflicts: () => conflicts,
      editNode: (nodeId, mutator, options = {}) => {
        nodes = nodes.map((n) => (n.id === nodeId ? mutator(structuredClone(n)) : n))
        dirty = true
        if (options.dragging !== undefined) dragging = options.dragging
      },
      setDragging: (value) => {
        dragging = value
      },
      commitToServer: () => {
        assert.equal(revision, serverState.revision, `${name} must have rebased to latest revision`)
        const doc = canonicalizeDocument(nodes, edges)
        serverState = {
          ...serverState,
          revision: serverState.revision + 1,
          contenido: structuredClone(doc),
        }
        confirmedBase = structuredClone(doc)
        revision = serverState.revision
        dirty = false
        return serverState.revision
      },
    }
  }

  const gabriel = createCollaboratorClient('Gabriel')
  const carlos = createCollaboratorClient('Carlos')

  // Step 1: Gabriel edits Ventas (adds attribute total + renames to VentaConfirmada) while Carlos simultaneously drags & edits Producto!
  gabriel.editNode('ven', (node) => {
    node.data.name = 'VentaConfirmada'
    node.data.attributes.push({
      id: 'ven-total',
      name: 'total',
      type: 'DECIMAL',
      primaryKey: false,
      visibility: 'private',
    })
    return node
  })

  carlos.editNode(
    'prod',
    (node) => {
      node.position = { x: 260, y: 310 }
      node.dragging = true
      node.data.attributes.push({
        id: 'prod-stock',
        name: 'stock',
        type: 'INTEGER',
        primaryKey: false,
        visibility: 'private',
      })
      return node
    },
    { dragging: true },
  )

  // Step 2: Gabriel's autosave commits Revision 2 while Carlos is STILL dirty AND actively dragging Producto!
  const rev2 = gabriel.commitToServer()
  assert.equal(rev2, 2)

  // DIAGRAM_COMMITTED (rev 2) arrives at Carlos (plus a duplicate notification)
  await Promise.all([
    carlos.sync.refresh({ name: 'Gabriel', revision: 2 }),
    carlos.sync.refresh({ name: 'Gabriel', revision: 2 }),
  ])

  // Carlos MUST NOT enter conflict! Carlos now has Gabriel's VentaConfirmada AND his own in-progress Producto edit!
  assert.equal(carlos.getConflicts(), 0)
  assert.equal(carlos.getRevision(), 2)
  assert.equal(carlos.isDirty(), true)
  assert.equal(carlos.getNodes().find((n) => n.id === 'ven').data.name, 'VentaConfirmada')
  assert.deepEqual(carlos.getNodes().find((n) => n.id === 'prod').position, { x: 260, y: 310 })
  assert.equal(carlos.getNodes().find((n) => n.id === 'prod').data.attributes.length, 2)

  // Step 3: Carlos finishes dragging Producto and his autosave commits Revision 3
  carlos.editNode('prod', (node) => {
    delete node.dragging
    return node
  })
  carlos.setDragging(false)
  const rev3 = carlos.commitToServer()
  assert.equal(rev3, 3)

  // DIAGRAM_COMMITTED (rev 3) arrives at Gabriel
  await gabriel.sync.refresh({ name: 'Carlos', revision: 3 })

  // Both browsers converged to Revision 3 with zero conflicts and zero reloads!
  assert.equal(gabriel.getConflicts(), 0)
  assert.equal(carlos.getConflicts(), 0)
  assert.equal(gabriel.getRevision(), 3)
  assert.deepEqual(
    canonicalizeDocument(gabriel.getNodes(), []),
    canonicalizeDocument(carlos.getNodes(), []),
  )
})

test('ephemeral STOMP previews render live while locally dirty without polluting the local PUT persistence buffer', () => {
  const confirmedBase = canonicalizeDocument(
    [makeClassNode('prod', 'Producto', 100, 100), makeClassNode('ven', 'Ventas', 420, 100)],
    [],
  )

  // Local user Carlos modifies Producto locally
  const localNodes = structuredClone(confirmedBase.nodes)
  localNodes[0].data.name = 'ProductoLocal'
  const localEdges = []

  // Remote user Gabriel streams live STOMP events: updates Ventas AND creates Factura (not yet saved via PUT)
  let previewGraph = { nodes: confirmedBase.nodes, edges: confirmedBase.edges }
  const updatedVentas = structuredClone(confirmedBase.nodes[1])
  updatedVentas.position = { x: 520, y: 180 }
  let liveStep = applyLiveDiagramEvent(
    makeEvent('NODE_UPDATED', { node: updatedVentas }),
    { nodes: previewGraph.nodes, edges: previewGraph.edges, dirty: false, conflict: false },
  )
  assert.equal(liveStep.applied, true)
  previewGraph = { nodes: liveStep.nodes, edges: liveStep.edges }

  const factura = makeClassNode('fac', 'Factura', 760, 100)
  liveStep = applyLiveDiagramEvent(
    makeEvent('NODE_CREATED', { node: factura }),
    { nodes: previewGraph.nodes, edges: previewGraph.edges, dirty: false, conflict: false },
  )
  assert.equal(liveStep.applied, true)
  previewGraph = { nodes: liveStep.nodes, edges: liveStep.edges }

  // Rendered canvas projection combines Carlos's dirty ProductoLocal + Gabriel's live Ventas move & Factura creation
  const projected = projectRemotePreviewOntoLocal(
    confirmedBase,
    previewGraph,
    localNodes,
    localEdges,
    { fac: { width: 250, height: 108 } },
  )
  assert.equal(projected.nodes.length, 3)
  assert.equal(projected.nodes.find((n) => n.id === 'prod').data.name, 'ProductoLocal')
  assert.deepEqual(projected.nodes.find((n) => n.id === 'ven').position, { x: 520, y: 180 })
  assert.deepEqual(projected.nodes.find((n) => n.id === 'fac').measured, { width: 250, height: 108 })

  // Meanwhile, Carlos's local persistence buffer ONLY contains confirmed Ventas + ProductoLocal (never Gabriel's unconfirmed Factura!)
  const localDiff = diffLocalElementsAgainstBase(confirmedBase, localNodes, localEdges)
  assert.deepEqual([...localDiff.allTouchedIds], ['prod'])
  assert.equal(localNodes.some((n) => n.id === 'fac'), false)
})

test('true overlapping conflict on the same element or deleted relationship endpoint preserves local draft without silent overwrite', () => {
  const base = canonicalizeDocument(
    [makeClassNode('prod', 'Producto', 100, 100), makeClassNode('ven', 'Ventas', 420, 100)],
    [],
  )

  // Case A: Both modified Producto differently (e.g., lock expired during offline tab)
  const serverSameNode = structuredClone(base)
  serverSameNode.nodes[0].data.name = 'ProductoServidor'
  const localSameNode = structuredClone(base.nodes)
  localSameNode[0].data.name = 'ProductoBorradorLocal'

  const conflictSame = rebaseLocalOntoServer(base, serverSameNode, localSameNode, [])
  assert.equal(conflictSame.outcome, 'conflict')
  assert.equal(conflictSame.nodes[0].data.name, 'ProductoBorradorLocal')

  // Case B: Server deleted Ventas while local user created a new relationship prod -> ven
  const serverDeletedVen = canonicalizeDocument([base.nodes[0]], [])
  const localAddedEdge = [
    {
      id: 'rel-new',
      source: 'prod',
      target: 'ven',
      type: 'relationship',
      data: { sourceCardinality: 'ONE_ONE', targetCardinality: 'ZERO_MANY' },
    },
  ]
  const conflictDangling = rebaseLocalOntoServer(base, serverDeletedVen, base.nodes, localAddedEdge)
  assert.equal(conflictDangling.outcome, 'conflict')
  assert.equal(conflictDangling.edges.length, 1)

  // Case C: Two users renamed different classes to the exact same entity name
  const serverRenamedVen = structuredClone(base)
  serverRenamedVen.nodes[1].data.name = 'ArticuloUnico'
  const localRenamedProd = structuredClone(base.nodes)
  localRenamedProd[0].data.name = 'ArticuloUnico'
  const conflictDuplicateName = rebaseLocalOntoServer(base, serverRenamedVen, localRenamedProd, [])
  assert.equal(conflictDuplicateName.outcome, 'conflict')
})

test('cursors and per-element locks share consistent collaborator colors and non-invasive visual indicators', () => {
  const cursorStore = new RemoteCursorStore()
  const lockStore = new ElementLockStore()

  cursorStore.applyEvent(
    makeEvent('CURSOR_MOVED', { x: 180, y: 220, sessionId: 'stomp-g1' }, { userId: 7, name: 'Gabriel', clientId: 'tab-g1' }),
    'my-tab',
    1_000,
  )
  lockStore.applyEvent(
    makeEvent('ELEMENT_LOCKED', {
      locks: [makeLock('prod', { userId: 7, userName: 'Gabriel', clientId: 'tab-g1', now: 1_000 })],
    }),
    'my-tab',
    1_000,
  )

  const cursor = cursorStore.getSnapshot()[0]
  const lock = lockStore.getLock('prod')
  assert.ok(cursor && lock)
  assert.equal(cursor.color, lock.color)
  assert.equal(cursor.label, lock.label)

  const entityNode = readFileSync(new URL('../src/features/diagram/components/EntityNode.tsx', import.meta.url), 'utf8')
  const relationshipEdge = readFileSync(new URL('../src/features/diagram/components/RelationshipEdge.tsx', import.meta.url), 'utf8')
  const propertiesPanel = readFileSync(new URL('../src/features/diagram/components/DiagramPropertiesPanel.tsx', import.meta.url), 'utf8')
  const styles = readFileSync(new URL('../src/features/diagram/diagram.css', import.meta.url), 'utf8')

  assert.match(entityNode, /element-lock-badge/)
  assert.match(entityNode, /editando/)
  assert.match(relationshipEdge, /element-lock-badge is-edge/)
  assert.match(propertiesPanel, /properties-lock-banner/)
  assert.match(styles, /\.element-lock-badge\s*\{[^}]*position:\s*absolute;[^}]*pointer-events:\s*none;/s)
})
