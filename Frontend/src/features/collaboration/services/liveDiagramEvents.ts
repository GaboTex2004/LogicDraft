import type { CollaborationEvent } from '../types/collaboration.types.ts'
import {
  ATTRIBUTE_TYPES,
  UML_NODE_KINDS,
  UML_VISIBILITIES,
  type AttributeType,
  type DiagramDocument,
  type DiagramEdge,
  type EntityAttribute,
  type EntityFlowNode,
  type ExternalMetadata,
  type UmlMethod,
  type UmlNodeKind,
} from '../../diagram/types/diagram.types.ts'
import { parseAssociationMetadata } from '../../diagram/services/associationMetadata.ts'
import { validateDiagramDocument } from '../../diagram/services/diagramDocumentValidation.ts'
import {
  normalizeRelationshipEdge,
  removeRelationshipEdges,
} from '../../diagram/services/relationshipCardinality.ts'

const MAX_COORDINATE = 100_000

function record(value: unknown): Record<string, unknown> | null {
  return typeof value === 'object' && value !== null ? (value as Record<string, unknown>) : null
}

function isFiniteCoordinate(value: unknown): value is number {
  return typeof value === 'number' && Number.isFinite(value)
    && value >= -MAX_COORDINATE && value <= MAX_COORDINATE
}

function parseExternalMetadata(value: unknown): ExternalMetadata | undefined {
  const metadata = record(value)
  return metadata?.source === 'enterprise-architect' &&
    typeof metadata.externalId === 'string' &&
    metadata.externalId.length > 0
    ? { source: 'enterprise-architect', externalId: metadata.externalId }
    : undefined
}

export function inferDocumentVersion(nodes: EntityFlowNode[], edges: DiagramEdge[]): 1 | 2 {
  return nodes.some(
    (node) =>
      (node.data.kind ?? 'ENTITY') !== 'ENTITY' ||
      Boolean(node.data.methods?.length) ||
      Boolean(node.data.abstract),
  ) || edges.some((edge) => Boolean(edge.data?.relationKind))
    ? 2
    : 1
}

export function parseRemoteNode(payload: unknown): EntityFlowNode | null {
  const wrapper = record(payload)
  const node = record(wrapper?.node)
  const position = record(node?.position)
  const data = record(node?.data)
  if (
    !node ||
    !position ||
    !data ||
    typeof node.id !== 'string' ||
    node.id.length === 0 ||
    !isFiniteCoordinate(position.x) ||
    !isFiniteCoordinate(position.y) ||
    typeof data.id !== 'string' ||
    data.id !== node.id ||
    typeof data.name !== 'string' ||
    !Array.isArray(data.attributes)
  ) {
    return null
  }

  const attributes: EntityAttribute[] = []
  for (const item of data.attributes) {
    const attribute = record(item)
    if (
      !attribute ||
      typeof attribute.id !== 'string' ||
      typeof attribute.name !== 'string' ||
      typeof attribute.type !== 'string' ||
      !ATTRIBUTE_TYPES.includes(attribute.type as AttributeType) ||
      typeof attribute.primaryKey !== 'boolean'
    ) {
      return null
    }
    const attributeExternalMetadata = parseExternalMetadata(attribute.externalMetadata)
    attributes.push({
      id: attribute.id,
      name: attribute.name,
      type: attribute.type as AttributeType,
      primaryKey: attribute.primaryKey,
      ...(typeof attribute.nullable === 'boolean' ? { nullable: attribute.nullable } : {}),
      ...(typeof attribute.visibility === 'string' &&
      UML_VISIBILITIES.includes(attribute.visibility as (typeof UML_VISIBILITIES)[number])
        ? { visibility: attribute.visibility as (typeof UML_VISIBILITIES)[number] }
        : {}),
      ...(typeof attribute.static === 'boolean' ? { static: attribute.static } : {}),
      ...(attributeExternalMetadata ? { externalMetadata: attributeExternalMetadata } : {}),
    })
  }

  const association =
    data.association === undefined ? undefined : parseAssociationMetadata(data.association)
  if (data.association !== undefined && !association) return null

  const nodeExternalMetadata = parseExternalMetadata(data.externalMetadata)
  const kind =
    typeof data.kind === 'string' && UML_NODE_KINDS.includes(data.kind as UmlNodeKind)
      ? (data.kind as UmlNodeKind)
      : undefined

  const methods: UmlMethod[] = []
  if (data.methods !== undefined) {
    if (!Array.isArray(data.methods)) return null
    for (const rawMethod of data.methods) {
      const method = record(rawMethod)
      if (
        !method ||
        typeof method.id !== 'string' ||
        typeof method.name !== 'string' ||
        typeof method.returnType !== 'string' ||
        typeof method.visibility !== 'string' ||
        !UML_VISIBILITIES.includes(method.visibility as UmlMethod['visibility']) ||
        !Array.isArray(method.parameters)
      ) {
        return null
      }
      const parameters = method.parameters
        .map((raw) => record(raw))
        .map((parameter) =>
          parameter &&
          typeof parameter.id === 'string' &&
          typeof parameter.name === 'string' &&
          typeof parameter.type === 'string'
            ? { id: parameter.id, name: parameter.name, type: parameter.type }
            : null,
        )
      if (parameters.some((parameter) => parameter === null)) return null
      methods.push({
        id: method.id,
        name: method.name,
        returnType: method.returnType,
        visibility: method.visibility as UmlMethod['visibility'],
        parameters: parameters as UmlMethod['parameters'],
        ...(typeof method.static === 'boolean' ? { static: method.static } : {}),
        ...(typeof method.abstract === 'boolean' ? { abstract: method.abstract } : {}),
      })
    }
  }

  return {
    id: node.id,
    type: 'entity',
    position: { x: position.x, y: position.y },
    data: {
      id: data.id,
      name: data.name,
      attributes,
      ...(kind ? { kind } : {}),
      ...(kind === 'CLASS' && data.generateCrud === true ? { generateCrud: true } : {}),
      ...(data.methods !== undefined ? { methods } : {}),
      ...(Array.isArray(data.literals) && data.literals.every((item) => typeof item === 'string')
        ? { literals: data.literals as string[] }
        : {}),
      ...(typeof data.abstract === 'boolean' ? { abstract: data.abstract } : {}),
      ...(typeof data.width === 'number' && Number.isFinite(data.width) ? { width: data.width } : {}),
      ...(typeof data.height === 'number' && Number.isFinite(data.height) ? { height: data.height } : {}),
      ...(association ? { association } : {}),
      ...(nodeExternalMetadata ? { externalMetadata: nodeExternalMetadata } : {}),
    },
  }
}

