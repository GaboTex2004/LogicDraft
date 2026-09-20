import type { AttributeType, DiagramEdge, EntityAttribute, EntityFlowNode } from '../types/diagram.types'
import type { AiAttribute, AiDataType, DiagramAiOperation } from '../types/diagramAi.types'
import { equivalentRelationship, isCardinality, normalizeRelationshipEdge } from './relationshipCardinality.ts'
import { convertManyToManyAssociation } from './associationConversion.ts'

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
function attribute(value: unknown): AiAttribute {
  const a = object(value)
  if (typeof a.dataType !== 'string' || !Object.hasOwn(AI_TYPE_MAP, a.dataType)
    || typeof a.primaryKey !== 'boolean' || typeof a.nullable !== 'boolean') return fail()
  return { name: name(a.name), dataType: a.dataType as AiDataType, primaryKey: a.primaryKey, nullable: a.nullable }
}
export function parseDiagramAiResponse(value: unknown): DiagramAiOperation[] {
  const raw = object(value).operations
  if (!Array.isArray(raw) || raw.length > 50) return fail()
  return raw.map((item): DiagramAiOperation => {
    const op = object(item)
    switch (op.type) {
      case 'ADD_ENTITY': {
        const entity = object(op.entity)
        if (!Array.isArray(entity.attributes) || entity.attributes.length > 100) return fail()
        return { type: op.type, entity: { name: name(entity.name), attributes: entity.attributes.map(attribute) } }
      }
      case 'ADD_ATTRIBUTE':
        return { type: op.type, entityName: name(op.entityName), attribute: attribute(op.attribute) }
      case 'ADD_RELATIONSHIP': {
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
  let nodes = [...currentNodes]
  const edges = [...currentEdges]
  const events: DiagramAiEvent[] = []
  let requiresAtomicBatch = false
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
  const convert = (a: AiAttribute): EntityAttribute => ({
    id: uniqueId('attribute'), name: a.name, type: AI_TYPE_MAP[a.dataType], primaryKey: a.primaryKey, nullable: a.nullable,
  })
  const equivalent = (a: EntityAttribute, b: AiAttribute) =>
    (a.type === AI_TYPE_MAP[b.dataType] || (a.type === 'TEXT' && b.dataType === 'String'))
    && a.primaryKey === b.primaryKey && (a.nullable ?? !a.primaryKey) === b.nullable

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
      events.push({ type: 'NODE_CREATED', node })
    } else if (op.type === 'ADD_ATTRIBUTE') {
      const node = find(op.entityName)
      const existing = node.data.attributes.find(a => key(a.name) === key(op.attribute.name))
      if (existing) {
        if (!equivalent(existing, op.attribute)) fail(`El atributo "${op.attribute.name}" ya existe con otra definición`)
        continue
      }
      const updated = { ...node, data: { ...node.data, attributes: [...node.data.attributes, convert(op.attribute)] } }
      nodes = nodes.map(n => n.id === node.id ? updated : n)
      events.push({ type: 'NODE_UPDATED', node: updated })
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
      events.push({ type: 'EDGE_CREATED', edge })
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
      edges.splice(0, edges.length, ...converted.document.edges)
      requiresAtomicBatch = true
    }
  }
  return { nodes, edges, events: requiresAtomicBatch
    ? [{ type: 'DIAGRAM_BATCH_APPLIED' as const, document: { version: 1 as const, nodes, edges } }]
    : events }
}
