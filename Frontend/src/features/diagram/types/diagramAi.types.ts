export type AiDataType = 'String' | 'Long' | 'Integer' | 'Double' | 'Boolean' | 'Date' | 'DateTime'
import type { DiagramCardinality, UmlNodeKind, UmlRelationKind } from './diagram.types'

export type AiVisibility =
  | 'PUBLIC'
  | 'PRIVATE'
  | 'PROTECTED'
  | 'PACKAGE'
  | 'public'
  | 'private'
  | 'protected'
  | 'package'

export type AiAggregationEnd = 'SOURCE' | 'TARGET'

export interface AiAttribute {
  name: string
  dataType: AiDataType
  primaryKey: boolean
  nullable: boolean
  visibility?: AiVisibility
  static?: boolean
}

export interface AiParameter {
  name: string
  dataType: string
}

export interface AiMethod {
  name: string
  returnType?: string
  visibility?: AiVisibility
  static?: boolean
  abstract?: boolean
  parameters?: AiParameter[]
}

export interface AiEntityDefinition {
  name: string
  attributes: AiAttribute[]
  kind?: UmlNodeKind
  abstract?: boolean
  methods?: AiMethod[]
  literals?: string[]
}

export interface AiRelationshipRef {
  sourceEntity: string
  targetEntity: string
  name?: string
  id?: string
  kind?: UmlRelationKind
  sourceMultiplicity?: string
  targetMultiplicity?: string
  aggregationEnd?: AiAggregationEnd
}

export interface AiAssociationDefinition {
  sourceEntity: string
  targetEntity: string
  associationEntityName: string
  attributes: AiAttribute[]
}

export type DiagramAiOperation =
  | { type: 'ADD_ENTITY'; entity: AiEntityDefinition }
  | { type: 'DELETE_ENTITY'; entityName: string }
  | { type: 'RENAME_ENTITY'; entityName: string; newName: string }
  | { type: 'UPDATE_CLASSIFIER'; entityName: string; classifierKind?: UmlNodeKind; abstract?: boolean }
  | { type: 'ADD_ATTRIBUTE'; entityName: string; attribute: AiAttribute }
  | { type: 'DELETE_ATTRIBUTE'; entityName: string; attributeName: string }
  | { type: 'RENAME_ATTRIBUTE'; entityName: string; attributeName: string; newName: string }
  | { type: 'CHANGE_ATTRIBUTE_TYPE'; entityName: string; attributeName: string; dataType: AiDataType }
  | { type: 'SET_ATTRIBUTE_PRIMARY_KEY'; entityName: string; attributeName: string; value: boolean }
  | { type: 'SET_ATTRIBUTE_NULLABLE'; entityName: string; attributeName: string; value: boolean }
  | { type: 'SET_ATTRIBUTE_VISIBILITY'; entityName: string; attributeName: string; visibility: AiVisibility }
  | { type: 'SET_ATTRIBUTE_STATIC'; entityName: string; attributeName: string; value: boolean }
  | { type: 'ADD_METHOD'; entityName: string; method: AiMethod }
  | { type: 'UPDATE_METHOD'; entityName: string; methodName: string; parameterTypes?: string[]; method: AiMethod }
  | { type: 'DELETE_METHOD'; entityName: string; methodName: string; parameterTypes?: string[] }
  | { type: 'ADD_METHOD_PARAMETER'; entityName: string; methodName: string; parameterTypes?: string[]; parameter: AiParameter }
  | {
      type: 'UPDATE_METHOD_PARAMETER'
      entityName: string
      methodName: string
      parameterTypes?: string[]
      parameterName: string
      parameter: AiParameter
    }
  | { type: 'DELETE_METHOD_PARAMETER'; entityName: string; methodName: string; parameterTypes?: string[]; parameterName: string }
  | { type: 'ADD_ENUM_LITERAL'; entityName: string; literal: string }
  | { type: 'RENAME_ENUM_LITERAL'; entityName: string; literal: string; newName: string }
  | { type: 'DELETE_ENUM_LITERAL'; entityName: string; literal: string }
  | {
      type: 'ADD_RELATIONSHIP'
      relationship: AiRelationshipRef & {
        sourceCardinality?: DiagramCardinality
        targetCardinality?: DiagramCardinality
        joinTableName?: string
      }
    }
  | { type: 'DELETE_RELATIONSHIP'; relationship: AiRelationshipRef }
  | {
      type: 'UPDATE_RELATIONSHIP'
      relationship: AiRelationshipRef & {
        sourceCardinality?: DiagramCardinality
        targetCardinality?: DiagramCardinality
        joinTableName?: string
      }
    }
  | { type: 'CREATE_ASSOCIATION'; association: AiAssociationDefinition }
  | { type: 'DELETE_ASSOCIATION'; associationEntityName: string }
  | {
      type: 'CONVERT_MANY_TO_MANY_ASSOCIATION'
      conversion: {
        relationshipId: string
        sourceEntity: string
        targetEntity: string
        associationEntityName: string
        attributes: AiAttribute[]
      }
    }
