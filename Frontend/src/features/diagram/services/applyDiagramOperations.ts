import type { AttributeType, DiagramEdge, EntityAttribute, EntityFlowNode } from '../types/diagram.types'
import type { AiAttribute, AiDataType, AiRelationshipRef, DiagramAiOperation } from '../types/diagramAi.types'
import { equivalentRelationship, isCardinality, normalizeRelationshipEdge } from './relationshipCardinality.ts'
import { convertManyToManyAssociation, structuralRelationshipIds } from './associationConversion.ts'
import { validateDiagramDocument } from './diagramDocumentValidation.ts'

export const AI_TYPE_MAP: Record<AiDataType, AttributeType> = {
  String: 'VARCHAR', Long: 'BIGINT', Integer: 'INTEGER', Double: 'DECIMAL',
  Boolean: 'BOOLEAN', Date: 'DATE', DateTime: 'TIMESTAMP',
}
const key = (name: string) => name.toLowerCase()
export class DiagramOperationError extends Error {}
const fail = (message = 'La IA devolvió operaciones inválidas'): never => { throw new DiagramOperationError(message) }

function object(value: unknown): Record<string, unknown> {
  if (!value || typeof value !== 'object' || Array.isArray(value)) return fail()
  return value as Record<string, unknown>
}
function name(value: unknown): string {
  if (typeof value !== 'string' || !value.trim() || value !== value.trim() || value.length > 100) return fail()
  return value
}
function exactKeys(value: Record<string, unknown>, required: readonly string[], optional: readonly string[] = []): void {
  if (required.some(key => !(key in value)) || Object.keys(value).some(key => !required.includes(key) && !optional.includes(key))) fail()
}
function dataType(value: unknown): AiDataType {
  if (typeof value !== 'string' || !Object.hasOwn(AI_TYPE_MAP, value)) return fail()
  return value as AiDataType
}
function boolean(value: unknown): boolean {
  if (typeof value !== 'boolean') return fail()
  return value
}
function attribute(value: unknown): AiAttribute {
  const a = object(value)
  exactKeys(a, ['name', 'dataType', 'primaryKey', 'nullable'])
  return { name: name(a.name), dataType: dataType(a.dataType), primaryKey: boolean(a.primaryKey), nullable: boolean(a.nullable) }
}
function relationshipRef(value: unknown): AiRelationshipRef {
  const relationship = object(value)
  exactKeys(relationship, ['sourceEntity', 'targetEntity'], ['name'])
  return { sourceEntity: name(relationship.sourceEntity), targetEntity: name(relationship.targetEntity),
    ...(relationship.name == null ? {} : { name: name(relationship.name) }) }
}
export function parseDiagramAiResponse(value: unknown): DiagramAiOperation[] {
  const raw = object(value).operations
  if (!Array.isArray(raw) || raw.length > 50) return fail()
  return raw.map((item): DiagramAiOperation => {
    const op = object(item)
    switch (op.type) {
      case 'ADD_ENTITY': {
        exactKeys(op, ['type', 'entity'])
        const entity = object(op.entity)
        exactKeys(entity, ['name', 'attributes'])
        if (!Array.isArray(entity.attributes) || entity.attributes.length > 100) return fail()
        return { type: op.type, entity: { name: name(entity.name), attributes: entity.attributes.map(attribute) } }
      }
      case 'DELETE_ENTITY':
        exactKeys(op, ['type', 'entityName'])
        return { type: op.type, entityName: name(op.entityName) }
      case 'RENAME_ENTITY':
        exactKeys(op, ['type', 'entityName', 'newName'])
        return { type: op.type, entityName: name(op.entityName), newName: name(op.newName) }
      case 'ADD_ATTRIBUTE':
        exactKeys(op, ['type', 'entityName', 'attribute'])
        return { type: op.type, entityName: name(op.entityName), attribute: attribute(op.attribute) }
      case 'DELETE_ATTRIBUTE':
        exactKeys(op, ['type', 'entityName', 'attributeName'])
        return { type: op.type, entityName: name(op.entityName), attributeName: name(op.attributeName) }
      case 'RENAME_ATTRIBUTE':
        exactKeys(op, ['type', 'entityName', 'attributeName', 'newName'])
        return { type: op.type, entityName: name(op.entityName), attributeName: name(op.attributeName), newName: name(op.newName) }
      case 'CHANGE_ATTRIBUTE_TYPE':
        exactKeys(op, ['type', 'entityName', 'attributeName', 'dataType'])
        return { type: op.type, entityName: name(op.entityName), attributeName: name(op.attributeName), dataType: dataType(op.dataType) }
      case 'SET_ATTRIBUTE_PRIMARY_KEY':
      case 'SET_ATTRIBUTE_NULLABLE':
        exactKeys(op, ['type', 'entityName', 'attributeName', 'value'])
        return { type: op.type, entityName: name(op.entityName), attributeName: name(op.attributeName), value: boolean(op.value) }
      case 'ADD_RELATIONSHIP': {
        exactKeys(op, ['type', 'relationship'])
        const r = object(op.relationship)
        if (!isCardinality(r.sourceCardinality) || !isCardinality(r.targetCardinality)
          || Object.keys(r).some(k => !['sourceEntity', 'targetEntity', 'sourceCardinality', 'targetCardinality', 'name', 'joinTableName'].includes(k))) return fail()
        return { type: op.type, relationship: {
          sourceEntity: name(r.sourceEntity), targetEntity: name(r.targetEntity),
          sourceCardinality: r.sourceCardinality, targetCardinality: r.targetCardinality,
          ...(r.name == null ? {} : { name: name(r.name) }),
          ...(r.joinTableName == null ? {} : { joinTableName: name(r.joinTableName) }),
        } }
      }
      case 'DELETE_RELATIONSHIP':
        exactKeys(op, ['type', 'relationship'])
        return { type: op.type, relationship: relationshipRef(op.relationship) }
      case 'UPDATE_RELATIONSHIP': {
        exactKeys(op, ['type', 'relationship'])
        const rawRelationship = object(op.relationship)
        exactKeys(rawRelationship, ['sourceEntity', 'targetEntity', 'sourceCardinality', 'targetCardinality'], ['name'])
        if (!isCardinality(rawRelationship.sourceCardinality) || !isCardinality(rawRelationship.targetCardinality)) return fail()
        return { type: op.type, relationship: {
          sourceEntity: name(rawRelationship.sourceEntity), targetEntity: name(rawRelationship.targetEntity),
          sourceCardinality: rawRelationship.sourceCardinality, targetCardinality: rawRelationship.targetCardinality,
          ...(rawRelationship.name == null ? {} : { name: name(rawRelationship.name) }),
        } }
      }
      case 'CREATE_ASSOCIATION': {
        exactKeys(op, ['type', 'association'])
        const association = object(op.association)
        exactKeys(association, ['sourceEntity', 'targetEntity', 'associationEntityName', 'attributes'])
        if (!Array.isArray(association.attributes) || association.attributes.length > 100) return fail()
        const attributes = association.attributes.map(attribute)
        if (attributes.some(item => item.primaryKey)) return fail('La entidad asociativa usa una PK generada')
        return { type: op.type, association: { sourceEntity: name(association.sourceEntity),
          targetEntity: name(association.targetEntity), associationEntityName: name(association.associationEntityName), attributes } }
      }
      case 'DELETE_ASSOCIATION':
        exactKeys(op, ['type', 'associationEntityName'])
        return { type: op.type, associationEntityName: name(op.associationEntityName) }
      case 'CONVERT_MANY_TO_MANY_ASSOCIATION': {
        const conversion = object(op.conversion)
        if (Object.keys(op).some(key => !['type', 'conversion'].includes(key))
          || Object.keys(conversion).some(key => !['relationshipId', 'sourceEntity', 'targetEntity', 'associationEntityName', 'attributes'].includes(key))
          || !Array.isArray(conversion.attributes) || conversion.attributes.length > 100) return fail()
        const attributes = conversion.attributes.map(attribute)
        if (attributes.some(item => item.primaryKey)) return fail('Los atributos propios no pueden reemplazar la PK generada')
        return { type: op.type, conversion: {
          relationshipId: name(conversion.relationshipId),
          sourceEntity: name(conversion.sourceEntity),
          targetEntity: name(conversion.targetEntity),
          associationEntityName: name(conversion.associationEntityName),
          attributes,
        } }
      }
      default: return fail('La IA devolvió una operación no soportada')
    }
  })
}

