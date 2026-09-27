import { cloneAssociationMetadata } from './associationMetadata.ts'
import { validateDiagramDocument } from './diagramDocumentValidation.ts'
import { cardinalities, normalizeRelationshipEdge } from './relationshipCardinality.ts'
import type {
  DiagramDocument, DiagramEdge, EntityAttribute, EntityFlowNode, ExternalMetadata,
} from '../types/diagram.types.ts'

export type ImportChangeStatus = 'added' | 'modified' | 'unchanged' | 'missing'

export interface AttributeImportChange {
  id: string
  status: ImportChangeStatus
  name: string
  currentAttributeId?: string
  importedAttributeId?: string
  summary: string
}

export interface EntityImportChange {
  id: string
  status: ImportChangeStatus
  name: string
  currentNodeId?: string
  importedNodeId?: string
  associationClass: boolean
  associationChanged: boolean
  attributes: AttributeImportChange[]
}

export interface RelationImportChange {
  id: string
  status: ImportChangeStatus
  name: string
  currentEdgeId?: string
  importedEdgeId?: string
  reversed: boolean
  structural: boolean
}

export interface DiagramImportDiff {
  entities: EntityImportChange[]
  relations: RelationImportChange[]
  warnings: string[]
}

export class DiagramImportError extends Error {}

const fail = (message: string): never => { throw new DiagramImportError(message) }
const key = (value: string) => value.normalize('NFKC').trim().toLocaleLowerCase()
const metadataId = (metadata: ExternalMetadata | undefined) =>
  metadata?.source === 'enterprise-architect' ? metadata.externalId : undefined
const nullable = (attribute: EntityAttribute) => attribute.nullable ?? !attribute.primaryKey
const isAssociation = (node: EntityFlowNode) => node.data.association !== undefined

function sameAttribute(left: EntityAttribute, right: EntityAttribute): boolean {
  return left.type === right.type && left.primaryKey === right.primaryKey && nullable(left) === nullable(right)
}

function attributeSummary(change: ImportChangeStatus, current?: EntityAttribute, imported?: EntityAttribute): string {
  if (change === 'added' && imported) return `+ ${imported.name} : ${imported.type}`
  if (change === 'missing' && current) return `− ${current.name} : ${current.type}`
  if (change === 'modified' && current && imported) return `${current.name}: ${current.type} → ${imported.type}`
  const value = imported ?? current
  return value ? `${value.name} : ${value.type}` : ''
}

function matchAttribute(current: EntityAttribute[], imported: EntityAttribute, used: Set<string>): EntityAttribute | undefined {
  const externalId = metadataId(imported.externalMetadata)
  if (externalId) {
    const byExternal = current.filter(item => !used.has(item.id) && metadataId(item.externalMetadata) === externalId)
    if (byExternal.length === 1) return byExternal[0]
  }
  const byName = current.filter(item => !used.has(item.id) && key(item.name) === key(imported.name))
  return byName.length === 1 ? byName[0] : undefined
}

function attributeChanges(current: EntityAttribute[], imported: EntityAttribute[], entityId: string): AttributeImportChange[] {
  const used = new Set<string>()
  const result: AttributeImportChange[] = []
  for (const incoming of imported) {
    const existing = matchAttribute(current, incoming, used)
    if (!existing) {
      result.push({ id: `attribute:add:${entityId}:${incoming.id}`, status: 'added', name: incoming.name,
        importedAttributeId: incoming.id, summary: attributeSummary('added', undefined, incoming) })
      continue
    }
    used.add(existing.id)
    const status = sameAttribute(existing, incoming) ? 'unchanged' : 'modified'
    result.push({ id: `attribute:${status}:${entityId}:${existing.id}`, status, name: incoming.name,
      currentAttributeId: existing.id, importedAttributeId: incoming.id,
      summary: attributeSummary(status, existing, incoming) })
  }
  for (const existing of current.filter(item => !used.has(item.id))) {
    result.push({ id: `attribute:missing:${entityId}:${existing.id}`, status: 'missing', name: existing.name,
      currentAttributeId: existing.id, summary: attributeSummary('missing', existing) })
  }
  return result
}