export function parseRemoteEdge(payload: unknown): DiagramEdge | null {
  const wrapper = record(payload)
  const edge = record(wrapper?.edge)
  if (
    !edge ||
    typeof edge.id !== 'string' ||
    edge.id.length === 0 ||
    typeof edge.source !== 'string' ||
    typeof edge.target !== 'string' ||
    edge.source === edge.target
  ) {
    return null
  }
  const rawData = record(edge.data)
  try {
    return normalizeRelationshipEdge({
      id: edge.id,
      source: edge.source,
      target: edge.target,
      ...(typeof edge.sourceHandle === 'string' ? { sourceHandle: edge.sourceHandle } : {}),
      ...(typeof edge.targetHandle === 'string' ? { targetHandle: edge.targetHandle } : {}),
      type: 'relationship',
      ...(rawData ? { data: { ...rawData } as DiagramEdge['data'] } : {}),
    })
  } catch {
    return null
  }
}

export function parseRemoteDocument(payload: unknown): DiagramDocument | null {
  const wrapper = record(payload)
  const document = record(wrapper?.document)
  if (
    (document?.version !== 1 && document?.version !== 2) ||
    !Array.isArray(document.nodes) ||
    !Array.isArray(document.edges)
  ) {
    return null
  }
  const nodes = document.nodes.map((node) => parseRemoteNode({ node }))
  const edges = document.edges.map((edge) => parseRemoteEdge({ edge }))
  if (nodes.some((node) => node === null) || edges.some((edge) => edge === null)) {
    return null
  }
  const result: DiagramDocument = {
    version: document.version,
    nodes: nodes as EntityFlowNode[],
    edges: edges as DiagramEdge[],
  }
  try {
    validateDiagramDocument(result)
  } catch {
    return null
  }
  return result
}

function isValidCandidateGraph(nodes: EntityFlowNode[], edges: DiagramEdge[]): boolean {
  try {
    validateDiagramDocument({
      version: inferDocumentVersion(nodes, edges),
      nodes,
      edges,
    })
    return true
  } catch {
    return false
  }
}

export interface LiveDiagramApplyState {
  nodes: EntityFlowNode[]
  edges: DiagramEdge[]
  dirty: boolean
  conflict: boolean
}

export interface LiveDiagramApplyResult {
  applied: boolean
  nodes: EntityFlowNode[]
  edges: DiagramEdge[]
  dragOverlay?: { nodeId: string; position: { x: number; y: number }; dragging: boolean }
}

/**
 * Pure applicator for transient live UML events.
 *
 * Guarantees:
 * - Never mutates a canvas frozen by a conflict.
 * - Never mutates the local persistence buffer when `dirty === true` (so a receiver
 *   never autosaves unconfirmed remote STOMP frames under its own PUT); during local
 *   dirty edits, remote `NODE_MOVED` still returns `dragOverlay` so live dragging
 *   of untouched classes remains visible on screen.
 * - Validates every candidate graph with `validateDiagramDocument` before applying.
 */
