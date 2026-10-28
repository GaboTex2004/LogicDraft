import { cloneAssociationMetadata } from '../../diagram/services/associationMetadata.ts'
import { validateDiagramDocument } from '../../diagram/services/diagramDocumentValidation.ts'
import { normalizeRelationshipEdge } from '../../diagram/services/relationshipCardinality.ts'
import type {
  DiagramDocument,
  DiagramEdge,
  EntityFlowNode,
} from '../../diagram/types/diagram.types.ts'

export function canonicalizeNode(node: EntityFlowNode): EntityFlowNode {
  return {
    id: node.id,
    type: 'entity',
    position: { x: node.position.x, y: node.position.y },
    data: {
      id: node.data.id,
      name: node.data.name,
      attributes: node.data.attributes.map((attribute) => ({ ...attribute })),
      ...(node.data.kind ? { kind: node.data.kind } : {}),
      ...(node.data.kind === 'CLASS' && node.data.generateCrud === true
        ? { generateCrud: true }
        : {}),
      ...(node.data.methods
        ? {
            methods: node.data.methods.map((method) => ({
              ...method,
              parameters: method.parameters.map((parameter) => ({ ...parameter })),
            })),
          }
        : {}),
      ...(node.data.literals ? { literals: [...node.data.literals] } : {}),
      ...(node.data.abstract !== undefined ? { abstract: node.data.abstract } : {}),
      ...(node.data.width !== undefined ? { width: node.data.width } : {}),
      ...(node.data.height !== undefined ? { height: node.data.height } : {}),
      ...(node.data.association
        ? { association: cloneAssociationMetadata(node.data.association) }
        : {}),
      ...(node.data.externalMetadata
        ? { externalMetadata: { ...node.data.externalMetadata } }
        : {}),
    },
  }
}

export function canonicalizeEdge(edge: DiagramEdge): DiagramEdge {
  const normalized = normalizeRelationshipEdge(edge)
  return {
    id: normalized.id,
    source: normalized.source,
    target: normalized.target,
    ...(normalized.sourceHandle ? { sourceHandle: normalized.sourceHandle } : {}),
    ...(normalized.targetHandle ? { targetHandle: normalized.targetHandle } : {}),
    ...(normalized.type ? { type: normalized.type } : {}),
    ...(normalized.markerEnd ? { markerEnd: normalized.markerEnd } : {}),
    ...(normalized.data ? { data: { ...normalized.data } } : {}),
  }
}

export function inferCanonicalVersion(
  nodes: readonly EntityFlowNode[],
  edges: readonly DiagramEdge[],
): 1 | 2 {
  return nodes.some(
    (node) =>
      (node.data.kind ?? 'ENTITY') !== 'ENTITY' ||
      Boolean(node.data.methods?.length) ||
      Boolean(node.data.abstract),
  ) || edges.some((edge) => Boolean(edge.data?.relationKind))
    ? 2
    : 1
}

export function canonicalizeDocument(
  nodes: readonly EntityFlowNode[],
  edges: readonly DiagramEdge[],
): DiagramDocument {
  return {
    version: inferCanonicalVersion(nodes, edges),
    nodes: nodes.map(canonicalizeNode),
    edges: edges.map(canonicalizeEdge),
  }
}

function stableNormalize(value: unknown): unknown {
  if (typeof value === 'number') {
    return Object.is(value, -0) ? 0 : value
  }
  if (Array.isArray(value)) {
    return value.map(stableNormalize)
  }
  if (typeof value === 'object' && value !== null) {
    const record = value as Record<string, unknown>
    const sorted: Record<string, unknown> = {}
    for (const key of Object.keys(record).sort()) {
      if (record[key] !== undefined) {
        sorted[key] = stableNormalize(record[key])
      }
    }
    return sorted
  }
  return value
}

export function canonicalJson(value: unknown): string {
  return JSON.stringify(stableNormalize(value))
}

