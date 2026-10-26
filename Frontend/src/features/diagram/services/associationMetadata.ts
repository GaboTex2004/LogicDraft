import type {
  AssociationEndpoint,
  AssociativeEntityMetadata,
  DiagramCardinality,
  ExternalMetadata,
} from '../types/diagram.types.ts'

const cardinalities: DiagramCardinality[] = ['ZERO_ONE', 'ONE_ONE', 'ZERO_MANY', 'ONE_MANY']

function record(value: unknown): Record<string, unknown> | null {
  return typeof value === 'object' && value !== null && !Array.isArray(value)
    ? value as Record<string, unknown> : null
}

function identifier(value: unknown): value is string {
  return typeof value === 'string' && value.length > 0 && value.length <= 200 && value === value.trim()
}

function physicalName(value: unknown, maxLength: number): value is string {
  return typeof value === 'string' && value.length <= maxLength && /^[a-z][a-z0-9_]*$/.test(value)
}

function cardinality(value: unknown): value is DiagramCardinality {
  return typeof value === 'string' && cardinalities.includes(value as DiagramCardinality)
}

function externalMetadata(value: unknown): ExternalMetadata | null {
  const item = record(value)
  return item?.source === 'enterprise-architect' && identifier(item.externalId)
    ? { source: 'enterprise-architect', externalId: item.externalId }
    : null
}

function endpoint(value: unknown): AssociationEndpoint | null {
  const item = record(value)
  if (!item || (item.role !== 'SOURCE' && item.role !== 'TARGET')
    || !identifier(item.entityId) || !identifier(item.relationshipId)
    || !physicalName(item.foreignKeyName, 63)) return null
  return {
    role: item.role,
    entityId: item.entityId,
    relationshipId: item.relationshipId,
    foreignKeyName: item.foreignKeyName,
  }
}

export function parseAssociationMetadata(value: unknown): AssociativeEntityMetadata | null {
  const item = record(value)
  if (!item || item.kind !== 'MANY_TO_MANY_ASSOCIATION' || item.uniquePair !== true
    || !physicalName(item.tableName, 55)
    || !Array.isArray(item.endpoints) || item.endpoints.length !== 2) return null
  const first = endpoint(item.endpoints[0])
  const second = endpoint(item.endpoints[1])
  if (!first || !second || first.role === second.role) return null
  if (item.sourceMultiplicity !== undefined && !cardinality(item.sourceMultiplicity)) return null
  if (item.targetMultiplicity !== undefined && !cardinality(item.targetMultiplicity)) return null
  const metadata = item.externalMetadata === undefined ? undefined : externalMetadata(item.externalMetadata)
  if (item.externalMetadata !== undefined && !metadata) return null
  return {
    kind: item.kind,
    tableName: item.tableName,
    uniquePair: true,
    endpoints: [first, second],
    ...(item.sourceMultiplicity !== undefined ? { sourceMultiplicity: item.sourceMultiplicity } : {}),
    ...(item.targetMultiplicity !== undefined ? { targetMultiplicity: item.targetMultiplicity } : {}),
    ...(metadata ? { externalMetadata: metadata } : {}),
  }
}

export function cloneAssociationMetadata(value: AssociativeEntityMetadata): AssociativeEntityMetadata {
  return {
    kind: value.kind,
    tableName: value.tableName,
    uniquePair: true,
    endpoints: value.endpoints.map(item => ({ ...item })) as [AssociationEndpoint, AssociationEndpoint],
    ...(value.sourceMultiplicity ? { sourceMultiplicity: value.sourceMultiplicity } : {}),
    ...(value.targetMultiplicity ? { targetMultiplicity: value.targetMultiplicity } : {}),
    ...(value.externalMetadata ? { externalMetadata: { ...value.externalMetadata } } : {}),
  }
}