/** Parses an HTTP response and validates its complete preview without mutating the loaded document. */
export function prepareDiagramAiProposal(
  response: unknown,
  currentNodes: EntityFlowNode[],
  currentEdges: DiagramEdge[],
) {
  const operations = parseDiagramAiResponse(response)
  const preview = applyDiagramOperations(currentNodes, currentEdges, operations)
  return { operations, preview }
}

export type DiagramAiEvent =
  | { type: 'NODE_CREATED' | 'NODE_UPDATED'; node: EntityFlowNode }
  | { type: 'EDGE_CREATED'; edge: DiagramEdge }
  | { type: 'DIAGRAM_BATCH_APPLIED'; document: { version: 1; nodes: EntityFlowNode[]; edges: DiagramEdge[] } }

export function applyDiagramOperations(
  currentNodes: EntityFlowNode[], currentEdges: DiagramEdge[], operations: unknown,
  createId: () => string = () => crypto.randomUUID(),
) {
  const validated = parseDiagramAiResponse({ operations })
  let nodes = currentNodes.map(node => ({ ...node, data: { ...node.data,
    attributes: node.data.attributes.map(attribute => ({ ...attribute })) } }))
  let edges = currentEdges.map(edge => normalizeRelationshipEdge({ ...edge, data: { ...edge.data } }))
  const usedIds = new Set([...nodes.map(n => n.id), ...edges.map(e => e.id), ...nodes.flatMap(n => n.data.attributes.map(a => a.id))])
  const uniqueId = (prefix: string) => {
    const id = `${prefix}-${createId()}`
    if (usedIds.has(id)) return fail('No se pudo generar un identificador único')
    usedIds.add(id)
    return id
  }
  const find = (entityName: string) => {
    const matches = nodes.filter(n => key(n.data.name) === key(entityName))
    if (matches.length !== 1) return fail(`La entidad "${entityName}" no existe o su nombre es ambiguo`)
    return matches[0]
  }
  const findAttribute = (entityName: string, attributeName: string) => {
    const node = find(entityName)
    const matches = node.data.attributes.filter(item => key(item.name) === key(attributeName))
    if (matches.length !== 1) fail(`El atributo "${attributeName}" no existe o su nombre es ambiguo en "${node.data.name}"`)
    return { node, attribute: matches[0] }
  }
  const findRelationship = (reference: AiRelationshipRef) => {
    const source = find(reference.sourceEntity)
    const target = find(reference.targetEntity)
    const structural = structuralRelationshipIds(nodes)
    const expectedName = reference.name == null ? undefined : key(reference.name)
    const matches = edges.filter(edge => !structural.has(edge.id)
      && ((edge.source === source.id && edge.target === target.id) || (edge.source === target.id && edge.target === source.id))
      && (expectedName === undefined || key(typeof edge.data?.name === 'string' ? edge.data.name : '') === expectedName))
    if (matches.length === 0) fail(`No encontré la relación ${source.data.name}-${target.data.name}.`)
    if (matches.length > 1) fail(`Encontré más de una relación entre ${source.data.name} y ${target.data.name}. Especifica cuál deseas modificar.`)
    return { edge: matches[0], source, target }
  }
  const convert = (a: AiAttribute): EntityAttribute => ({
    id: uniqueId('attribute'), name: a.name, type: AI_TYPE_MAP[a.dataType], primaryKey: a.primaryKey, nullable: a.nullable,
  })
  const equivalent = (a: EntityAttribute, b: AiAttribute) =>
    (a.type === AI_TYPE_MAP[b.dataType] || (a.type === 'TEXT' && b.dataType === 'String'))
    && a.primaryKey === b.primaryKey && (a.nullable ?? !a.primaryKey) === b.nullable

  const deleteAssociation = (associationNode: EntityFlowNode) => {
    const association = associationNode.data.association ?? fail(`La entidad "${associationNode.data.name}" no es asociativa`)
    const removedEdges = new Set(association.endpoints.map(endpoint => endpoint.relationshipId))
    nodes = nodes.filter(node => node.id !== associationNode.id)
    edges = edges.filter(edge => !removedEdges.has(edge.id) && edge.source !== associationNode.id && edge.target !== associationNode.id)
  }

  const deleteEntity = (node: EntityFlowNode) => {
    if (node.data.association) {
      deleteAssociation(node)
      return
    }
    const dependentAssociations = nodes.filter(candidate => candidate.data.association?.endpoints
      .some(endpoint => endpoint.entityId === node.id))
    const removedNodeIds = new Set([node.id, ...dependentAssociations.map(candidate => candidate.id)])
    const removedEdgeIds = new Set(dependentAssociations.flatMap(candidate =>
      candidate.data.association?.endpoints.map(endpoint => endpoint.relationshipId) ?? []))
    nodes = nodes.filter(candidate => !removedNodeIds.has(candidate.id))
    edges = edges.filter(edge => !removedEdgeIds.has(edge.id)
      && !removedNodeIds.has(edge.source) && !removedNodeIds.has(edge.target))
  }

  for (const op of validated) {
    if (op.type === 'ADD_ENTITY') {
      if (nodes.some(n => key(n.data.name) === key(op.entity.name))) fail(`Ya existe la entidad "${op.entity.name}"`)
      const attributes: EntityAttribute[] = []
      for (const a of op.entity.attributes) {
        const existing = attributes.find(item => key(item.name) === key(a.name))
        if (existing) {
          if (!equivalent(existing, a)) fail(`El atributo "${a.name}" tiene definiciones incompatibles`)
        } else attributes.push(convert(a))
      }
      // Place new nodes in free vertical bands; reserve height based on attribute count.
      const y = nodes.reduce((bottom, n) => Math.max(bottom, n.position.y + Math.max(n.measured?.height ?? 0, 100 + n.data.attributes.length * 32) + 60), 180)
      const id = uniqueId('entity')
      const node: EntityFlowNode = { id, type: 'entity', position: { x: 180, y }, data: { id, name: op.entity.name, attributes } }
      nodes.push(node)
    } else if (op.type === 'DELETE_ENTITY') {
      deleteEntity(find(op.entityName))
    } else if (op.type === 'RENAME_ENTITY') {
      const node = find(op.entityName)
      if (nodes.some(candidate => candidate.id !== node.id && key(candidate.data.name) === key(op.newName)))
        fail(`Ya existe la entidad "${op.newName}"`)
      nodes = nodes.map(candidate => candidate.id === node.id
        ? { ...candidate, data: { ...candidate.data, name: op.newName } } : candidate)
    } else if (op.type === 'ADD_ATTRIBUTE') {
      const node = find(op.entityName)
      const existing = node.data.attributes.find(a => key(a.name) === key(op.attribute.name))
      if (existing) {
        if (!equivalent(existing, op.attribute)) fail(`El atributo "${op.attribute.name}" ya existe con otra definición`)
        continue
      }
      const updated = { ...node, data: { ...node.data, attributes: [...node.data.attributes, convert(op.attribute)] } }
      nodes = nodes.map(n => n.id === node.id ? updated : n)
    } else if (op.type === 'DELETE_ATTRIBUTE') {
      const { node, attribute } = findAttribute(op.entityName, op.attributeName)
      nodes = nodes.map(candidate => candidate.id === node.id ? { ...candidate, data: { ...candidate.data,
        attributes: candidate.data.attributes.filter(item => item.id !== attribute.id) } } : candidate)
    } else if (op.type === 'RENAME_ATTRIBUTE') {
      const { node, attribute } = findAttribute(op.entityName, op.attributeName)
      if (node.data.attributes.some(item => item.id !== attribute.id && key(item.name) === key(op.newName)))
        fail(`La entidad "${node.data.name}" ya contiene el atributo "${op.newName}"`)
      nodes = nodes.map(candidate => candidate.id === node.id ? { ...candidate, data: { ...candidate.data,
        attributes: candidate.data.attributes.map(item => item.id === attribute.id ? { ...item, name: op.newName } : item) } } : candidate)
    } else if (op.type === 'CHANGE_ATTRIBUTE_TYPE') {
      const { node, attribute } = findAttribute(op.entityName, op.attributeName)
      nodes = nodes.map(candidate => candidate.id === node.id ? { ...candidate, data: { ...candidate.data,
        attributes: candidate.data.attributes.map(item => item.id === attribute.id
          ? { ...item, type: AI_TYPE_MAP[op.dataType] } : item) } } : candidate)
    } else if (op.type === 'SET_ATTRIBUTE_PRIMARY_KEY') {
      const { node, attribute } = findAttribute(op.entityName, op.attributeName)
      nodes = nodes.map(candidate => candidate.id === node.id ? { ...candidate, data: { ...candidate.data,
        attributes: candidate.data.attributes.map(item => item.id === attribute.id
          ? { ...item, primaryKey: op.value, ...(op.value ? { nullable: false } : {}) } : item) } } : candidate)
    } else if (op.type === 'SET_ATTRIBUTE_NULLABLE') {
      const { node, attribute } = findAttribute(op.entityName, op.attributeName)
      if (op.value && attribute.primaryKey) fail(`El atributo PK "${attribute.name}" no puede aceptar null`)
      nodes = nodes.map(candidate => candidate.id === node.id ? { ...candidate, data: { ...candidate.data,
        attributes: candidate.data.attributes.map(item => item.id === attribute.id
          ? { ...item, nullable: op.value } : item) } } : candidate)
    } else if (op.type === 'ADD_RELATIONSHIP') {
      const source = find(op.relationship.sourceEntity).id
      const target = find(op.relationship.targetEntity).id
      if (source === target) fail('Las autorrelaciones nuevas no están habilitadas en este MVP')
      const candidate: DiagramEdge = { id: '', source, target, data: {
        sourceCardinality: op.relationship.sourceCardinality, targetCardinality: op.relationship.targetCardinality,
        ...(op.relationship.name == null ? {} : { name: op.relationship.name }),
        ...(op.relationship.joinTableName == null ? {} : { joinTableName: op.relationship.joinTableName }),
      } }
      if (edges.some(e => equivalentRelationship(e, candidate))) continue
      const edge = normalizeRelationshipEdge({ ...candidate, id: uniqueId('edge') })
      edges.push(edge)
    } else if (op.type === 'DELETE_RELATIONSHIP') {
      const { edge } = findRelationship(op.relationship)
      edges = edges.filter(candidate => candidate.id !== edge.id)
    } else if (op.type === 'UPDATE_RELATIONSHIP') {
      const { edge, source } = findRelationship(op.relationship)
      const direct = edge.source === source.id
      edges = edges.map(candidate => candidate.id === edge.id ? normalizeRelationshipEdge({ ...candidate, data: {
        ...candidate.data,
        sourceCardinality: direct ? op.relationship.sourceCardinality : op.relationship.targetCardinality,
        targetCardinality: direct ? op.relationship.targetCardinality : op.relationship.sourceCardinality,
      } }) : candidate)
    } else if (op.type === 'CREATE_ASSOCIATION') {
      const source = find(op.association.sourceEntity)
      const target = find(op.association.targetEntity)
      if (source.id === target.id) fail('Las autorrelaciones nuevas no están habilitadas en este MVP')
      const structural = structuralRelationshipIds(nodes)
      const candidates = edges.filter(edge => !structural.has(edge.id)
        && ((edge.source === source.id && edge.target === target.id) || (edge.source === target.id && edge.target === source.id)))
        .filter(edge => {
          const cards = normalizeRelationshipEdge(edge).data!
          return ['ZERO_MANY', 'ONE_MANY'].includes(String(cards.sourceCardinality))
            && ['ZERO_MANY', 'ONE_MANY'].includes(String(cards.targetCardinality))
        })
      if (candidates.length > 1) fail(`Encontré más de una relación N:M entre ${source.data.name} y ${target.data.name}.`)
      let relationshipId = candidates[0]?.id
      if (!relationshipId) {
        relationshipId = uniqueId('edge')
        edges.push(normalizeRelationshipEdge({ id: relationshipId, source: source.id, target: target.id,
          data: { sourceCardinality: 'ZERO_MANY', targetCardinality: 'ZERO_MANY' } }))
      }
      const converted = convertManyToManyAssociation(nodes, edges, relationshipId,
        op.association.associationEntityName, createId,
        op.association.attributes.map(item => ({ name: item.name, type: AI_TYPE_MAP[item.dataType], nullable: item.nullable })))
      nodes = converted.document.nodes
      edges = converted.document.edges
    } else if (op.type === 'DELETE_ASSOCIATION') {
      const association = find(op.associationEntityName)
      deleteAssociation(association)
    } else {
      const original = edges.find(edge => edge.id === op.conversion.relationshipId)
        ?? fail('La relacion N:M indicada no existe o ya fue convertida')
      const source = find(op.conversion.sourceEntity)
      const target = find(op.conversion.targetEntity)
      if (new Set([original.source, original.target]).size !== 2
        || ![original.source, original.target].includes(source.id)
        || ![original.source, original.target].includes(target.id)) {
        fail('Los extremos de la conversion no coinciden con la relacion indicada')
      }
      const attributeNames = new Set<string>()
      for (const item of op.conversion.attributes) {
        if (attributeNames.has(key(item.name))) fail(`El atributo "${item.name}" esta duplicado`)
        attributeNames.add(key(item.name))
      }
      const converted = convertManyToManyAssociation(nodes, edges, op.conversion.relationshipId,
        op.conversion.associationEntityName, createId,
        op.conversion.attributes.map(item => ({ name: item.name, type: AI_TYPE_MAP[item.dataType], nullable: item.nullable })))
      nodes = converted.document.nodes
      edges = converted.document.edges
    }
  }
  validateDiagramDocument({ version: 1, nodes, edges })
  const normalizedCurrentEdges = currentEdges.map(edge => normalizeRelationshipEdge({ ...edge, data: { ...edge.data } }))
  const changed = JSON.stringify({ nodes: currentNodes, edges: normalizedCurrentEdges }) !== JSON.stringify({ nodes, edges })
  return { nodes, edges, events: changed
    ? [{ type: 'DIAGRAM_BATCH_APPLIED' as const, document: { version: 1 as const, nodes, edges } }]
    : [] }
}
