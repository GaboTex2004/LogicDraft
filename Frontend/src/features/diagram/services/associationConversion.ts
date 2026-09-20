import type { DiagramDocument, DiagramEdge, EntityFlowNode } from '../types/diagram.types.ts'
import { deriveJoinTable, sqlName, technicalName } from './derivedJoinTable.ts'
import { cardinalities, normalizeRelationshipEdge } from './relationshipCardinality.ts'

export class AssociationConversionError extends Error {}

export interface AssociationConversionResult {
  document: DiagramDocument
  associationNodeId: string
}

export interface AssociationOwnAttribute {
  name: string
  type: EntityFlowNode['data']['attributes'][number]['type']
  nullable: boolean
}

function fail(message: string): never {
  throw new AssociationConversionError(message)
}

function many(value: string): boolean {
  return value === 'ZERO_MANY' || value === 'ONE_MANY'
}

function structuralRelationship(edge: DiagramEdge, endpointId: string, associationId: string): boolean {
  const cards = cardinalities(edge.data)
  return edge.source === endpointId && edge.target === associationId
    && cards.sourceCardinality === 'ONE_ONE' && many(cards.targetCardinality)
}

/** Validates only the optional association extension, leaving legacy diagrams unchanged. */
export function validateAssociationDocument(nodes: readonly EntityFlowNode[], edges: readonly DiagramEdge[]): void {
  if (!nodes.some(node => node.data.association)) return
  const nodeIds = new Set(nodes.map(node => node.id))
  if (nodeIds.size !== nodes.length) fail('El diagrama contiene entidades duplicadas.')
  const edgeIds = new Set(edges.map(edge => edge.id))
  if (edgeIds.size !== edges.length) fail('El diagrama contiene relaciones duplicadas.')
  const physicalTables = new Set<string>()
  for (const node of nodes) {
    const table = node.data.association?.tableName ?? sqlName(technicalName(node.data.name))
    const key = table.toLocaleLowerCase()
    if (physicalTables.has(key)) fail(`La tabla fisica '${table}' ya esta en uso.`)
    physicalTables.add(key)
  }
  for (const edge of edges) {
    const source = nodes.find(node => node.id === edge.source)?.data
    const target = nodes.find(node => node.id === edge.target)?.data
    const table = deriveJoinTable(edge, source, target)
    if (!table) continue
    const key = table.name.toLocaleLowerCase()
    if (physicalTables.has(key)) fail(`La tabla fisica '${table.name}' ya esta en uso.`)
    physicalTables.add(key)
  }

  const usedStructuralEdges = new Set<string>()
  for (const node of nodes) {
    const association = node.data.association
    if (!association) continue
    if (association.endpoints.length !== 2 || association.endpoints[0].role === association.endpoints[1].role) {
      fail(`La entidad asociativa ${node.data.name} no tiene dos extremos validos.`)
    }
    const primaryKeys = node.data.attributes.filter(attribute => attribute.primaryKey)
    if (primaryKeys.length !== 1 || !['INTEGER', 'BIGINT'].includes(primaryKeys[0].type) || primaryKeys[0].nullable === true) {
      fail(`La entidad asociativa ${node.data.name} requiere una PK INTEGER o BIGINT generada.`)
    }
    const endpointIds = new Set<string>()
    const foreignKeys = new Set<string>()
    for (const endpoint of association.endpoints) {
      if (endpoint.entityId === node.id || !nodeIds.has(endpoint.entityId) || endpointIds.has(endpoint.entityId)) {
        fail(`La entidad asociativa ${node.data.name} referencia extremos invalidos.`)
      }
      endpointIds.add(endpoint.entityId)
      if (foreignKeys.has(endpoint.foreignKeyName.toLocaleLowerCase())) fail('Las claves foraneas estructurales deben ser distintas.')
      foreignKeys.add(endpoint.foreignKeyName.toLocaleLowerCase())
      if (usedStructuralEdges.has(endpoint.relationshipId)) fail('Una relacion estructural no puede pertenecer a dos asociaciones.')
      const edge = edges.find(candidate => candidate.id === endpoint.relationshipId)
      if (!edge || !structuralRelationship(edge, endpoint.entityId, node.id)) {
        fail(`La relacion estructural '${endpoint.relationshipId}' no es valida.`)
      }
      usedStructuralEdges.add(endpoint.relationshipId)
    }
    for (const attribute of node.data.attributes) {
      if (foreignKeys.has(sqlName(technicalName(attribute.name)).toLocaleLowerCase())) {
        fail(`El atributo ${attribute.name} colisiona con una clave foranea estructural.`)
      }
    }
    if (edges.some(edge => {
      if (usedStructuralEdges.has(edge.id)) return false
      const cards = cardinalities(edge.data)
      return endpointIds.has(edge.source) && endpointIds.has(edge.target)
        && many(cards.sourceCardinality) && many(cards.targetCardinality)
    })) fail(`La entidad asociativa ${node.data.name} no puede coexistir con la relacion N:M original.`)
  }
}

