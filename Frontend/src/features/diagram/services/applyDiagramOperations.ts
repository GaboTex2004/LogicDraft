import type { AttributeType, DiagramEdge, EntityAttribute, EntityFlowNode } from '../types/diagram.types'
import type { AiAttribute, AiDataType, DiagramAiOperation } from '../types/diagramAi.types'
import { equivalentRelationship, isCardinality, normalizeRelationshipEdge } from './relationshipCardinality.ts'

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
          || Object.keys(r).some(k => !['sourceEntity', 'targetEntity', 'sourceCardinality', 'targetCardinality'].includes(k))) return fail()
        return { type: op.type, relationship: { sourceEntity: name(r.sourceEntity), targetEntity: name(r.targetEntity), sourceCardinality: r.sourceCardinality, targetCardinality: r.targetCardinality } }
      }
      default: return fail('La IA devolvió una operación no soportada')
    }
  })
}

export type DiagramAiEvent =
  | { type: 'NODE_CREATED' | 'NODE_UPDATED'; node: EntityFlowNode }
  | { type: 'EDGE_CREATED'; edge: DiagramEdge }

export function applyDiagramOperations(
  currentNodes: EntityFlowNode[], currentEdges: DiagramEdge[], operations: unknown,
  createId: () => string = () => crypto.randomUUID(),
) {
  const validated = parseDiagramAiResponse({ operations })
  let nodes = [...currentNodes]
  const edges = [...currentEdges]
  const events: DiagramAiEvent[] = []
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
    } else {
      const source = find(op.relationship.sourceEntity).id
      const target = find(op.relationship.targetEntity).id
      if (source === target) fail('Las autorrelaciones nuevas no están habilitadas en este MVP')
      const candidate: DiagramEdge = { id: '', source, target, data: { sourceCardinality: op.relationship.sourceCardinality, targetCardinality: op.relationship.targetCardinality } }
      if (edges.some(e => equivalentRelationship(e, candidate))) continue
      const edge = normalizeRelationshipEdge({ ...candidate, id: uniqueId('edge') })
      edges.push(edge)
      events.push({ type: 'EDGE_CREATED', edge })
    }
  }
  return { nodes, edges, events }
}