export function nodeContentEquals(
  left: EntityFlowNode | undefined,
  right: EntityFlowNode | undefined,
): boolean {
  if (!left && !right) return true
  if (!left || !right) return false
  return canonicalJson(canonicalizeNode(left)) === canonicalJson(canonicalizeNode(right))
}

export function edgeContentEquals(
  left: DiagramEdge | undefined,
  right: DiagramEdge | undefined,
): boolean {
  if (!left && !right) return true
  if (!left || !right) return false
  return canonicalJson(canonicalizeEdge(left)) === canonicalJson(canonicalizeEdge(right))
}

export interface LocalElementDiff {
  modifiedOrDeletedIds: Set<string>
  addedIds: Set<string>
  referencedNodeIdsOfAddedEdges: Set<string>
  activelyDraggingIds: Set<string>
  allTouchedIds: Set<string>
  lockProtectedIds: string[]
}

export function diffLocalElementsAgainstBase(
  base: DiagramDocument,
  localNodes: readonly EntityFlowNode[],
  localEdges: readonly DiagramEdge[],
): LocalElementDiff {
  const baseNodes = new Map(base.nodes.map((node) => [node.id, node]))
  const currentNodes = new Map(localNodes.map((node) => [node.id, node]))
  const baseEdges = new Map(base.edges.map((edge) => [edge.id, edge]))
  const currentEdges = new Map(localEdges.map((edge) => [edge.id, edge]))

  const modifiedOrDeletedIds = new Set<string>()
  const addedIds = new Set<string>()
  const referencedNodeIdsOfAddedEdges = new Set<string>()
  const activelyDraggingIds = new Set<string>()

  for (const node of localNodes) {
    if (node.dragging) {
      activelyDraggingIds.add(node.id)
    }
  }

  for (const id of new Set([...baseNodes.keys(), ...currentNodes.keys()])) {
    const b = baseNodes.get(id)
    const c = currentNodes.get(id)
    if (!b && c) {
      addedIds.add(id)
    } else if (b && (!nodeContentEquals(b, c) || activelyDraggingIds.has(id))) {
      modifiedOrDeletedIds.add(id)
      if (b.data.association) {
        for (const ep of b.data.association.endpoints) modifiedOrDeletedIds.add(ep.relationshipId)
      }
      if (c?.data.association) {
        for (const ep of c.data.association.endpoints) modifiedOrDeletedIds.add(ep.relationshipId)
      }
    }
  }

  for (const id of new Set([...baseEdges.keys(), ...currentEdges.keys()])) {
    const b = baseEdges.get(id)
    const c = currentEdges.get(id)
    if (!b && c) {
      addedIds.add(id)
      if (baseNodes.has(c.source)) referencedNodeIdsOfAddedEdges.add(c.source)
      if (baseNodes.has(c.target)) referencedNodeIdsOfAddedEdges.add(c.target)
    } else if (b && !edgeContentEquals(b, c)) {
      modifiedOrDeletedIds.add(id)
    }
  }

  const allTouchedIds = new Set<string>([
    ...modifiedOrDeletedIds,
    ...addedIds,
    ...activelyDraggingIds,
  ])

  const lockProtectedIds = [
    ...new Set([
      ...modifiedOrDeletedIds,
      ...referencedNodeIdsOfAddedEdges,
      ...activelyDraggingIds,
    ]),
  ].sort()

  return {
    modifiedOrDeletedIds,
    addedIds,
    referencedNodeIdsOfAddedEdges,
    activelyDraggingIds,
    allTouchedIds,
    lockProtectedIds,
  }
}

export interface RebaseOutcome {
  outcome: 'clean' | 'rebased' | 'conflict'
  nodes: EntityFlowNode[]
  edges: DiagramEdge[]
  stillDirty: boolean
}

function isDocumentStructurallyValid(
  nodes: readonly EntityFlowNode[],
  edges: readonly DiagramEdge[],
): boolean {
  try {
    validateDiagramDocument(canonicalizeDocument(nodes, edges))
    return true
  } catch {
    return false
  }
}