function associationSignature(node: EntityFlowNode, nodes: EntityFlowNode[]): string {
  const association = node.data.association
  if (!association) return ''
  return JSON.stringify({
    tableName: key(association.tableName), uniquePair: association.uniquePair,
    endpoints: association.endpoints.map(endpoint => ({ role: endpoint.role,
      entity: key(nodes.find(item => item.id === endpoint.entityId)?.data.name ?? endpoint.entityId),
      foreignKeyName: key(endpoint.foreignKeyName) })),
  })
}

function matchEntity(current: EntityFlowNode[], imported: EntityFlowNode, used: Set<string>): EntityFlowNode | undefined {
  const externalId = metadataId(imported.data.externalMetadata)
  if (externalId) {
    const byExternal = current.filter(item => !used.has(item.id) && metadataId(item.data.externalMetadata) === externalId)
    if (byExternal.length === 1) return byExternal[0]
  }
  const byNameAndKind = current.filter(item => !used.has(item.id) && key(item.data.name) === key(imported.data.name)
    && isAssociation(item) === isAssociation(imported))
  return byNameAndKind.length === 1 ? byNameAndKind[0] : undefined
}

interface EntityMatches {
  changes: EntityImportChange[]
  importedToCurrent: Map<string, string>
}

function diffEntities(current: DiagramDocument, imported: DiagramDocument): EntityMatches {
  const used = new Set<string>()
  const importedToCurrent = new Map<string, string>()
  const changes: EntityImportChange[] = []
  for (const incoming of imported.nodes) {
    const existing = matchEntity(current.nodes, incoming, used)
    if (!existing) {
      changes.push({ id: `entity:add:${incoming.id}`, status: 'added', name: incoming.data.name,
        importedNodeId: incoming.id, associationClass: isAssociation(incoming), associationChanged: false,
        attributes: attributeChanges([], incoming.data.attributes, incoming.id) })
      continue
    }
    used.add(existing.id)
    importedToCurrent.set(incoming.id, existing.id)
    const attributes = attributeChanges(existing.data.attributes, incoming.data.attributes, existing.id)
    const associationChanged = associationSignature(existing, current.nodes) !== associationSignature(incoming, imported.nodes)
    const modified = associationChanged || attributes.some(item => item.status !== 'unchanged')
    changes.push({ id: `entity:${modified ? 'modified' : 'unchanged'}:${existing.id}`,
      status: modified ? 'modified' : 'unchanged', name: existing.data.name,
      currentNodeId: existing.id, importedNodeId: incoming.id,
      associationClass: isAssociation(incoming), associationChanged, attributes })
  }
  for (const existing of current.nodes.filter(item => !used.has(item.id))) {
    changes.push({ id: `entity:missing:${existing.id}`, status: 'missing', name: existing.data.name,
      currentNodeId: existing.id, associationClass: isAssociation(existing), associationChanged: false,
      attributes: existing.data.attributes.map(attribute => ({ id: `attribute:missing:${existing.id}:${attribute.id}`,
        status: 'missing', name: attribute.name, currentAttributeId: attribute.id,
        summary: attributeSummary('missing', attribute) })) })
  }
  return { changes, importedToCurrent }
}

function structuralEdge(edge: DiagramEdge, nodes: EntityFlowNode[]): boolean {
  return nodes.some(node => node.data.association?.endpoints.some(endpoint => endpoint.relationshipId === edge.id))
}

function edgeName(edge: DiagramEdge, nodes: EntityFlowNode[]): string {
  const source = nodes.find(node => node.id === edge.source)?.data.name ?? edge.source
  const target = nodes.find(node => node.id === edge.target)?.data.name ?? edge.target
  return edge.data?.name?.trim() || `${source} – ${target}`
}