export function structuralRelationshipIds(nodes: readonly EntityFlowNode[]): ReadonlySet<string> {
  return new Set(nodes.flatMap(node => node.data.association?.endpoints.map(endpoint => endpoint.relationshipId) ?? []))
}

export function associationProtectedNodeIds(nodes: readonly EntityFlowNode[]): ReadonlySet<string> {
  return new Set(nodes.flatMap(node => node.data.association
    ? [node.id, ...node.data.association.endpoints.map(endpoint => endpoint.entityId)] : []))
}

export function convertManyToManyAssociation(
  nodes: readonly EntityFlowNode[],
  edges: readonly DiagramEdge[],
  relationshipId: string,
  requestedName: string,
  idFactory: () => string = () => crypto.randomUUID(),
  ownAttributes: readonly AssociationOwnAttribute[] = [],
): AssociationConversionResult {
  const entityName = requestedName.trim()
  if (!entityName || entityName.length > 200) fail('Indica un nombre valido para la entidad asociativa.')
  const original = edges.find(edge => edge.id === relationshipId)
  if (!original) fail('La relacion N:M seleccionada ya no existe.')
  const cards = cardinalities(original.data)
  if (!many(cards.sourceCardinality) || !many(cards.targetCardinality)) fail('La relacion seleccionada no es N:M.')
  const source = nodes.find(node => node.id === original.source)
  const target = nodes.find(node => node.id === original.target)
  if (!source || !target || source.id === target.id) fail('La relacion N:M tiene extremos invalidos.')
  // The editor supports incomplete models. Endpoint PKs are required when the
  // ApplicationSchema is exported, but they are not needed to perform this
  // structural transformation or to preserve its association metadata.
  const entityTechnicalName = technicalName(entityName)
  if (!entityTechnicalName || nodes.some(node => technicalName(node.data.name).toLocaleLowerCase() === entityTechnicalName.toLocaleLowerCase())) {
    fail(`Ya existe una entidad con el nombre '${entityName}'.`)
  }
  const joinTable = deriveJoinTable(original, source.data, target.data)
  if (!joinTable || joinTable.name.length > 55) fail('El nombre fisico de la tabla intermedia no es valido.')
  const attributeNames = new Set(['id'])
  for (const attribute of ownAttributes) {
    const attributeName = attribute.name.trim()
    const normalized = technicalName(attributeName).toLocaleLowerCase()
    if (!attributeName || attributeName !== attribute.name || !normalized || attributeNames.has(normalized)) {
      fail(`El atributo propio '${attribute.name}' no tiene un nombre unico valido.`)
    }
    attributeNames.add(normalized)
  }

  const associationId = `entity-${idFactory()}`
  const primaryKeyId = `attribute-${idFactory()}`
  const sourceRelationshipId = `relationship-${idFactory()}`
  const targetRelationshipId = `relationship-${idFactory()}`
  const associationNode: EntityFlowNode = {
    id: associationId,
    type: 'entity',
    position: {
      x: (source.position.x + target.position.x) / 2,
      y: (source.position.y + target.position.y) / 2 + 140,
    },
    selected: true,
    data: {
      id: associationId,
      name: entityName,
      attributes: [
        { id: primaryKeyId, name: 'id', type: 'INTEGER', primaryKey: true, nullable: false },
        ...ownAttributes.map(attribute => ({
          id: `attribute-${idFactory()}`,
          name: attribute.name,
          type: attribute.type,
          primaryKey: false,
          nullable: attribute.nullable,
        })),
      ],
      association: {
        kind: 'MANY_TO_MANY_ASSOCIATION',
        tableName: joinTable.name,
        uniquePair: true,
        endpoints: [
          { role: 'SOURCE', entityId: source.id, relationshipId: sourceRelationshipId, foreignKeyName: joinTable.columns[0].name },
          { role: 'TARGET', entityId: target.id, relationshipId: targetRelationshipId, foreignKeyName: joinTable.columns[1].name },
        ],
      },
    },
  }
  const structuralEdges: DiagramEdge[] = [
    normalizeRelationshipEdge({ id: sourceRelationshipId, source: source.id, target: associationId,
      data: { sourceCardinality: 'ONE_ONE', targetCardinality: cards.targetCardinality } }),
    normalizeRelationshipEdge({ id: targetRelationshipId, source: target.id, target: associationId,
      data: { sourceCardinality: 'ONE_ONE', targetCardinality: cards.sourceCardinality } }),
  ]
  const nextNodes = [...nodes.map(node => ({ ...node, selected: false })), associationNode]
  const nextEdges = [...edges.filter(edge => edge.id !== relationshipId).map(edge => ({ ...edge, selected: false })), ...structuralEdges]
  validateAssociationDocument(nextNodes, nextEdges)
  return { document: { version: 1, nodes: nextNodes, edges: nextEdges }, associationNodeId: associationId }
}
