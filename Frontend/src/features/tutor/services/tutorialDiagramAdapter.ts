import type { DiagramEdge, EntityFlowNode } from '../../diagram/types/diagram.types.ts'
import { cardinalities } from '../../diagram/services/relationshipCardinality.ts'
import type { ObservedTutorialItem } from '../types/tutorialRuntime.types.ts'
import type { TutorAction } from './tutorialRuntimeStore.ts'

export function tutorialDiagramItems(workspaceId: number, projectId: number, nodes: readonly EntityFlowNode[], edges: readonly DiagramEdge[], locked: (id: string) => boolean): ObservedTutorialItem[] {
  const scope = { workspaceId, projectId }
  const structural = new Set(nodes.flatMap(node => node.data.association?.endpoints.map(endpoint => endpoint.relationshipId) ?? []))
  return [
    ...nodes.flatMap(node => [
      { ...scope, kind: 'node' as const, id: node.id, nodeKind: node.data.kind ?? 'ENTITY', locked: locked(node.id), fields: { name: node.data.name } },
      ...node.data.attributes.map(attribute => ({ ...scope, kind: 'attribute' as const, id: attribute.id, parentId: node.id, locked: locked(node.id),
        structural: Boolean(node.data.association && attribute.primaryKey), fields: { name: attribute.name, type: attribute.type, primaryKey: attribute.primaryKey } })),
    ]),
    ...edges.map(edge => ({ ...scope, kind: 'relationship' as const, id: edge.id, sourceId: edge.source, targetId: edge.target,
      relationKind: typeof edge.data?.relationKind === 'string' ? edge.data.relationKind : 'ASSOCIATION', structural: structural.has(edge.id),
      locked: locked(edge.id) || locked(edge.source) || locked(edge.target), fields: { name: typeof edge.data?.name === 'string' ? edge.data.name : '', ...cardinalities(edge.data) } })),
  ]
}

/** Call only inside an accepted local edit handler, never from a graph subscription. */
export function localNodeEvidence(before: EntityFlowNode, after: EntityFlowNode): TutorAction[] {
  const actions: TutorAction[] = []
  if (before.data.name !== after.data.name) actions.push({ action: 'changed', entity: 'node', entityId: after.id, field: 'name', value: after.data.name })
  for (const attribute of after.data.attributes) {
    const previous = before.data.attributes.find(item => item.id === attribute.id)
    if (!previous) actions.push({ action: 'created', entity: 'attribute', entityId: attribute.id, parentId: after.id })
    else for (const field of ['name', 'type', 'primaryKey'] as const) {
      if (previous[field] !== attribute[field]) actions.push({ action: 'changed', entity: 'attribute', entityId: attribute.id, field, value: attribute[field] })
    }
  }
  return actions
}