export function applyLiveDiagramEvent(
  event: CollaborationEvent,
  state: LiveDiagramApplyState,
): LiveDiagramApplyResult {
  const unchanged: LiveDiagramApplyResult = {
    applied: false,
    nodes: state.nodes,
    edges: state.edges,
  }
  if (state.conflict) return unchanged

  const payload = record(event.payload)

  if (event.type === 'NODE_MOVED') {
    const position = record(payload?.position)
    if (
      typeof payload?.nodeId !== 'string' ||
      !isFiniteCoordinate(position?.x) ||
      !isFiniteCoordinate(position?.y)
    ) {
      return unchanged
    }
    const target = state.nodes.find((node) => node.id === payload.nodeId)
    if (!target || target.dragging) return unchanged
    const nextPosition = { x: position.x, y: position.y }
    const dragging = Boolean(payload.dragging)
    if (state.dirty) {
      return {
        applied: false,
        nodes: state.nodes,
        edges: state.edges,
        dragOverlay: { nodeId: payload.nodeId, position: nextPosition, dragging },
      }
    }
    const nextNodes = state.nodes.map((node) =>
      node.id === payload.nodeId ? { ...node, position: nextPosition } : node,
    )
    return {
      applied: true,
      nodes: nextNodes,
      edges: state.edges,
      dragOverlay: { nodeId: payload.nodeId, position: nextPosition, dragging },
    }
  }

  // While the user has unsaved local edits in flight, wait for confirmed REST reconciliation
  // rather than mixing unconfirmed remote frames into the local PUT payload.
  if (state.dirty) return unchanged

  if (event.type === 'DIAGRAM_BATCH_APPLIED') {
    const document = parseRemoteDocument(event.payload)
    if (!document) return unchanged
    const selectedNodes = new Set(state.nodes.filter((n) => n.selected).map((n) => n.id))
    const selectedEdges = new Set(state.edges.filter((e) => e.selected).map((e) => e.id))
    return {
      applied: true,
      nodes: document.nodes.map((n) => (selectedNodes.has(n.id) ? { ...n, selected: true } : n)),
      edges: document.edges.map((e) => (selectedEdges.has(e.id) ? { ...e, selected: true } : e)),
    }
  }

  if (event.type === 'NODE_CREATED') {
    const node = parseRemoteNode(event.payload)
    if (!node || state.nodes.some((existing) => existing.id === node.id)) return unchanged
    const nextNodes = [...state.nodes, node]
    if (!isValidCandidateGraph(nextNodes, state.edges)) return unchanged
    return { applied: true, nodes: nextNodes, edges: state.edges }
  }

  if (event.type === 'NODE_UPDATED') {
    const node = parseRemoteNode(event.payload)
    if (!node || !state.nodes.some((existing) => existing.id === node.id)) return unchanged
    const nextNodes = state.nodes.map((item) =>
      item.id === node.id ? { ...node, selected: item.selected } : item,
    )
    if (!isValidCandidateGraph(nextNodes, state.edges)) return unchanged
    return { applied: true, nodes: nextNodes, edges: state.edges }
  }

  if (event.type === 'NODE_DELETED' && typeof payload?.nodeId === 'string') {
    const nodeId = payload.nodeId
    if (!state.nodes.some((node) => node.id === nodeId)) return unchanged
    const nextNodes = state.nodes.filter((node) => node.id !== nodeId)
    const nextEdges = state.edges.filter(
      (edge) => edge.source !== nodeId && edge.target !== nodeId,
    )
    if (!isValidCandidateGraph(nextNodes, nextEdges)) return unchanged
    return { applied: true, nodes: nextNodes, edges: nextEdges }
  }

  if (event.type === 'EDGE_CREATED') {
    const edge = parseRemoteEdge(event.payload)
    if (!edge || state.edges.some((existing) => existing.id === edge.id)) return unchanged
    const nextEdges = [...state.edges, edge]
    if (!isValidCandidateGraph(state.nodes, nextEdges)) return unchanged
    return { applied: true, nodes: state.nodes, edges: nextEdges }
  }

  if (event.type === 'EDGE_UPDATED') {
    const edge = parseRemoteEdge(event.payload)
    if (!edge || !state.edges.some((existing) => existing.id === edge.id)) return unchanged
    const nextEdges = state.edges.map((existing) =>
      existing.id === edge.id ? { ...edge, selected: existing.selected } : existing,
    )
    if (!isValidCandidateGraph(state.nodes, nextEdges)) return unchanged
    return { applied: true, nodes: state.nodes, edges: nextEdges }
  }

  if (event.type === 'EDGE_DELETED' && typeof payload?.edgeId === 'string') {
    const edgeId = payload.edgeId
    if (!state.edges.some((edge) => edge.id === edgeId)) return unchanged
    const nextEdges = removeRelationshipEdges(state.edges, new Set([edgeId]))
    if (!isValidCandidateGraph(state.nodes, nextEdges)) return unchanged
    return { applied: true, nodes: state.nodes, edges: nextEdges }
  }

  return unchanged
}
