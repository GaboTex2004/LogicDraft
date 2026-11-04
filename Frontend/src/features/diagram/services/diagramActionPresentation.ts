import type { DiagramEdge, EntityFlowNode } from '../types/diagram.types.ts'
import type { DiagramAiOperation } from '../types/diagramAi.types.ts'

export function diagramOperationLabel(operation: DiagramAiOperation): string {
  switch (operation.type) {
    case 'ADD_ENTITY': {
      const kind = operation.entity.kind
      const kindLabel =
        kind === 'CLASS'
          ? 'clase UML'
          : kind === 'INTERFACE'
            ? 'interfaz'
            : kind === 'ENUMERATION'
              ? 'enumeración'
              : kind === 'ASSOCIATION_CLASS'
                ? 'clase asociativa'
                : 'entidad'
      return `Crear ${kindLabel} ${operation.entity.name}`
    }
    case 'DELETE_ENTITY': return `Eliminar clasificador ${operation.entityName}`
    case 'RENAME_ENTITY': return `Renombrar ${operation.entityName} como ${operation.newName}`
    case 'UPDATE_CLASSIFIER': return `Actualizar propiedades UML de ${operation.entityName}`
    case 'ADD_ATTRIBUTE': return `Agregar ${operation.attribute.name} ${operation.attribute.dataType} a ${operation.entityName}`
    case 'DELETE_ATTRIBUTE': return `Eliminar ${operation.attributeName} de ${operation.entityName}`
    case 'RENAME_ATTRIBUTE': return `Renombrar ${operation.attributeName} como ${operation.newName} en ${operation.entityName}`
    case 'CHANGE_ATTRIBUTE_TYPE': return `Cambiar ${operation.attributeName} de ${operation.entityName} a ${operation.dataType}`
    case 'SET_ATTRIBUTE_PRIMARY_KEY': return `${operation.value ? 'Marcar' : 'Quitar'} PK en ${operation.entityName}.${operation.attributeName}`
    case 'SET_ATTRIBUTE_NULLABLE': return `${operation.value ? 'Permitir' : 'Impedir'} null en ${operation.entityName}.${operation.attributeName}`
    case 'SET_ATTRIBUTE_VISIBILITY': return `Cambiar visibilidad de ${operation.entityName}.${operation.attributeName} a ${operation.visibility}`
    case 'SET_ATTRIBUTE_STATIC': return `${operation.value ? 'Marcar' : 'Quitar'} static en ${operation.entityName}.${operation.attributeName}`
    case 'ADD_METHOD': {
      const params = (operation.method.parameters ?? []).map(p => `${p.name}: ${p.dataType}`).join(', ')
      return `Agregar método ${operation.method.name}(${params}) a ${operation.entityName}`
    }
    case 'UPDATE_METHOD': return `Actualizar método ${operation.methodName} en ${operation.entityName}`
    case 'DELETE_METHOD': return `Eliminar método ${operation.methodName} de ${operation.entityName}`
    case 'ADD_METHOD_PARAMETER': return `Agregar parámetro ${operation.parameter.name}: ${operation.parameter.dataType} en ${operation.entityName}.${operation.methodName}`
    case 'UPDATE_METHOD_PARAMETER': return `Actualizar parámetro ${operation.parameterName} en ${operation.entityName}.${operation.methodName}`
    case 'DELETE_METHOD_PARAMETER': return `Eliminar parámetro ${operation.parameterName} de ${operation.entityName}.${operation.methodName}`
    case 'ADD_ENUM_LITERAL': return `Agregar literal ${operation.literal} a ${operation.entityName}`
    case 'RENAME_ENUM_LITERAL': return `Renombrar literal ${operation.literal} como ${operation.newName} en ${operation.entityName}`
    case 'DELETE_ENUM_LITERAL': return `Eliminar literal ${operation.literal} de ${operation.entityName}`
    case 'ADD_RELATIONSHIP': {
      const kind = operation.relationship.kind
      const suffix = kind && kind !== 'ASSOCIATION' ? ` (${kind})` : ''
      return `Crear relación${suffix} ${operation.relationship.sourceEntity} - ${operation.relationship.targetEntity}`
    }
    case 'DELETE_RELATIONSHIP': {
      const kind = operation.relationship.kind
      const suffix = kind && kind !== 'ASSOCIATION' ? ` (${kind})` : ''
      return `Eliminar relación${suffix} ${operation.relationship.sourceEntity} - ${operation.relationship.targetEntity}`
    }
    case 'UPDATE_RELATIONSHIP': return `Actualizar relación ${operation.relationship.sourceEntity} - ${operation.relationship.targetEntity}`
    case 'CREATE_ASSOCIATION': return `Crear asociación ${operation.association.associationEntityName} entre ${operation.association.sourceEntity} y ${operation.association.targetEntity}`
    case 'DELETE_ASSOCIATION': return `Eliminar asociación ${operation.associationEntityName}`
    case 'CONVERT_MANY_TO_MANY_ASSOCIATION': return `Convertir la relación en ${operation.conversion.associationEntityName}`
  }
}

export function destructivePlanImpact(operations: readonly DiagramAiOperation[], nodes: readonly EntityFlowNode[],
  edges: readonly DiagramEdge[]): string[] {
  const result = new Set<string>()
  for (const operation of operations) {
    if (operation.type === 'DELETE_ENTITY') {
      const node = nodes.find(candidate => candidate.data.name.toLocaleLowerCase() === operation.entityName.toLocaleLowerCase())
      if (!node) continue
      for (const edge of edges.filter(candidate => candidate.source === node.id || candidate.target === node.id)) {
        const otherId = edge.source === node.id ? edge.target : edge.source
        const other = nodes.find(candidate => candidate.id === otherId)?.data.name ?? 'entidad desconocida'
        result.add(`relación ${node.data.name} - ${other}`)
      }
      for (const association of nodes.filter(candidate => candidate.data.association?.endpoints.some(endpoint => endpoint.entityId === node.id)))
        result.add(`asociación ${association.data.name}`)
    } else if (operation.type === 'DELETE_ASSOCIATION') {
      const association = nodes.find(candidate => candidate.data.name.toLocaleLowerCase() === operation.associationEntityName.toLocaleLowerCase())
      for (const endpoint of association?.data.association?.endpoints ?? []) {
        const entity = nodes.find(candidate => candidate.id === endpoint.entityId)?.data.name ?? 'entidad desconocida'
        result.add(`relación estructural con ${entity}`)
      }
    }
  }
  return [...result]
}