function mergeNodePreservingUiState(
  winner: EntityFlowNode,
  localNode: EntityFlowNode | undefined,
): EntityFlowNode {
  if (!localNode) return winner
  const preserveMeasured =
    localNode.measured && nodeContentEquals(winner, localNode)
      ? { measured: localNode.measured }
      : {}
  return {
    ...winner,
    ...(localNode.selected ? { selected: true } : {}),
    ...preserveMeasured,
  }
}

/**
 * Rebases local working state (authored on `base`) onto a newly confirmed `server` snapshot.
 *
 * - Disjoint edits on different UML elements merge automatically without user interruption
 *   (including while the user is actively dragging another node).
 * - True overlapping edits on the same element or cross-element structural collisions
 *   return `outcome: 'conflict'` without altering the local draft.
 */
export function rebaseLocalOntoServer(
  base: DiagramDocument,
  server: DiagramDocument,
  localNodes: readonly EntityFlowNode[],
  localEdges: readonly DiagramEdge[],
): RebaseOutcome {
  const localWasValid = isDocumentStructurallyValid(localNodes, localEdges)

  const baseNodes = new Map(base.nodes.map((node) => [node.id, node]))
  const serverNodes = new Map(server.nodes.map((node) => [node.id, node]))
  const workingNodes = new Map(localNodes.map((node) => [node.id, node]))

  const baseEdges = new Map(base.edges.map((edge) => [edge.id, edge]))
  const serverEdges = new Map(server.edges.map((edge) => [edge.id, edge]))
  const workingEdges = new Map(localEdges.map((edge) => [edge.id, edge]))

  const resolvedNodes = new Map<string, EntityFlowNode>()
  const deletedNodeIds = new Set<string>()
  const serverTouchedNodeIds = new Set<string>()
  const localTouchedNodeIds = new Set<string>()
  let stillDirty = false

  const orderedNodeIds = new Set<string>([
    ...serverNodes.keys(),
    ...workingNodes.keys(),
    ...baseNodes.keys(),
  ])

  for (const id of orderedNodeIds) {
    const b = baseNodes.get(id)
    const s = serverNodes.get(id)
    const l = workingNodes.get(id)
    const isDragging = Boolean(l?.dragging)

    const changedInServer = !nodeContentEquals(b, s)
    const changedInLocal = isDragging || !nodeContentEquals(b, l)
    if (changedInServer) serverTouchedNodeIds.add(id)
    if (changedInLocal) localTouchedNodeIds.add(id)

    if (!changedInLocal) {
      if (s) {
        resolvedNodes.set(id, mergeNodePreservingUiState(s, l))
      } else {
        deletedNodeIds.add(id)
      }
    } else if (!changedInServer) {
      if (l) {
        resolvedNodes.set(id, l)
      } else {
        deletedNodeIds.add(id)
      }
      if (!nodeContentEquals(s, l)) {
        stillDirty = true
      }
    } else if (!isDragging && nodeContentEquals(s, l)) {
      if (s) {
        resolvedNodes.set(id, mergeNodePreservingUiState(s, l))
      } else {
        deletedNodeIds.add(id)
      }
    } else {
      return {
        outcome: 'conflict',
        nodes: [...localNodes],
        edges: [...localEdges],
        stillDirty: true,
      }
    }
  }

  const resolvedEdges = new Map<string, DiagramEdge>()
  const deletedEdgeIds = new Set<string>()

  const orderedEdgeIds = new Set<string>([
    ...serverEdges.keys(),
    ...workingEdges.keys(),
    ...baseEdges.keys(),
  ])

  for (const id of orderedEdgeIds) {
    const b = baseEdges.get(id)
    const s = serverEdges.get(id)
    const l = workingEdges.get(id)

    const changedInServer = !edgeContentEquals(b, s)
    const changedInLocal = !edgeContentEquals(b, l)

    if (!changedInLocal) {
      if (s) {
        resolvedEdges.set(id, l?.selected ? { ...s, selected: true } : s)
      } else {
        deletedEdgeIds.add(id)
      }
    } else if (!changedInServer) {
      if (l) {
        resolvedEdges.set(id, l)
      } else {
        deletedEdgeIds.add(id)
      }
      if (!edgeContentEquals(s, l)) {
        stillDirty = true
      }
    } else if (edgeContentEquals(s, l)) {
      if (s) {
        resolvedEdges.set(id, l?.selected ? { ...s, selected: true } : s)
      } else {
        deletedEdgeIds.add(id)
      }
    } else {
      return {
        outcome: 'conflict',
        nodes: [...localNodes],
        edges: [...localEdges],
        stillDirty: true,
      }
    }
  }

  const mergedNodes: EntityFlowNode[] = []
  for (const id of serverNodes.keys()) {
    if (!deletedNodeIds.has(id) && resolvedNodes.has(id)) {
      mergedNodes.push(resolvedNodes.get(id)!)
      resolvedNodes.delete(id)
    }
  }
  for (const id of workingNodes.keys()) {
    if (!deletedNodeIds.has(id) && resolvedNodes.has(id)) {
      mergedNodes.push(resolvedNodes.get(id)!)
      resolvedNodes.delete(id)
    }
  }
  for (const node of resolvedNodes.values()) mergedNodes.push(node)

  const mergedEdges: DiagramEdge[] = []
  for (const id of serverEdges.keys()) {
    if (!deletedEdgeIds.has(id) && resolvedEdges.has(id)) {
      mergedEdges.push(resolvedEdges.get(id)!)
      resolvedEdges.delete(id)
    }
  }
  for (const id of workingEdges.keys()) {
    if (!deletedEdgeIds.has(id) && resolvedEdges.has(id)) {
      mergedEdges.push(resolvedEdges.get(id)!)
      resolvedEdges.delete(id)
    }
  }
  for (const edge of resolvedEdges.values()) mergedEdges.push(edge)

  // Cross-element integrity checks
  const survivingNodeIds = new Set(mergedNodes.map((node) => node.id))
  const survivingEdgeIds = new Set(mergedEdges.map((edge) => edge.id))

  for (const edge of mergedEdges) {
    if (!survivingNodeIds.has(edge.source) || !survivingNodeIds.has(edge.target)) {
      return {
        outcome: 'conflict',
        nodes: [...localNodes],
        edges: [...localEdges],
        stillDirty: true,
      }
    }
  }

  for (const node of mergedNodes) {
    if (node.data.association) {
      for (const endpoint of node.data.association.endpoints) {
        if (
          !survivingNodeIds.has(endpoint.entityId) ||
          !survivingEdgeIds.has(endpoint.relationshipId)
        ) {
          return {
            outcome: 'conflict',
            nodes: [...localNodes],
            edges: [...localEdges],
            stillDirty: true,
          }
        }
      }
    }
  }

  // Detect duplicate entity name collision introduced between server and local edits
  const namesToOwner = new Map<string, string>()
  for (const node of mergedNodes) {
    const normalizedName = node.data.name.trim().toLocaleLowerCase()
    if (!normalizedName) continue
    const existingId = namesToOwner.get(normalizedName)
    if (existingId) {
      const crossCollision =
        (serverTouchedNodeIds.has(existingId) && localTouchedNodeIds.has(node.id)) ||
        (localTouchedNodeIds.has(existingId) && serverTouchedNodeIds.has(node.id))
      if (crossCollision) {
        return {
          outcome: 'conflict',
          nodes: [...localNodes],
          edges: [...localEdges],
          stillDirty: true,
        }
      }
    } else {
      namesToOwner.set(normalizedName, node.id)
    }
  }

  if (localWasValid && !isDocumentStructurallyValid(mergedNodes, mergedEdges)) {
    return {
      outcome: 'conflict',
      nodes: [...localNodes],
      edges: [...localEdges],
      stillDirty: true,
    }
  }

  const anyDragging = mergedNodes.some((node) => node.dragging)
  return {
    outcome: !stillDirty && !anyDragging ? 'clean' : 'rebased',
    nodes: mergedNodes,
    edges: mergedEdges,
    stillDirty,
  }
}

