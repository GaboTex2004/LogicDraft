export type AiDataType = 'String' | 'Long' | 'Integer' | 'Double' | 'Boolean' | 'Date' | 'DateTime'
import type { DiagramCardinality } from './diagram.types'
export interface AiAttribute {
  name: string
  dataType: AiDataType
  primaryKey: boolean
  nullable: boolean
}
export interface AiRelationshipRef {
  sourceEntity: string
  targetEntity: string
  name?: string
}
export interface AiAssociationDefinition {
  sourceEntity: string
  targetEntity: string
  associationEntityName: string
  attributes: AiAttribute[]
}
export type DiagramAiOperation =
  | { type: 'ADD_ENTITY'; entity: { name: string; attributes: AiAttribute[] } }
  | { type: 'DELETE_ENTITY'; entityName: string }
  | { type: 'RENAME_ENTITY'; entityName: string; newName: string }
  | { type: 'ADD_ATTRIBUTE'; entityName: string; attribute: AiAttribute }
  | { type: 'DELETE_ATTRIBUTE'; entityName: string; attributeName: string }
  | { type: 'RENAME_ATTRIBUTE'; entityName: string; attributeName: string; newName: string }
  | { type: 'CHANGE_ATTRIBUTE_TYPE'; entityName: string; attributeName: string; dataType: AiDataType }
  | { type: 'SET_ATTRIBUTE_PRIMARY_KEY'; entityName: string; attributeName: string; value: boolean }
  | { type: 'SET_ATTRIBUTE_NULLABLE'; entityName: string; attributeName: string; value: boolean }
  | { type: 'ADD_RELATIONSHIP'; relationship: { sourceEntity: string; targetEntity: string; sourceCardinality: DiagramCardinality; targetCardinality: DiagramCardinality; name?: string; joinTableName?: string } }
  | { type: 'DELETE_RELATIONSHIP'; relationship: AiRelationshipRef }
  | { type: 'UPDATE_RELATIONSHIP'; relationship: AiRelationshipRef & { sourceCardinality: DiagramCardinality; targetCardinality: DiagramCardinality } }
  | { type: 'CREATE_ASSOCIATION'; association: AiAssociationDefinition }
  | { type: 'DELETE_ASSOCIATION'; associationEntityName: string }
  | { type: 'CONVERT_MANY_TO_MANY_ASSOCIATION'; conversion: {
      relationshipId: string
      sourceEntity: string
      targetEntity: string
      associationEntityName: string
      attributes: AiAttribute[]
    } }