function sameRelationData(current: DiagramEdge, imported: DiagramEdge, reversed: boolean): boolean {
  const currentCards = cardinalities(current.data)
  const importedCards = cardinalities(imported.data)
  const source = reversed ? importedCards.targetCardinality : importedCards.sourceCardinality
  const target = reversed ? importedCards.sourceCardinality : importedCards.targetCardinality
  return currentCards.sourceCardinality === source && currentCards.targetCardinality === target
    && key(current.data?.name ?? '') === key(imported.data?.name ?? '')
    && key(current.data?.joinTableName ?? '') === key(imported.data?.joinTableName ?? '')
}

function relationCandidate(current: DiagramDocument, imported: DiagramDocument, incoming: DiagramEdge,
  importedToCurrent: Map<string, string>, used: Set<string>): { edge: DiagramEdge; reversed: boolean } | undefined {
  const externalId = metadataId(incoming.data?.externalMetadata)
  if (externalId) {
    const byExternal = current.edges.filter(edge => !used.has(edge.id)
      && metadataId(edge.data?.externalMetadata) === externalId)
    if (byExternal.length === 1) return { edge: byExternal[0], reversed: false }
  }
  const source = importedToCurrent.get(incoming.source)
  const target = importedToCurrent.get(incoming.target)
  if (!source || !target) return undefined
  const incomingStructural = structuralEdge(incoming, imported.nodes)
  const candidates = current.edges.flatMap(edge => {
    if (used.has(edge.id) || structuralEdge(edge, current.nodes) !== incomingStructural) return []
    const direct = edge.source === source && edge.target === target
    const reversed = edge.source === target && edge.target === source
    if (!direct && !reversed) return []
    if (key(edge.data?.name ?? '') !== key(incoming.data?.name ?? '')) return []
    return [{ edge, reversed }]
  })
  return candidates.length === 1 ? candidates[0] : undefined
}

export function computeDiagramImportDiff(current: DiagramDocument, imported: DiagramDocument,
  warnings: string[] = []): DiagramImportDiff {
  const entities = diffEntities(current, imported)
  const used = new Set<string>()
  const relations: RelationImportChange[] = []
  for (const incoming of imported.edges) {
    const match = relationCandidate(current, imported, incoming, entities.importedToCurrent, used)
    if (!match) {
      relations.push({ id: `relation:add:${incoming.id}`, status: 'added', name: edgeName(incoming, imported.nodes),
        importedEdgeId: incoming.id, reversed: false, structural: structuralEdge(incoming, imported.nodes) })
      continue
    }
    used.add(match.edge.id)
    const status = sameRelationData(match.edge, incoming, match.reversed) ? 'unchanged' : 'modified'
    relations.push({ id: `relation:${status}:${match.edge.id}`, status, name: edgeName(match.edge, current.nodes),
      currentEdgeId: match.edge.id, importedEdgeId: incoming.id, reversed: match.reversed,
      structural: structuralEdge(incoming, imported.nodes) })
  }
  for (const edge of current.edges.filter(item => !used.has(item.id))) {
    relations.push({ id: `relation:missing:${edge.id}`, status: 'missing', name: edgeName(edge, current.nodes),
      currentEdgeId: edge.id, reversed: false, structural: structuralEdge(edge, current.nodes) })
  }
  return { entities: entities.changes, relations, warnings: [...warnings] }
}

export function defaultImportSelection(diff: DiagramImportDiff): Set<string> {
  const selected = new Set<string>()
  for (const entity of diff.entities) {
    if (entity.status === 'added' || entity.status === 'modified') selected.add(entity.id)
    for (const attribute of entity.attributes) {
      if (attribute.status === 'added' || attribute.status === 'modified') selected.add(attribute.id)
    }
  }
  for (const relation of diff.relations) {
    if (relation.status === 'added' || relation.status === 'modified') selected.add(relation.id)
  }
  return selected
}

