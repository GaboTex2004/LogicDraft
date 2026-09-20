import { DIAGRAM_CARDINALITIES, type DiagramCardinality, type DiagramEdge } from '../types/diagram.types.ts'

export const CARDINALITY_LABELS: Record<DiagramCardinality, string> = {
  ZERO_ONE: '0..1', ONE_ONE: '1..1', ZERO_MANY: '0..N', ONE_MANY: '1..N',
}
export const LEGACY_CARDINALITIES: Record<string, readonly [DiagramCardinality, DiagramCardinality]> = {
  ONE_TO_ONE: ['ONE_ONE', 'ONE_ONE'], ONE_TO_MANY: ['ONE_ONE', 'ZERO_MANY'],
  MANY_TO_ONE: ['ZERO_MANY', 'ONE_ONE'], MANY_TO_MANY: ['ZERO_MANY', 'ZERO_MANY'],
}
export function isCardinality(value: unknown): value is DiagramCardinality {
  return typeof value === 'string' && DIAGRAM_CARDINALITIES.some(c => c === value)
}
export function cardinalities(data: Record<string, unknown> = {}) {
  if ('sourceCardinality' in data || 'targetCardinality' in data) {
    if (!isCardinality(data.sourceCardinality) || !isCardinality(data.targetCardinality)) throw new Error('Cardinalidad inválida')
    return { sourceCardinality: data.sourceCardinality, targetCardinality: data.targetCardinality }
  }
  const legacy = data.relationshipType
  if (legacy != null && (typeof legacy !== 'string' || !Object.hasOwn(LEGACY_CARDINALITIES, legacy))) throw new Error('Relación legacy inválida')
  const [sourceCardinality, targetCardinality] = typeof legacy === 'string' ? LEGACY_CARDINALITIES[legacy] : ['ONE_ONE', 'ONE_ONE'] as const
  return { sourceCardinality, targetCardinality }
}
export function normalizeRelationshipEdge(edge: DiagramEdge): DiagramEdge {
  const data: NonNullable<DiagramEdge['data']> = { ...edge.data, ...cardinalities(edge.data) }
  delete data.relationshipType
  const normalized = { ...edge, type: 'relationship', data }
  delete normalized.markerEnd
  delete normalized.markerStart
  return normalized
}
export function equivalentRelationship(a: DiagramEdge, b: DiagramEdge): boolean {
  const ac = cardinalities(a.data), bc = cardinalities(b.data)
  const an = typeof a.data?.name === 'string' ? a.data.name.trim().toLocaleLowerCase() : ''
  const bn = typeof b.data?.name === 'string' ? b.data.name.trim().toLocaleLowerCase() : ''
  return an === bn && ((a.source === b.source && a.target === b.target && ac.sourceCardinality === bc.sourceCardinality && ac.targetCardinality === bc.targetCardinality)
    || (a.source === b.target && a.target === b.source && ac.sourceCardinality === bc.targetCardinality && ac.targetCardinality === bc.sourceCardinality))
}

export function removeRelationshipEdges(current: DiagramEdge[], edgeIds: ReadonlySet<string>): DiagramEdge[] {
  return current.filter(edge => !edgeIds.has(edge.id))
}

// Used by both manual edits and remote events. Only local changes publish.
export function commitRelationshipEdge(
  current: DiagramEdge[], edge: DiagramEdge, kind: 'EDGE_CREATED' | 'EDGE_UPDATED', remote: boolean,
  effects: { setEdges: (edges: DiagramEdge[]) => void; markDirty: () => void; publish: (kind: 'EDGE_CREATED' | 'EDGE_UPDATED', edge: DiagramEdge) => void },
): boolean {
  const normalized = normalizeRelationshipEdge(edge)
  const existing = current.find(e => e.id === edge.id)
  if (kind === 'EDGE_CREATED' && existing || kind === 'EDGE_UPDATED' && !existing) return false
  if (!remote && (edge.source === edge.target || current.some(e => e.id !== edge.id && equivalentRelationship(e, normalized)))) return false
  if (existing && JSON.stringify(normalizeRelationshipEdge(existing).data) === JSON.stringify(normalized.data)
      && existing.source === normalized.source && existing.target === normalized.target) return false
  effects.setEdges(kind === 'EDGE_CREATED' ? [...current, normalized] : current.map(e => e.id === edge.id ? { ...normalized, selected: e.selected } : e))
  effects.markDirty()
  if (!remote) effects.publish(kind, normalized)
  return true
}
