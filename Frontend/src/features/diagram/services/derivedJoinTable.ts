import type { AttributeType, DiagramEdge, DiagramEntity } from '../types/diagram.types.ts'
import { cardinalities } from './relationshipCardinality.ts'

export interface DerivedJoinColumn {
  name: string
  type: string
  referencedEntity: string
}

export interface DerivedJoinTable {
  name: string
  columns: readonly [DerivedJoinColumn, DerivedJoinColumn]
  uniqueConstraint: string
}

const POSTGRES_TYPES: Record<AttributeType, string> = {
  BIGINT: 'BIGINT',
  INTEGER: 'INTEGER',
  VARCHAR: 'VARCHAR',
  TEXT: 'VARCHAR',
  BOOLEAN: 'BOOLEAN',
  DATE: 'DATE',
  TIMESTAMP: 'TIMESTAMP',
  DECIMAL: 'NUMERIC',
}

export function postgresType(type: AttributeType): string {
  return POSTGRES_TYPES[type]
}

export function technicalName(displayName: string): string {
  const words = displayName.trim().normalize('NFD').replace(/\p{M}+/gu, '')
    .replace(/([a-z0-9])([A-Z])/g, '$1 $2').replace(/[^A-Za-z0-9]+/g, ' ').trim().split(/ +/)
  const pascal = words.map(word => word.charAt(0).toUpperCase() + word.slice(1).toLowerCase()).join('')
  return /^\d/.test(pascal) ? `N${pascal}` : pascal
}

export function sqlName(value: string): string {
  return value.replace(/([a-z0-9])([A-Z])/g, '$1_$2').toLowerCase()
}

function foreignKey(entity: DiagramEntity): DerivedJoinColumn {
  const typeName = technicalName(entity.name)
  const primaryKey = entity.attributes.find(attribute => attribute.primaryKey)
  return {
    name: sqlName(`${typeName}Id`),
    type: primaryKey ? postgresType(primaryKey.type) : 'PK no definida',
    referencedEntity: entity.name,
  }
}

/** Builds presentation-only metadata. The returned table is never persisted as a node. */
export function deriveJoinTable(
  edge: DiagramEdge,
  source: DiagramEntity | undefined,
  target: DiagramEntity | undefined,
): DerivedJoinTable | null {
  const cards = cardinalities(edge.data)
  if (!cards.sourceCardinality.endsWith('MANY') || !cards.targetCardinality.endsWith('MANY') || !source || !target) {
    return null
  }
  const sourceName = technicalName(source.name)
  const targetName = technicalName(target.name)
  const customTable = typeof edge.data?.joinTableName === 'string' ? edge.data.joinTableName.trim() : ''
  const relationshipName = typeof edge.data?.name === 'string' ? edge.data.name.trim() : ''
  const technicalTable = customTable
    ? technicalName(customTable)
    : `${sourceName}${targetName}${relationshipName ? technicalName(relationshipName) : ''}`
  const name = sqlName(technicalTable)
  return {
    name,
    columns: [foreignKey(source), foreignKey(target)],
    uniqueConstraint: `uk_${name}_pair`,
  }
}