function freePosition(position: EntityFlowNode['position'], nodes: EntityFlowNode[]): EntityFlowNode['position'] {
  let candidate = { ...position }
  for (let attempt = 0; attempt < 30; attempt += 1) {
    if (!nodes.some(node => Math.abs(node.position.x - candidate.x) < 180
      && Math.abs(node.position.y - candidate.y) < 120)) return candidate
    candidate = { x: position.x + (attempt % 3) * 45, y: position.y + (attempt + 1) * 70 }
  }
  return candidate
}

function uniqueId(preferred: string, used: Set<string>, prefix: string, createId: () => string): string {
  if (!used.has(preferred)) { used.add(preferred); return preferred }
  let generated = `${prefix}-${createId()}`
  while (used.has(generated)) generated = `${prefix}-${createId()}`
  used.add(generated)
  return generated
}

function importedAttribute(node: EntityFlowNode, id: string | undefined): EntityAttribute {
  return node.data.attributes.find(attribute => attribute.id === id)
    ?? fail('El atributo importado ya no está disponible.')
}

export function applyDiagramImport(current: DiagramDocument, imported: DiagramDocument,
  selected: ReadonlySet<string>, createId: () => string = () => crypto.randomUUID()): DiagramDocument {
  const diff = computeDiagramImportDiff(current, imported)
  const importedNodes = new Map(imported.nodes.map(node => [node.id, node]))
  const importedEdges = new Map(imported.edges.map(edge => [edge.id, edge]))
  const usedIds = new Set([...current.nodes.map(node => node.id), ...current.edges.map(edge => edge.id),
    ...current.nodes.flatMap(node => node.data.attributes.map(attribute => attribute.id))])
  const removedNodeIds = new Set(diff.entities.filter(change => change.status === 'missing' && selected.has(change.id))
    .flatMap(change => change.currentNodeId ?? []))
  const removedEdgeIds = new Set(diff.relations.filter(change => change.status === 'missing' && selected.has(change.id))
    .flatMap(change => change.currentEdgeId ?? []))
  let nodes = current.nodes.filter(node => !removedNodeIds.has(node.id)).map(node => ({ ...node,
    data: { ...node.data, attributes: node.data.attributes.map(attribute => ({ ...attribute })) } }))
  let edges = current.edges.filter(edge => !removedEdgeIds.has(edge.id) && !removedNodeIds.has(edge.source)
    && !removedNodeIds.has(edge.target)).map(edge => normalizeRelationshipEdge({ ...edge, data: { ...edge.data } }))
  const nodeMap = new Map<string, string>()
  const edgeMap = new Map<string, string>()

  for (const change of diff.entities) {
    if (!change.importedNodeId) continue
    const incoming = importedNodes.get(change.importedNodeId)!
    if (change.currentNodeId) {
      const index = nodes.findIndex(node => node.id === change.currentNodeId)
      if (index < 0) continue
      nodeMap.set(incoming.id, change.currentNodeId)
      const existing = nodes[index]
      let attributes = [...existing.data.attributes]
      for (const attributeChange of change.attributes) {
        if (attributeChange.currentAttributeId && attributeChange.importedAttributeId) {
          const importedValue = importedAttribute(incoming, attributeChange.importedAttributeId)
          attributes = attributes.map(attribute => attribute.id === attributeChange.currentAttributeId
            ? { ...(selected.has(attributeChange.id) && attributeChange.status === 'modified'
              ? { ...importedValue, id: attribute.id }
              : attribute), ...(importedValue.externalMetadata ? { externalMetadata: { ...importedValue.externalMetadata } } : {}) }
            : attribute)
        } else if (attributeChange.status === 'added' && selected.has(attributeChange.id)) {
          const importedValue = importedAttribute(incoming, attributeChange.importedAttributeId)
          attributes.push({ ...importedValue,
            id: uniqueId(importedValue.id, usedIds, 'attribute', createId) })
        } else if (attributeChange.status === 'missing' && selected.has(attributeChange.id)) {
          attributes = attributes.filter(attribute => attribute.id !== attributeChange.currentAttributeId)
        }
      }
      nodes[index] = { ...existing, data: { ...existing.data, attributes,
        ...(incoming.data.externalMetadata ? { externalMetadata: { ...incoming.data.externalMetadata } } : {}) } }
    } else if (change.status === 'added' && selected.has(change.id)) {
      const id = uniqueId(incoming.id, usedIds, 'entity', createId)
      nodeMap.set(incoming.id, id)
      const attributes = incoming.data.attributes.map(attribute => ({ ...attribute,
        id: uniqueId(attribute.id, usedIds, 'attribute', createId) }))
      nodes.push({ ...incoming, id, selected: false, position: freePosition(incoming.position, nodes),
        data: { ...incoming.data, id, attributes, association: undefined } })
    }
  }

  const selectedAssociationIds = new Set(diff.entities.filter(change => change.associationClass
    && change.importedNodeId && selected.has(change.id)).map(change => change.importedNodeId!))
  for (const change of diff.relations) {
    if (!change.importedEdgeId) continue
    const incoming = importedEdges.get(change.importedEdgeId)!
    const requiredStructural = change.structural && selectedAssociationIds.has(incoming.target)
    if (change.currentEdgeId) {
      const existing = edges.find(edge => edge.id === change.currentEdgeId)
      if (!existing) continue
      edgeMap.set(incoming.id, existing.id)
      const external = incoming.data?.externalMetadata
      if (selected.has(change.id) && change.status === 'modified') {
        const data = { ...incoming.data }
        if (change.reversed) {
          const cards = cardinalities(data)
          data.sourceCardinality = cards.targetCardinality
          data.targetCardinality = cards.sourceCardinality
        }
        edges = edges.map(edge => edge.id === existing.id
          ? normalizeRelationshipEdge({ ...existing, data: { ...data,
            ...(external ? { externalMetadata: { ...external } } : {}) } }) : edge)
      } else if (external) {
        edges = edges.map(edge => edge.id === existing.id ? { ...edge,
          data: { ...edge.data, externalMetadata: { ...external } } } : edge)
      }
    } else if (change.status === 'added' && (selected.has(change.id) || requiredStructural)) {
      const source = nodeMap.get(incoming.source)
      const target = nodeMap.get(incoming.target)
      if (!source || !target) fail(`No se puede agregar ${change.name} sin importar sus entidades.`)
      const mappedSource = source ?? fail(`Falta la entidad origen de ${change.name}.`)
      const mappedTarget = target ?? fail(`Falta la entidad destino de ${change.name}.`)
      const id = uniqueId(incoming.id, usedIds, 'relationship', createId)
      edgeMap.set(incoming.id, id)
      edges.push(normalizeRelationshipEdge({ ...incoming, id, source: mappedSource, target: mappedTarget, selected: false,
        data: { ...incoming.data } }))
    }
  }

  for (const change of diff.entities) {
    if (!change.importedNodeId || !nodeMap.has(change.importedNodeId)) continue
    const incoming = importedNodes.get(change.importedNodeId)!
    if (!incoming.data.association) continue
    const shouldApplyAssociation = change.currentNodeId
      ? change.associationChanged && selected.has(change.id)
      : selected.has(change.id)
    if (!shouldApplyAssociation) continue
    const nodeId = nodeMap.get(incoming.id)!
    const association = cloneAssociationMetadata(incoming.data.association)
    association.endpoints = association.endpoints.map(endpoint => {
      const entityId = nodeMap.get(endpoint.entityId)
      const relationshipId = edgeMap.get(endpoint.relationshipId)
      if (!entityId || !relationshipId) fail(`La estructura de ${incoming.data.name} está incompleta.`)
      return { ...endpoint, entityId, relationshipId }
    }) as typeof association.endpoints
    nodes = nodes.map(node => node.id === nodeId ? { ...node,
      data: { ...node.data, association } } : node)
  }
  const document: DiagramDocument = { version: 1, nodes, edges }
  validateDiagramDocument(document)
  return document
}
