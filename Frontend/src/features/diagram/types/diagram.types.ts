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

export interface EntityAttribute {
  id: string
  name: string
  type: AttributeType
  primaryKey: boolean
  nullable?: boolean
}

export interface DiagramEntity {
  id: string
  name: string
  attributes: EntityAttribute[]
}

export type EntityNodeData = DiagramEntity & Record<string, unknown>
export type EntityFlowNode = Node<EntityNodeData, 'entity'>
export const DIAGRAM_CARDINALITIES = ['ZERO_ONE', 'ONE_ONE', 'ZERO_MANY', 'ONE_MANY'] as const
export type DiagramCardinality = (typeof DIAGRAM_CARDINALITIES)[number]
export type RelationshipData = Record<string, unknown> & {
  sourceCardinality?: DiagramCardinality
  targetCardinality?: DiagramCardinality
}
export type DiagramEdge = Edge<RelationshipData>

export interface DiagramDocument {
  version: 1
  nodes: EntityFlowNode[]
  edges: DiagramEdge[]
}

export interface DiagramaResponse {
  id: number
  proyectoId: number
  version: number
  contenido: DiagramDocument
  fechaActualizacion: string
}