/**
 * Projects ephemeral STOMP remote previews onto the local working graph for rendering,
 * without mutating the local persistence buffer or overriding locally dirty/dragging elements.
 */
export function projectRemotePreviewOntoLocal(
  confirmedBase: DiagramDocument,
  remotePreview: { nodes: readonly EntityFlowNode[]; edges: readonly DiagramEdge[] },
  localNodes: readonly EntityFlowNode[],
  localEdges: readonly DiagramEdge[],
  previewMeasured?: Readonly<Record<string, { width: number; height: number }>>,
  selectedIds?: { nodeId: string | null; edgeIds: ReadonlySet<string> },
): { nodes: EntityFlowNode[]; edges: DiagramEdge[] } {
  const localDiff = diffLocalElementsAgainstBase(confirmedBase, localNodes, localEdges)
  const localTouched = localDiff.allTouchedIds

  const baseNodes = new Map(confirmedBase.nodes.map((node) => [node.id, node]))
  const previewNodes = new Map(remotePreview.nodes.map((node) => [node.id, node]))
  const workingNodes = new Map(localNodes.map((node) => [node.id, node]))

  const projectedNodes: EntityFlowNode[] = []
  const seenNodes = new Set<string>()

  for (const id of [...workingNodes.keys(), ...previewNodes.keys()]) {
    if (seenNodes.has(id)) continue
    seenNodes.add(id)

    if (localTouched.has(id)) {
      const localNode = workingNodes.get(id)
      if (localNode) projectedNodes.push(localNode)
      continue
    }

    const previewNode = previewNodes.get(id)
    const baseNode = baseNodes.get(id)
    const localNode = workingNodes.get(id)

    if (!previewNode) {
      // Deleted in remote preview (only existed in baseNode and untouched locally)
      if (!baseNode && localNode) {
        projectedNodes.push(localNode)
      }
      continue
    }

    const measured = localNode?.measured ?? previewMeasured?.[id]
    const selected = localNode?.selected ?? selectedIds?.nodeId === id
    projectedNodes.push({
      ...previewNode,
      ...(measured ? { measured } : {}),
      ...(selected ? { selected: true } : {}),
    })
  }

  const projectedNodeIds = new Set(projectedNodes.map((node) => node.id))
  const baseEdges = new Map(confirmedBase.edges.map((edge) => [edge.id, edge]))
  const previewEdges = new Map(remotePreview.edges.map((edge) => [edge.id, edge]))
  const workingEdges = new Map(localEdges.map((edge) => [edge.id, edge]))

  const projectedEdges: DiagramEdge[] = []
  const seenEdges = new Set<string>()

  for (const id of [...workingEdges.keys(), ...previewEdges.keys()]) {
    if (seenEdges.has(id)) continue
    seenEdges.add(id)

    let candidate: DiagramEdge | undefined
    if (localTouched.has(id)) {
      candidate = workingEdges.get(id)
    } else {
      const previewEdge = previewEdges.get(id)
      const baseEdge = baseEdges.get(id)
      const localEdge = workingEdges.get(id)
      if (!previewEdge) {
        candidate = !baseEdge ? localEdge : undefined
      } else {
        const selected = localEdge?.selected ?? Boolean(selectedIds?.edgeIds.has(id))
        candidate = selected ? { ...previewEdge, selected: true } : previewEdge
      }
    }

    if (
      candidate &&
      projectedNodeIds.has(candidate.source) &&
      projectedNodeIds.has(candidate.target)
    ) {
      projectedEdges.push(candidate)
    }
  }

  return { nodes: projectedNodes, edges: projectedEdges }
}
