import type { DiagramEdge, EntityFlowNode } from '../types/diagram.types.ts'
import { cardinalities } from './relationshipCardinality.ts'

export interface AssociationClassProjection {
  hidden: boolean
  associationNodeId: string
  sourceNodeId: string
  targetNodeId: string
  sourceMultiplicity: string
  targetMultiplicity: string
}

/** Derives one UML association plus its class link from the two persisted structural edges. */
export function associationClassProjection(
  edge: DiagramEdge,
  nodes: readonly EntityFlowNode[],
  edges: readonly DiagramEdge[],
): AssociationClassProjection | null {
  const associationNode = nodes.find(node => node.data.association?.endpoints
    .some(endpoint => endpoint.relationshipId === edge.id))
  const association = associationNode?.data.association
  if (!associationNode || !association) return null
  const source = association.endpoints.find(endpoint => endpoint.role === 'SOURCE')
  const target = association.endpoints.find(endpoint => endpoint.role === 'TARGET')
  if (!source || !target) return null
  const sourceEdge = edges.find(candidate => candidate.id === source.relationshipId)
  const targetEdge = edges.find(candidate => candidate.id === target.relationshipId)
  if (!sourceEdge || !targetEdge) return null
  return {
    hidden: edge.id !== source.relationshipId,
    associationNodeId: associationNode.id,
    sourceNodeId: source.entityId,
    targetNodeId: target.entityId,
    sourceMultiplicity: association.sourceMultiplicity
      ?? cardinalities(targetEdge.data).targetCardinality,
    targetMultiplicity: association.targetMultiplicity
      ?? cardinalities(sourceEdge.data).targetCardinality,
  }
}
