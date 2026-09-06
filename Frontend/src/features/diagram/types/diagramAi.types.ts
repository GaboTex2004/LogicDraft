export type AiDataType = 'String' | 'Long' | 'Integer' | 'Double' | 'Boolean' | 'Date' | 'DateTime'
import type { DiagramCardinality } from './diagram.types'
export interface AiAttribute {
  name: string
  dataType: AiDataType
  primaryKey: boolean
  nullable: boolean
}
export type DiagramAiOperation =
  | { type: 'ADD_ENTITY'; entity: { name: string; attributes: AiAttribute[] } }
  | { type: 'ADD_ATTRIBUTE'; entityName: string; attribute: AiAttribute }
  | { type: 'ADD_RELATIONSHIP'; relationship: { sourceEntity: string; targetEntity: string; sourceCardinality: DiagramCardinality; targetCardinality: DiagramCardinality } }
