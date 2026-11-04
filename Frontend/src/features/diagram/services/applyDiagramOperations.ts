import {
  UML_NODE_KINDS,
  UML_RELATION_KINDS,
  type AttributeType,
  type DiagramCardinality,
  type DiagramEdge,
  type EntityAttribute,
  type EntityFlowNode,
  type UmlMethod,
  type UmlNodeKind,
  type UmlParameter,
  type UmlRelationKind,
  type UmlVisibility,
} from '../types/diagram.types.ts'
import type {
  AiAggregationEnd,
  AiAttribute,
  AiDataType,
  AiEntityDefinition,
  AiMethod,
  AiParameter,
  AiRelationshipRef,
  AiVisibility,
  DiagramAiOperation,
} from '../types/diagramAi.types.ts'
import { equivalentRelationship, isCardinality, normalizeRelationshipEdge } from './relationshipCardinality.ts'
import { convertManyToManyAssociation, structuralRelationshipIds } from './associationConversion.ts'
import { validateDiagramDocument } from './diagramDocumentValidation.ts'

export const AI_TYPE_MAP: Record<AiDataType, AttributeType> = {
  String: 'VARCHAR', Long: 'BIGINT', Integer: 'INTEGER', Double: 'DECIMAL',
  Boolean: 'BOOLEAN', Date: 'DATE', DateTime: 'TIMESTAMP',
}
const NON_MULTIPLICATIVE_RELATIONS: ReadonlySet<UmlRelationKind> = new Set([
  'GENERALIZATION',
  'REALIZATION',
  'DEPENDENCY',
])
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
  if (required.some(k => !(k in value)) || Object.keys(value).some(k => !required.includes(k) && !optional.includes(k))) fail()
}
function dataType(value: unknown): AiDataType {
  if (typeof value !== 'string' || !Object.hasOwn(AI_TYPE_MAP, value)) return fail()
  return value as AiDataType
}
function boolean(value: unknown): boolean {
  if (typeof value !== 'boolean') return fail()
  return value
}
function visibility(value: unknown): AiVisibility {
  if (
    typeof value !== 'string' ||
    !['PUBLIC', 'PRIVATE', 'PROTECTED', 'PACKAGE', 'public', 'private', 'protected', 'package'].includes(value)
  ) {
    return fail('Visibilidad UML no soportada')
  }
  return value as AiVisibility
}
function normalizeVisibility(value: AiVisibility | undefined, fallback: UmlVisibility = 'public'): UmlVisibility {
  if (!value) return fallback
  return value.toLowerCase() as UmlVisibility
}
function classifierKind(value: unknown): UmlNodeKind {
  if (typeof value !== 'string' || !UML_NODE_KINDS.includes(value as UmlNodeKind)) {
    return fail('Tipo de clasificador UML no soportado')
  }
  return value as UmlNodeKind
}
function relationKind(value: unknown): UmlRelationKind {
  if (typeof value !== 'string' || !UML_RELATION_KINDS.includes(value as UmlRelationKind)) {
    return fail('Tipo de relación UML no soportado')
  }
  return value as UmlRelationKind
}
function aggregationEnd(value: unknown): AiAggregationEnd {
  if (value !== 'SOURCE' && value !== 'TARGET') return fail('Extremo de agregación no válido')
  return value
}
function attribute(value: unknown): AiAttribute {
  const a = object(value)
  exactKeys(a, ['name', 'dataType', 'primaryKey', 'nullable'], ['visibility', 'static'])
  return {
    name: name(a.name),
    dataType: dataType(a.dataType),
    primaryKey: boolean(a.primaryKey),
    nullable: boolean(a.nullable),
    ...(a.visibility == null ? {} : { visibility: visibility(a.visibility) }),
    ...(a.static == null ? {} : { static: boolean(a.static) }),
  }
}
function parameter(value: unknown): AiParameter {
  const p = object(value)
  exactKeys(p, ['name'], ['dataType', 'type'])
  const rawType = p.dataType ?? p.type
  if (rawType == null) return fail()
  return { name: name(p.name), dataType: name(rawType) }
}
function methodDef(value: unknown): AiMethod {
  const m = object(value)
  exactKeys(m, ['name'], ['returnType', 'visibility', 'static', 'abstract', 'parameters'])
  if (m.parameters != null && (!Array.isArray(m.parameters) || m.parameters.length > 30)) return fail()
  const isStatic = m.static == null ? undefined : boolean(m.static)
  const isAbstract = m.abstract == null ? undefined : boolean(m.abstract)
  if (isStatic && isAbstract) return fail('Un método no puede ser static y abstract simultáneamente')
  return {
    name: name(m.name),
    ...(m.returnType == null ? {} : { returnType: name(m.returnType) }),
    ...(m.visibility == null ? {} : { visibility: visibility(m.visibility) }),
    ...(isStatic === undefined ? {} : { static: isStatic }),
    ...(isAbstract === undefined ? {} : { abstract: isAbstract }),
    ...(m.parameters == null ? {} : { parameters: m.parameters.map(parameter) }),
  }
}
function parameterTypesList(value: unknown): string[] | undefined {
  if (value == null) return undefined
  if (!Array.isArray(value) || value.length > 30) return fail()
  return value.map(name)
}
function extractRelationKind(raw: Record<string, unknown>): UmlRelationKind | undefined {
  const kindVal = raw.kind ?? raw.relationKind
  return kindVal == null ? undefined : relationKind(kindVal)
}
function relationshipRef(value: unknown): AiRelationshipRef {
  const r = object(value)
  exactKeys(
    r,
    ['sourceEntity', 'targetEntity'],
    ['name', 'id', 'kind', 'relationKind', 'sourceMultiplicity', 'targetMultiplicity', 'aggregationEnd'],
  )
  const kind = extractRelationKind(r)
  return {
    sourceEntity: name(r.sourceEntity),
    targetEntity: name(r.targetEntity),
    ...(r.name == null ? {} : { name: name(r.name) }),
    ...(r.id == null ? {} : { id: name(r.id) }),
    ...(kind == null ? {} : { kind }),
    ...(r.sourceMultiplicity == null ? {} : { sourceMultiplicity: name(r.sourceMultiplicity) }),
    ...(r.targetMultiplicity == null ? {} : { targetMultiplicity: name(r.targetMultiplicity) }),
    ...(r.aggregationEnd == null ? {} : { aggregationEnd: aggregationEnd(r.aggregationEnd) }),
  }
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
        const kind = entity.kind == null ? undefined : classifierKind(entity.kind)
        if (kind === 'ASSOCIATION_CLASS') {
          return fail('Usa CREATE_ASSOCIATION para crear una clase asociativa con sus extremos')
        }
        const allowDefaultAttrs = kind === 'INTERFACE' || kind === 'ENUMERATION'
        exactKeys(
          entity,
          allowDefaultAttrs ? ['name'] : ['name', 'attributes'],
          ['attributes', 'kind', 'abstract', 'methods', 'literals'],
        )
        const rawAttrs = entity.attributes == null && allowDefaultAttrs ? [] : entity.attributes
        if (!Array.isArray(rawAttrs) || rawAttrs.length > 100) return fail()
        if (entity.methods != null && (!Array.isArray(entity.methods) || entity.methods.length > 100)) return fail()
        if (entity.literals != null && (!Array.isArray(entity.literals) || entity.literals.length > 100)) return fail()
        const parsedEntity: AiEntityDefinition = {
          name: name(entity.name),
          attributes: rawAttrs.map(attribute),
          ...(kind == null ? {} : { kind }),
          ...(entity.abstract == null ? {} : { abstract: boolean(entity.abstract) }),
          ...(entity.methods == null ? {} : { methods: entity.methods.map(methodDef) }),
          ...(entity.literals == null ? {} : { literals: entity.literals.map(name) }),
        }
        return { type: op.type, entity: parsedEntity }
      }
      case 'DELETE_ENTITY':
        exactKeys(op, ['type', 'entityName'])
        return { type: op.type, entityName: name(op.entityName) }
      case 'RENAME_ENTITY':
        exactKeys(op, ['type', 'entityName', 'newName'])
        return { type: op.type, entityName: name(op.entityName), newName: name(op.newName) }
      case 'UPDATE_CLASSIFIER': {
        exactKeys(op, ['type', 'entityName'], ['classifierKind', 'kind', 'abstract'])
        const rawKind = op.classifierKind ?? op.kind
        if (rawKind == null && op.abstract == null) return fail()
        return {
          type: op.type,
          entityName: name(op.entityName),
          ...(rawKind == null ? {} : { classifierKind: classifierKind(rawKind) }),
          ...(op.abstract == null ? {} : { abstract: boolean(op.abstract) }),
        }
      }
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
      case 'SET_ATTRIBUTE_STATIC': {
        exactKeys(op, ['type', 'entityName', 'attributeName'], ['value', 'static'])
        const rawVal = op.value ?? op.static
        return { type: op.type, entityName: name(op.entityName), attributeName: name(op.attributeName), value: boolean(rawVal) }
      }
      case 'SET_ATTRIBUTE_VISIBILITY':
        exactKeys(op, ['type', 'entityName', 'attributeName', 'visibility'])
        return {
          type: op.type,
          entityName: name(op.entityName),
          attributeName: name(op.attributeName),
          visibility: visibility(op.visibility),
        }
      case 'ADD_METHOD':
        exactKeys(op, ['type', 'entityName', 'method'])
        return { type: op.type, entityName: name(op.entityName), method: methodDef(op.method) }
      case 'UPDATE_METHOD': {
        exactKeys(
          op,
          ['type', 'entityName', 'methodName'],
          ['parameterTypes', 'method', 'newName', 'returnType', 'visibility', 'static', 'abstract'],
        )
        const pTypes = parameterTypesList(op.parameterTypes)
        const rawMethod =
          op.method != null
            ? op.method
            : {
                name: op.newName ?? op.methodName,
                ...(op.returnType == null ? {} : { returnType: op.returnType }),
                ...(op.visibility == null ? {} : { visibility: op.visibility }),
                ...(op.static == null ? {} : { static: op.static }),
                ...(op.abstract == null ? {} : { abstract: op.abstract }),
              }
        return {
          type: op.type,
          entityName: name(op.entityName),
          methodName: name(op.methodName),
          ...(pTypes == null ? {} : { parameterTypes: pTypes }),
          method: methodDef(rawMethod),
        }
      }
      case 'DELETE_METHOD': {
        exactKeys(op, ['type', 'entityName', 'methodName'], ['parameterTypes'])
        const pTypes = parameterTypesList(op.parameterTypes)
        return {
          type: op.type,
          entityName: name(op.entityName),
          methodName: name(op.methodName),
          ...(pTypes == null ? {} : { parameterTypes: pTypes }),
        }
      }
      case 'ADD_METHOD_PARAMETER': {
        exactKeys(op, ['type', 'entityName', 'methodName', 'parameter'], ['parameterTypes'])
        const pTypes = parameterTypesList(op.parameterTypes)
        return {
          type: op.type,
          entityName: name(op.entityName),
          methodName: name(op.methodName),
          ...(pTypes == null ? {} : { parameterTypes: pTypes }),
          parameter: parameter(op.parameter),
        }
      }
      case 'UPDATE_METHOD_PARAMETER': {
        exactKeys(
          op,
          ['type', 'entityName', 'methodName', 'parameterName'],
          ['parameterTypes', 'parameter', 'newName', 'newType'],
        )
        const pTypes = parameterTypesList(op.parameterTypes)
        const rawParam =
          op.parameter != null
            ? op.parameter
            : {
                name: op.newName ?? op.parameterName,
                ...(op.newType == null ? {} : { type: op.newType }),
              }
        return {
          type: op.type,
          entityName: name(op.entityName),
          methodName: name(op.methodName),
          ...(pTypes == null ? {} : { parameterTypes: pTypes }),
          parameterName: name(op.parameterName),
          parameter: parameter(rawParam),
        }
      }
      case 'DELETE_METHOD_PARAMETER': {
        exactKeys(op, ['type', 'entityName', 'methodName', 'parameterName'], ['parameterTypes'])
        const pTypes = parameterTypesList(op.parameterTypes)
        return {
          type: op.type,
          entityName: name(op.entityName),
          methodName: name(op.methodName),
          ...(pTypes == null ? {} : { parameterTypes: pTypes }),
          parameterName: name(op.parameterName),
        }
      }
      case 'ADD_ENUM_LITERAL':
      case 'DELETE_ENUM_LITERAL':
        exactKeys(op, ['type', 'entityName', 'literal'])
        return { type: op.type, entityName: name(op.entityName), literal: name(op.literal) }
      case 'RENAME_ENUM_LITERAL': {
        exactKeys(op, ['type', 'entityName', 'literal'], ['newName', 'newLiteral'])
        const rawNew = op.newName ?? op.newLiteral
        return { type: op.type, entityName: name(op.entityName), literal: name(op.literal), newName: name(rawNew) }
      }
      case 'ADD_RELATIONSHIP': {
        exactKeys(op, ['type', 'relationship'])
        const r = object(op.relationship)
        exactKeys(
          r,
          ['sourceEntity', 'targetEntity'],
          [
            'sourceCardinality',
            'targetCardinality',
            'name',
            'joinTableName',
            'id',
            'kind',
            'relationKind',
            'sourceMultiplicity',
            'targetMultiplicity',
            'aggregationEnd',
          ],
        )
        const kind = extractRelationKind(r)
        const nonMultiplicative = kind != null && NON_MULTIPLICATIVE_RELATIONS.has(kind)
        if (!nonMultiplicative) {
          if (!isCardinality(r.sourceCardinality) || !isCardinality(r.targetCardinality)) return fail()
        } else {
          if (r.sourceCardinality != null && !isCardinality(r.sourceCardinality)) return fail()
          if (r.targetCardinality != null && !isCardinality(r.targetCardinality)) return fail()
        }
        return {
          type: op.type,
          relationship: {
            sourceEntity: name(r.sourceEntity),
            targetEntity: name(r.targetEntity),
            ...(r.sourceCardinality == null ? {} : { sourceCardinality: r.sourceCardinality as DiagramCardinality }),
            ...(r.targetCardinality == null ? {} : { targetCardinality: r.targetCardinality as DiagramCardinality }),
            ...(r.name == null ? {} : { name: name(r.name) }),
            ...(r.joinTableName == null ? {} : { joinTableName: name(r.joinTableName) }),
            ...(r.id == null ? {} : { id: name(r.id) }),
            ...(kind == null ? {} : { kind }),
            ...(r.sourceMultiplicity == null ? {} : { sourceMultiplicity: name(r.sourceMultiplicity) }),
            ...(r.targetMultiplicity == null ? {} : { targetMultiplicity: name(r.targetMultiplicity) }),
            ...(r.aggregationEnd == null ? {} : { aggregationEnd: aggregationEnd(r.aggregationEnd) }),
          },
        }
      }
      case 'DELETE_RELATIONSHIP':
        exactKeys(op, ['type', 'relationship'])
        return { type: op.type, relationship: relationshipRef(op.relationship) }
      case 'UPDATE_RELATIONSHIP': {
        exactKeys(op, ['type', 'relationship'])
        const r = object(op.relationship)
        exactKeys(
          r,
          ['sourceEntity', 'targetEntity'],
          [
            'sourceCardinality',
            'targetCardinality',
            'name',
            'joinTableName',
            'id',
            'kind',
            'relationKind',
            'sourceMultiplicity',
            'targetMultiplicity',
            'aggregationEnd',
          ],
        )
        const kind = extractRelationKind(r)
        const nonMultiplicative = kind != null && NON_MULTIPLICATIVE_RELATIONS.has(kind)
        if (!nonMultiplicative && kind == null) {
          if (!isCardinality(r.sourceCardinality) || !isCardinality(r.targetCardinality)) return fail()
        } else {
          if (r.sourceCardinality != null && !isCardinality(r.sourceCardinality)) return fail()
          if (r.targetCardinality != null && !isCardinality(r.targetCardinality)) return fail()
        }
        return {
          type: op.type,
          relationship: {
            sourceEntity: name(r.sourceEntity),
            targetEntity: name(r.targetEntity),
            ...(r.sourceCardinality == null ? {} : { sourceCardinality: r.sourceCardinality as DiagramCardinality }),
            ...(r.targetCardinality == null ? {} : { targetCardinality: r.targetCardinality as DiagramCardinality }),
            ...(r.name == null ? {} : { name: name(r.name) }),
            ...(r.joinTableName == null ? {} : { joinTableName: name(r.joinTableName) }),
            ...(r.id == null ? {} : { id: name(r.id) }),
            ...(kind == null ? {} : { kind }),
            ...(r.sourceMultiplicity == null ? {} : { sourceMultiplicity: name(r.sourceMultiplicity) }),
            ...(r.targetMultiplicity == null ? {} : { targetMultiplicity: name(r.targetMultiplicity) }),
            ...(r.aggregationEnd == null ? {} : { aggregationEnd: aggregationEnd(r.aggregationEnd) }),
          },
        }
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
        if (Object.keys(op).some(k => !['type', 'conversion'].includes(k))
          || Object.keys(conversion).some(k => !['relationshipId', 'sourceEntity', 'targetEntity', 'associationEntityName', 'attributes'].includes(k))
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
  let nodes = currentNodes.map(node => ({
    ...node,
    data: {
      ...node.data,
      attributes: node.data.attributes.map(attr => ({ ...attr })),
      ...(node.data.methods
        ? {
            methods: node.data.methods.map(m => ({
              ...m,
              parameters: m.parameters.map(p => ({ ...p })),
            })),
          }
        : {}),
      ...(node.data.literals ? { literals: [...node.data.literals] } : {}),
    },
  }))
  let edges = currentEdges.map(edge => normalizeRelationshipEdge({ ...edge, data: { ...edge.data } }))
  const usedIds = new Set([
    ...nodes.map(n => n.id),
    ...edges.map(e => e.id),
    ...nodes.flatMap(n => n.data.attributes.map(a => a.id)),
    ...nodes.flatMap(n => (n.data.methods ?? []).flatMap(m => [m.id, ...m.parameters.map(p => p.id)])),
  ])
  const uniqueId = (prefix: string) => {
    const id = `${prefix}-${createId()}`
    if (usedIds.has(id)) return fail('No se pudo generar un identificador único')
    usedIds.add(id)
    return id
  }
  const isAssociationEdge = (edge: DiagramEdge) =>
    !edge.data?.relationKind || edge.data.relationKind === 'ASSOCIATION'
  const isMultiplicativeEdge = (edge: DiagramEdge) =>
    !edge.data?.relationKind || ['ASSOCIATION', 'AGGREGATION', 'COMPOSITION'].includes(edge.data.relationKind)
  const edgeRelationKind = (edge: DiagramEdge): UmlRelationKind =>
    edge.data?.relationKind ?? 'ASSOCIATION'
  const isClassLike = (kind?: UmlNodeKind) => !kind || ['ENTITY', 'CLASS', 'ASSOCIATION_CLASS'].includes(kind)

  const findClassifier = (entityName: string) => {
    const matches = nodes.filter(n => key(n.data.name) === key(entityName))
    if (matches.length !== 1) return fail(`La entidad "${entityName}" no existe o su nombre es ambiguo`)
    return matches[0]
  }
  const findAttributeCarrier = (entityName: string) => {
    const node = findClassifier(entityName)
    const kind = node.data.kind
    if (kind === 'INTERFACE' || kind === 'ENUMERATION') {
      return fail(`El elemento UML "${node.data.name}" es de tipo ${kind} y no admite operaciones IA de entidad`)
    }
    return node
  }
  const findMethodCarrier = (entityName: string) => {
    const node = findClassifier(entityName)
    if (node.data.kind === 'ENUMERATION') {
      return fail(`La enumeración "${node.data.name}" es de tipo ENUMERATION y no admite métodos`)
    }
    return node
  }
  const findEnumeration = (entityName: string) => {
    const node = findClassifier(entityName)
    if (node.data.kind !== 'ENUMERATION') {
      return fail(`El clasificador "${node.data.name}" no es una enumeración (ENUMERATION)`)
    }
    return node
  }
  const verifyNotStructuralFk = (node: EntityFlowNode, attributeName: string, isDelete: boolean) => {
    const endpoints = node.data.association?.endpoints
    if (!endpoints) return
    const normalizedAttr = attributeName.replace(/([a-z0-9])([A-Z])/g, '$1_$2').toLowerCase()
    const attrKey = key(attributeName)
    for (const ep of endpoints) {
      const fk = ep.foreignKeyName.toLowerCase()
      if (fk === normalizedAttr || fk === attrKey || `fk_${fk}` === normalizedAttr || `fk_${fk}` === attrKey) {
        if (isDelete) {
          fail(`El atributo "${attributeName}" es una clave foránea estructural derivada de la clase asociativa "${node.data.name}" y no puede eliminarse como atributo independiente.`)
        }
        fail(`El atributo "${attributeName}" procede de metadatos estructurales de la clase asociativa "${node.data.name}". Modifica la asociación canónica o sus extremos.`)
      }
    }
    if (isDelete && attrKey === 'id') {
      fail(`El atributo "id" es la clave primaria estructural de la clase asociativa "${node.data.name}" y no puede eliminarse como atributo independiente.`)
    }
  }
  const findAttribute = (entityName: string, attributeName: string, isDelete = false) => {
    const node = findAttributeCarrier(entityName)
    verifyNotStructuralFk(node, attributeName, isDelete)
    const matches = node.data.attributes.filter(item => key(item.name) === key(attributeName))
    if (matches.length !== 1) fail(`El atributo "${attributeName}" no existe o su nombre es ambiguo en "${node.data.name}"`)
    return { node, attribute: matches[0] }
  }
  const methodSignatureTypes = (m: UmlMethod) => m.parameters.map(p => key(p.type))
  const sameParameterTypes = (a: readonly string[], b: readonly string[]) =>
    a.length === b.length && a.every((item, idx) => key(item) === key(b[idx]))
  const findMethod = (node: EntityFlowNode, methodName: string, parameterTypes?: string[]) => {
    const methods = node.data.methods ?? []
    const matches = methods.filter(m => {
      if (key(m.name) !== key(methodName)) return false
      if (!parameterTypes) return true
      return sameParameterTypes(methodSignatureTypes(m), parameterTypes)
    })
    if (matches.length === 0) fail(`El método "${methodName}" no existe en "${node.data.name}"`)
    if (matches.length > 1) fail(`El método "${methodName}" está sobrecargado en "${node.data.name}". Especifica parameterTypes.`)
    return matches[0]
  }
  const findRelationship = (reference: AiRelationshipRef, allowKindOrNameFallback = false) => {
    const source = findClassifier(reference.sourceEntity)
    const target = findClassifier(reference.targetEntity)
    const structural = structuralRelationshipIds(nodes)
    const expectedName = reference.name == null ? undefined : key(reference.name)
    const expectedKind = reference.kind
    let matches = edges.filter(edge => {
      if (reference.id && edge.id === reference.id) {
        if (structural.has(edge.id)) {
          fail('No se puede modificar ni eliminar directamente una arista estructural de una clase asociativa.')
        }
        return true
      }
      if (structural.has(edge.id)) return false
      const endpointsMatch =
        (edge.source === source.id && edge.target === target.id) ||
        (edge.source === target.id && edge.target === source.id)
      if (!endpointsMatch) return false
      if (expectedKind) {
        if (edgeRelationKind(edge) !== expectedKind) return false
      } else if (!isMultiplicativeEdge(edge) && !edges.every(e => !isMultiplicativeEdge(e))) {
        return false
      }
      return expectedName === undefined || key(typeof edge.data?.name === 'string' ? edge.data.name : '') === expectedName
    })
    if (matches.length === 0 && allowKindOrNameFallback && !reference.id) {
      const endpointOnly = edges.filter(
        edge =>
          !structural.has(edge.id) &&
          ((edge.source === source.id && edge.target === target.id) ||
            (edge.source === target.id && edge.target === source.id)),
      )
      if (endpointOnly.length === 1) {
        matches = endpointOnly
      }
    }
    if (matches.length === 0) {
      const touchesStructural = edges.some(
        edge =>
          structural.has(edge.id) &&
          ((edge.source === source.id && edge.target === target.id) ||
            (edge.source === target.id && edge.target === source.id)),
      )
      if (touchesStructural) {
        fail('No se puede modificar ni eliminar directamente una arista estructural de una clase asociativa.')
      }
      fail(`No encontré la relación ${source.data.name}-${target.data.name}.`)
    }
    if (matches.length > 1) fail(`Encontré más de una relación entre ${source.data.name} y ${target.data.name}. Especifica cuál deseas modificar.`)
    return { edge: matches[0], source, target }
  }
  const convert = (a: AiAttribute): EntityAttribute => ({
    id: uniqueId('attribute'),
    name: a.name,
    type: AI_TYPE_MAP[a.dataType],
    primaryKey: a.primaryKey,
    nullable: a.nullable,
    ...(a.visibility == null ? {} : { visibility: normalizeVisibility(a.visibility) }),
    ...(a.static == null ? {} : { static: a.static }),
  })
  const convertParameter = (p: AiParameter): UmlParameter => ({
    id: uniqueId('param'),
    name: p.name,
    type: p.dataType,
  })
  const convertMethod = (m: AiMethod): UmlMethod => {
    const params: UmlParameter[] = []
    const seen = new Set<string>()
    for (const p of m.parameters ?? []) {
      if (seen.has(key(p.name))) fail(`El método "${m.name}" contiene parámetros con nombre duplicado`)
      seen.add(key(p.name))
      params.push(convertParameter(p))
    }
    return {
      id: uniqueId('method'),
      name: m.name,
      visibility: normalizeVisibility(m.visibility, 'public'),
      returnType: m.returnType ?? 'void',
      parameters: params,
      ...(m.static == null ? {} : { static: m.static }),
      ...(m.abstract == null ? {} : { abstract: m.abstract }),
    }
  }
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
      const kind = op.entity.kind
      if (kind === 'ENUMERATION' && op.entity.attributes.length > 0) {
        fail(`La enumeración "${op.entity.name}" debe usar literales, no atributos.`)
      }
      if (kind === 'ENUMERATION' && (op.entity.methods?.length ?? 0) > 0) {
        fail(`La enumeración "${op.entity.name}" no admite métodos.`)
      }
      if (kind === 'INTERFACE' && op.entity.attributes.length > 0) {
        fail(`La interfaz "${op.entity.name}" no admite atributos en LogicDraft.`)
      }
      if (kind !== 'ENUMERATION' && (op.entity.literals?.length ?? 0) > 0) {
        fail('Solo una enumeración puede declarar literales.')
      }
      if (kind === 'ENUMERATION' && op.entity.abstract) {
        fail(`La enumeración "${op.entity.name}" no puede ser abstracta.`)
      }
      const attributes: EntityAttribute[] = []
      for (const a of op.entity.attributes) {
        const existing = attributes.find(item => key(item.name) === key(a.name))
        if (existing) {
          if (!equivalent(existing, a)) fail(`El atributo "${a.name}" tiene definiciones incompatibles`)
        } else attributes.push(convert(a))
      }
      const methods: UmlMethod[] = []
      for (const m of op.entity.methods ?? []) {
        const candidate = convertMethod(m)
        if (methods.some(existing => key(existing.name) === key(candidate.name) && sameParameterTypes(methodSignatureTypes(existing), methodSignatureTypes(candidate)))) {
          fail(`El método "${m.name}" tiene una sobrecarga duplicada con los mismos tipos de parámetros.`)
        }
        methods.push(candidate)
      }
      const literals: string[] = []
      for (const lit of op.entity.literals ?? []) {
        if (literals.some(existing => key(existing) === key(lit))) {
          fail(`La enumeración "${op.entity.name}" contiene literales duplicados.`)
        }
        literals.push(lit)
      }
      // Place new nodes in free vertical bands; reserve height based on attribute count.
      const y = nodes.reduce((bottom, n) => Math.max(bottom, n.position.y + Math.max(n.measured?.height ?? 0, 100 + n.data.attributes.length * 32) + 60), 180)
      const id = uniqueId('entity')
      const node: EntityFlowNode = {
        id,
        type: 'entity',
        position: { x: 180, y },
        data: {
          id,
          name: op.entity.name,
          attributes,
          ...(kind == null ? {} : { kind }),
          ...(op.entity.abstract == null || kind === 'ENUMERATION' ? {} : { abstract: op.entity.abstract }),
          ...(methods.length > 0 || kind === 'CLASS' || kind === 'INTERFACE' ? { methods } : {}),
          ...(kind === 'ENUMERATION' ? { literals } : {}),
        },
      }
      nodes.push(node)
    } else if (op.type === 'DELETE_ENTITY') {
      deleteEntity(findClassifier(op.entityName))
    } else if (op.type === 'RENAME_ENTITY') {
      const node = findClassifier(op.entityName)
      if (nodes.some(candidate => candidate.id !== node.id && key(candidate.data.name) === key(op.newName)))
        fail(`Ya existe la entidad "${op.newName}"`)
      nodes = nodes.map(candidate => candidate.id === node.id
        ? { ...candidate, data: { ...candidate.data, name: op.newName } } : candidate)
    } else if (op.type === 'UPDATE_CLASSIFIER') {
      const node = findClassifier(op.entityName)
      const currentKind = node.data.kind ?? 'ENTITY'
      if (op.classifierKind && op.classifierKind !== currentKind) {
        fail(`No se puede cambiar automáticamente el tipo del clasificador "${node.data.name}" de ${currentKind} a ${op.classifierKind} porque tiene implicaciones estructurales.`)
      }
      if (op.abstract !== undefined) {
        if (currentKind === 'ENUMERATION') {
          fail(`La enumeración "${node.data.name}" no puede ser abstracta.`)
        }
        nodes = nodes.map(candidate => candidate.id === node.id
          ? { ...candidate, data: { ...candidate.data, abstract: op.abstract } } : candidate)
      }
    } else if (op.type === 'ADD_ATTRIBUTE') {
      const node = findAttributeCarrier(op.entityName)
      const existing = node.data.attributes.find(a => key(a.name) === key(op.attribute.name))
      if (existing) {
        if (!equivalent(existing, op.attribute)) fail(`El atributo "${op.attribute.name}" ya existe con otra definición`)
        continue
      }
      const updated = { ...node, data: { ...node.data, attributes: [...node.data.attributes, convert(op.attribute)] } }
      nodes = nodes.map(n => n.id === node.id ? updated : n)
    } else if (op.type === 'DELETE_ATTRIBUTE') {
      const { node, attribute: attr } = findAttribute(op.entityName, op.attributeName, true)
      nodes = nodes.map(candidate => candidate.id === node.id ? { ...candidate, data: { ...candidate.data,
        attributes: candidate.data.attributes.filter(item => item.id !== attr.id) } } : candidate)
    } else if (op.type === 'RENAME_ATTRIBUTE') {
      const { node, attribute: attr } = findAttribute(op.entityName, op.attributeName)
      verifyNotStructuralFk(node, op.attributeName, false)
      verifyNotStructuralFk(node, op.newName, false)
      if (node.data.attributes.some(item => item.id !== attr.id && key(item.name) === key(op.newName)))
        fail(`La entidad "${node.data.name}" ya contiene el atributo "${op.newName}"`)
      nodes = nodes.map(candidate => candidate.id === node.id ? { ...candidate, data: { ...candidate.data,
        attributes: candidate.data.attributes.map(item => item.id === attr.id ? { ...item, name: op.newName } : item) } } : candidate)
    } else if (op.type === 'CHANGE_ATTRIBUTE_TYPE') {
      const { node, attribute: attr } = findAttribute(op.entityName, op.attributeName)
      verifyNotStructuralFk(node, op.attributeName, false)
      nodes = nodes.map(candidate => candidate.id === node.id ? { ...candidate, data: { ...candidate.data,
        attributes: candidate.data.attributes.map(item => item.id === attr.id
          ? { ...item, type: AI_TYPE_MAP[op.dataType] } : item) } } : candidate)
    } else if (op.type === 'SET_ATTRIBUTE_PRIMARY_KEY') {
      const { node, attribute: attr } = findAttribute(op.entityName, op.attributeName)
      verifyNotStructuralFk(node, op.attributeName, false)
      if (op.value && node.data.association) {
        fail(`La clase asociativa "${node.data.name}" no permite modificar claves primarias individuales.`)
      }
      nodes = nodes.map(candidate => candidate.id === node.id ? { ...candidate, data: { ...candidate.data,
        attributes: candidate.data.attributes.map(item => item.id === attr.id
          ? { ...item, primaryKey: op.value, ...(op.value ? { nullable: false } : {}) } : item) } } : candidate)
    } else if (op.type === 'SET_ATTRIBUTE_NULLABLE') {
      const { node, attribute: attr } = findAttribute(op.entityName, op.attributeName)
      verifyNotStructuralFk(node, op.attributeName, false)
      if (op.value && attr.primaryKey) fail(`El atributo PK "${attr.name}" no puede aceptar null`)
      nodes = nodes.map(candidate => candidate.id === node.id ? { ...candidate, data: { ...candidate.data,
        attributes: candidate.data.attributes.map(item => item.id === attr.id
          ? { ...item, nullable: op.value } : item) } } : candidate)
    } else if (op.type === 'SET_ATTRIBUTE_VISIBILITY') {
      const { node, attribute: attr } = findAttribute(op.entityName, op.attributeName)
      const vis = normalizeVisibility(op.visibility)
      nodes = nodes.map(candidate => candidate.id === node.id ? { ...candidate, data: { ...candidate.data,
        attributes: candidate.data.attributes.map(item => item.id === attr.id
          ? { ...item, visibility: vis } : item) } } : candidate)
    } else if (op.type === 'SET_ATTRIBUTE_STATIC') {
      const { node, attribute: attr } = findAttribute(op.entityName, op.attributeName)
      nodes = nodes.map(candidate => candidate.id === node.id ? { ...candidate, data: { ...candidate.data,
        attributes: candidate.data.attributes.map(item => item.id === attr.id
          ? { ...item, static: op.value } : item) } } : candidate)
    } else if (op.type === 'ADD_METHOD') {
      const node = findMethodCarrier(op.entityName)
      const candidate = convertMethod(op.method)
      const methods = node.data.methods ?? []
      if (methods.some(m => key(m.name) === key(candidate.name) && sameParameterTypes(methodSignatureTypes(m), methodSignatureTypes(candidate)))) {
        fail(`La clase "${node.data.name}" ya contiene un método "${candidate.name}" con la misma firma de parámetros`)
      }
      nodes = nodes.map(n => n.id === node.id ? { ...n, data: { ...n.data, methods: [...methods, candidate] } } : n)
    } else if (op.type === 'UPDATE_METHOD') {
      const node = findMethodCarrier(op.entityName)
      const target = findMethod(node, op.methodName, op.parameterTypes)
      const nextParams: UmlParameter[] =
        op.method.parameters == null
          ? target.parameters
          : op.method.parameters.map((p, idx) => ({
              id: target.parameters[idx]?.id ?? uniqueId('param'),
              name: p.name,
              type: p.dataType,
            }))
      const seenParams = new Set<string>()
      for (const p of nextParams) {
        if (seenParams.has(key(p.name))) fail(`El método "${op.method.name}" contiene parámetros con nombre duplicado`)
        seenParams.add(key(p.name))
      }
      const nextStatic = op.method.static ?? target.static
      const nextAbstract = op.method.abstract ?? target.abstract
      if (nextStatic && nextAbstract) fail(`El método "${node.data.name}.${op.method.name}" no puede ser static y abstract.`)
      const updatedMethod: UmlMethod = {
        ...target,
        name: op.method.name,
        returnType: op.method.returnType ?? target.returnType,
        visibility: op.method.visibility ? normalizeVisibility(op.method.visibility) : target.visibility,
        parameters: nextParams,
        ...(nextStatic === undefined ? {} : { static: nextStatic }),
        ...(nextAbstract === undefined ? {} : { abstract: nextAbstract }),
      }
      const methods = node.data.methods ?? []
      if (methods.some(m => m.id !== target.id && key(m.name) === key(updatedMethod.name) && sameParameterTypes(methodSignatureTypes(m), methodSignatureTypes(updatedMethod)))) {
        fail(`La clase "${node.data.name}" ya contiene otro método "${updatedMethod.name}" con la misma firma`)
      }
      nodes = nodes.map(n => n.id === node.id ? {
        ...n,
        data: { ...n.data, methods: methods.map(m => m.id === target.id ? updatedMethod : m) },
      } : n)
    } else if (op.type === 'DELETE_METHOD') {
      const node = findMethodCarrier(op.entityName)
      const target = findMethod(node, op.methodName, op.parameterTypes)
      nodes = nodes.map(n => n.id === node.id ? {
        ...n,
        data: { ...n.data, methods: (n.data.methods ?? []).filter(m => m.id !== target.id) },
      } : n)
    } else if (op.type === 'ADD_METHOD_PARAMETER') {
      const node = findMethodCarrier(op.entityName)
      const target = findMethod(node, op.methodName, op.parameterTypes)
      if (target.parameters.some(p => key(p.name) === key(op.parameter.name))) {
        fail(`El método "${target.name}" ya contiene un parámetro llamado "${op.parameter.name}"`)
      }
      const nextParams = [...target.parameters, convertParameter(op.parameter)]
      const methods = node.data.methods ?? []
      if (methods.some(m => m.id !== target.id && key(m.name) === key(target.name) && sameParameterTypes(methodSignatureTypes(m), nextParams.map(p => key(p.type))))) {
        fail(`Agregar el parámetro genera una sobrecarga duplicada en "${target.name}"`)
      }
      nodes = nodes.map(n => n.id === node.id ? {
        ...n,
        data: { ...n.data, methods: methods.map(m => m.id === target.id ? { ...m, parameters: nextParams } : m) },
      } : n)
    } else if (op.type === 'UPDATE_METHOD_PARAMETER') {
      const node = findMethodCarrier(op.entityName)
      const target = findMethod(node, op.methodName, op.parameterTypes)
      const paramIdx = target.parameters.findIndex(p => key(p.name) === key(op.parameterName))
      if (paramIdx < 0) fail(`El parámetro "${op.parameterName}" no existe en "${target.name}"`)
      if (target.parameters.some((p, idx) => idx !== paramIdx && key(p.name) === key(op.parameter.name))) {
        fail(`El método "${target.name}" ya contiene otro parámetro llamado "${op.parameter.name}"`)
      }
      const nextParams = target.parameters.map((p, idx) =>
        idx === paramIdx ? { ...p, name: op.parameter.name, type: op.parameter.dataType } : p,
      )
      const methods = node.data.methods ?? []
      if (methods.some(m => m.id !== target.id && key(m.name) === key(target.name) && sameParameterTypes(methodSignatureTypes(m), nextParams.map(p => key(p.type))))) {
        fail(`Actualizar el parámetro genera una sobrecarga duplicada en "${target.name}"`)
      }
      nodes = nodes.map(n => n.id === node.id ? {
        ...n,
        data: { ...n.data, methods: methods.map(m => m.id === target.id ? { ...m, parameters: nextParams } : m) },
      } : n)
    } else if (op.type === 'DELETE_METHOD_PARAMETER') {
      const node = findMethodCarrier(op.entityName)
      const target = findMethod(node, op.methodName, op.parameterTypes)
      if (!target.parameters.some(p => key(p.name) === key(op.parameterName))) {
        fail(`El parámetro "${op.parameterName}" no existe en "${target.name}"`)
      }
      const nextParams = target.parameters.filter(p => key(p.name) !== key(op.parameterName))
      const methods = node.data.methods ?? []
      if (methods.some(m => m.id !== target.id && key(m.name) === key(target.name) && sameParameterTypes(methodSignatureTypes(m), nextParams.map(p => key(p.type))))) {
        fail(`Eliminar el parámetro genera una sobrecarga duplicada en "${target.name}"`)
      }
      nodes = nodes.map(n => n.id === node.id ? {
        ...n,
        data: { ...n.data, methods: methods.map(m => m.id === target.id ? { ...m, parameters: nextParams } : m) },
      } : n)
    } else if (op.type === 'ADD_ENUM_LITERAL') {
      const node = findEnumeration(op.entityName)
      const literals = node.data.literals ?? []
      if (literals.some(lit => key(lit) === key(op.literal))) {
        fail(`La enumeración "${node.data.name}" ya contiene el literal "${op.literal}"`)
      }
      nodes = nodes.map(n => n.id === node.id ? { ...n, data: { ...n.data, literals: [...literals, op.literal] } } : n)
    } else if (op.type === 'RENAME_ENUM_LITERAL') {
      const node = findEnumeration(op.entityName)
      const literals = node.data.literals ?? []
      const idx = literals.findIndex(lit => key(lit) === key(op.literal))
      if (idx < 0) fail(`El literal "${op.literal}" no existe en la enumeración "${node.data.name}"`)
      if (literals.some((lit, i) => i !== idx && key(lit) === key(op.newName))) {
        fail(`La enumeración "${node.data.name}" ya contiene el literal "${op.newName}"`)
      }
      const nextLiterals = literals.map((lit, i) => (i === idx ? op.newName : lit))
      nodes = nodes.map(n => n.id === node.id ? { ...n, data: { ...n.data, literals: nextLiterals } } : n)
    } else if (op.type === 'DELETE_ENUM_LITERAL') {
      const node = findEnumeration(op.entityName)
      const literals = node.data.literals ?? []
      if (!literals.some(lit => key(lit) === key(op.literal))) {
        fail(`El literal "${op.literal}" no existe en la enumeración "${node.data.name}"`)
      }
      nodes = nodes.map(n => n.id === node.id ? {
        ...n,
        data: { ...n.data, literals: literals.filter(lit => key(lit) !== key(op.literal)) },
      } : n)
    } else if (op.type === 'ADD_RELATIONSHIP') {
      const relKind = op.relationship.kind ?? 'ASSOCIATION'
      const nonMultiplicative = NON_MULTIPLICATIVE_RELATIONS.has(relKind)
      const sourceNode = nonMultiplicative
        ? findClassifier(op.relationship.sourceEntity)
        : findAttributeCarrier(op.relationship.sourceEntity)
      const targetNode = nonMultiplicative
        ? findClassifier(op.relationship.targetEntity)
        : findAttributeCarrier(op.relationship.targetEntity)
      const source = sourceNode.id
      const target = targetNode.id
      if (source === target) fail('Las autorrelaciones nuevas no están habilitadas en este MVP')
      if (nonMultiplicative) {
        const srcKind = sourceNode.data.kind ?? 'ENTITY'
        const tgtKind = targetNode.data.kind ?? 'ENTITY'
        if (relKind === 'REALIZATION' && (!isClassLike(srcKind) || tgtKind !== 'INTERFACE')) {
          fail('Una realización debe partir de una clase o entidad y terminar en una interfaz.')
        }
        if (relKind === 'GENERALIZATION' && !((isClassLike(srcKind) && isClassLike(tgtKind)) || (srcKind === 'INTERFACE' && tgtKind === 'INTERFACE'))) {
          fail('Una generalización debe conectar clases entre sí o interfaces entre sí.')
        }
        if (op.relationship.sourceMultiplicity || op.relationship.targetMultiplicity) {
          fail(`La relación ${relKind} no admite multiplicidades.`)
        }
        if (edges.some(e => e.source === source && e.target === target && edgeRelationKind(e) === relKind)) {
          continue
        }
        const edge = normalizeRelationshipEdge({
          id: uniqueId('edge'),
          source,
          target,
          data: {
            relationKind: relKind,
            sourceCardinality: 'ONE_ONE',
            targetCardinality: 'ONE_ONE',
            ...(op.relationship.name == null ? {} : { name: op.relationship.name }),
          },
        })
        edges.push(edge)
        continue
      }
      if (!op.relationship.sourceCardinality || !op.relationship.targetCardinality) {
        fail('La relación debe declarar ambas cardinalidades.')
      }
      if (op.relationship.aggregationEnd && !['AGGREGATION', 'COMPOSITION'].includes(relKind)) {
        fail('El extremo de agregación no coincide con el tipo de relación.')
      }
      const candidate: DiagramEdge = { id: '', source, target, data: {
        ...(op.relationship.kind == null ? {} : { relationKind: relKind }),
        sourceCardinality: op.relationship.sourceCardinality,
        targetCardinality: op.relationship.targetCardinality,
        ...(op.relationship.name == null ? {} : { name: op.relationship.name }),
        ...(op.relationship.joinTableName == null ? {} : { joinTableName: op.relationship.joinTableName }),
        ...(op.relationship.sourceMultiplicity == null ? {} : { sourceMultiplicity: op.relationship.sourceMultiplicity }),
        ...(op.relationship.targetMultiplicity == null ? {} : { targetMultiplicity: op.relationship.targetMultiplicity }),
        ...(op.relationship.aggregationEnd == null ? {} : { aggregationEnd: op.relationship.aggregationEnd }),
      } }
      if (edges.some(e => isMultiplicativeEdge(e) && edgeRelationKind(e) === relKind && equivalentRelationship(e, candidate))) continue
      const edge = normalizeRelationshipEdge({ ...candidate, id: uniqueId('edge') })
      edges.push(edge)
    } else if (op.type === 'DELETE_RELATIONSHIP') {
      const { edge } = findRelationship(op.relationship)
      edges = edges.filter(candidate => candidate.id !== edge.id)
    } else if (op.type === 'UPDATE_RELATIONSHIP') {
      const { edge, source } = findRelationship(op.relationship, true)
      const direct = edge.source === source.id
      const nextKind = op.relationship.kind ?? edgeRelationKind(edge)
      const nonMultiplicative = NON_MULTIPLICATIVE_RELATIONS.has(nextKind)
      edges = edges.map(candidate => {
        if (candidate.id !== edge.id) return candidate
        const nextData: NonNullable<DiagramEdge['data']> = {
          ...candidate.data,
          ...(op.relationship.kind == null ? {} : { relationKind: op.relationship.kind }),
          ...(op.relationship.name == null ? {} : { name: op.relationship.name }),
          ...(op.relationship.joinTableName == null ? {} : { joinTableName: op.relationship.joinTableName }),
        }
        if (nonMultiplicative) {
          delete nextData.sourceMultiplicity
          delete nextData.targetMultiplicity
          delete nextData.aggregationEnd
        } else {
          if (op.relationship.sourceCardinality && op.relationship.targetCardinality) {
            nextData.sourceCardinality = direct ? op.relationship.sourceCardinality : op.relationship.targetCardinality
            nextData.targetCardinality = direct ? op.relationship.targetCardinality : op.relationship.sourceCardinality
          }
          if (op.relationship.sourceMultiplicity != null) nextData.sourceMultiplicity = op.relationship.sourceMultiplicity
          if (op.relationship.targetMultiplicity != null) nextData.targetMultiplicity = op.relationship.targetMultiplicity
          if (op.relationship.aggregationEnd != null) nextData.aggregationEnd = op.relationship.aggregationEnd
        }
        return normalizeRelationshipEdge({ ...candidate, data: nextData })
      })
    } else if (op.type === 'CREATE_ASSOCIATION') {
      const source = findAttributeCarrier(op.association.sourceEntity)
      const target = findAttributeCarrier(op.association.targetEntity)
      if (source.id === target.id) fail('Las autorrelaciones nuevas no están habilitadas en este MVP')
      const structural = structuralRelationshipIds(nodes)
      const candidates = edges.filter(edge => !structural.has(edge.id)
        && isAssociationEdge(edge)
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
      const association = findAttributeCarrier(op.associationEntityName)
      deleteAssociation(association)
    } else {
      const original = edges.find(edge => edge.id === op.conversion.relationshipId)
        ?? fail('La relacion N:M indicada no existe o ya fue convertida')
      const source = findAttributeCarrier(op.conversion.sourceEntity)
      const target = findAttributeCarrier(op.conversion.targetEntity)
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
  const hasUmlV2 =
    nodes.some(
      n =>
        (n.data.kind && n.data.kind !== 'ENTITY') ||
        (n.data.methods?.length ?? 0) > 0 ||
        (n.data.literals?.length ?? 0) > 0 ||
        n.data.abstract !== undefined,
    ) || edges.some(e => e.data?.relationKind && e.data.relationKind !== 'ASSOCIATION')
  validateDiagramDocument({ version: hasUmlV2 ? 2 : 1, nodes, edges })
  const normalizedCurrentEdges = currentEdges.map(edge => normalizeRelationshipEdge({ ...edge, data: { ...edge.data } }))
  const changed = JSON.stringify({ nodes: currentNodes, edges: normalizedCurrentEdges }) !== JSON.stringify({ nodes, edges })
  return { nodes, edges, events: changed
    ? [{ type: 'DIAGRAM_BATCH_APPLIED' as const, document: { version: 1 as const, nodes, edges } }]
    : [] }
}
