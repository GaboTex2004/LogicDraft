import type { Edge, Node } from '@xyflow/react'

export const ATTRIBUTE_TYPES = [
  'BIGINT',
  'INTEGER',
  'VARCHAR',
  'TEXT',
  'BOOLEAN',
  'DATE',
  'TIMESTAMP',
  'DECIMAL',
] as const

export type AttributeType = (typeof ATTRIBUTE_TYPES)[number]

export interface ExternalMetadata {
  source: 'enterprise-architect'
  externalId: string
}

export interface EntityAttribute {
  id: string
  name: string
  type: AttributeType
  primaryKey: boolean
  nullable?: boolean
  externalMetadata?: ExternalMetadata
  visibility?: UmlVisibility
  static?: boolean
}

export const UML_VISIBILITIES = ['public', 'private', 'protected', 'package'] as const
export type UmlVisibility = (typeof UML_VISIBILITIES)[number]
export const UML_NODE_KINDS = ['ENTITY', 'CLASS', 'INTERFACE', 'ENUMERATION', 'ASSOCIATION_CLASS'] as const
export type UmlNodeKind = (typeof UML_NODE_KINDS)[number]
export const UML_RELATION_KINDS = ['ASSOCIATION', 'GENERALIZATION', 'REALIZATION', 'AGGREGATION', 'COMPOSITION', 'DEPENDENCY'] as const
export type UmlRelationKind = (typeof UML_RELATION_KINDS)[number]

export interface UmlParameter {
  id: string
  name: string
  type: string
}

export interface UmlMethod {
  id: string
  name: string
  visibility: UmlVisibility
  returnType: string
  parameters: UmlParameter[]
  static?: boolean
  abstract?: boolean
}

export type AssociationEndpointRole = 'SOURCE' | 'TARGET'

export interface AssociationEndpoint {
  role: AssociationEndpointRole
  entityId: string
  relationshipId: string
  foreignKeyName: string
}

export interface AssociativeEntityMetadata {
  kind: 'MANY_TO_MANY_ASSOCIATION'
  tableName: string
  endpoints: [AssociationEndpoint, AssociationEndpoint]
  uniquePair: true
  sourceMultiplicity?: DiagramCardinality
  targetMultiplicity?: DiagramCardinality
  externalMetadata?: ExternalMetadata
}

export interface DiagramEntity {
  id: string
  name: string
  attributes: EntityAttribute[]
  association?: AssociativeEntityMetadata
  externalMetadata?: ExternalMetadata
  kind?: UmlNodeKind
  /** Opt-in to generate persistence + CRUD for a UML CLASS. */
  generateCrud?: boolean
  methods?: UmlMethod[]
  literals?: string[]
  abstract?: boolean
  width?: number
  height?: number
}

export type EntityNodeData = DiagramEntity & Record<string, unknown>
export type EntityFlowNode = Node<EntityNodeData, 'entity'>
export const DIAGRAM_CARDINALITIES = ['ZERO_ONE', 'ONE_ONE', 'ZERO_MANY', 'ONE_MANY'] as const
export type DiagramCardinality = (typeof DIAGRAM_CARDINALITIES)[number]
export type RelationshipData = Record<string, unknown> & {
  sourceCardinality?: DiagramCardinality
  targetCardinality?: DiagramCardinality
  name?: string
  joinTableName?: string
  externalMetadata?: ExternalMetadata
  relationKind?: UmlRelationKind
  aggregationEnd?: 'SOURCE' | 'TARGET'
  sourceMultiplicity?: string
  targetMultiplicity?: string
}
export type DiagramEdge = Edge<RelationshipData>

export interface DiagramDocument {
  version: 1 | 2
  nodes: EntityFlowNode[]
  edges: DiagramEdge[]
}

export interface DiagramaResponse {
  id: number
  proyectoId: number
  version: number
  revision: number
  contenido: DiagramDocument
  fechaActualizacion: string
}
