import type { DiagramEdge, EntityFlowNode } from '../types/diagram.types.ts'
import type { DiagramAiOperation } from '../types/diagramAi.types.ts'

export function diagramOperationLabel(operation: DiagramAiOperation): string {
  switch (operation.type) {
    case 'ADD_ENTITY': return `Crear entidad ${operation.entity.name}`
    case 'DELETE_ENTITY': return `Eliminar entidad ${operation.entityName}`
    case 'RENAME_ENTITY': return `Renombrar ${operation.entityName} como ${operation.newName}`
    case 'ADD_ATTRIBUTE': return `Agregar ${operation.attribute.name} ${operation.attribute.dataType} a ${operation.entityName}`
    case 'DELETE_ATTRIBUTE': return `Eliminar ${operation.attributeName} de ${operation.entityName}`
    case 'RENAME_ATTRIBUTE': return `Renombrar ${operation.attributeName} como ${operation.newName} en ${operation.entityName}`
    case 'CHANGE_ATTRIBUTE_TYPE': return `Cambiar ${operation.attributeName} de ${operation.entityName} a ${operation.dataType}`
    case 'SET_ATTRIBUTE_PRIMARY_KEY': return `${operation.value ? 'Marcar' : 'Quitar'} PK en ${operation.entityName}.${operation.attributeName}`
    case 'SET_ATTRIBUTE_NULLABLE': return `${operation.value ? 'Permitir' : 'Impedir'} null en ${operation.entityName}.${operation.attributeName}`
    case 'ADD_RELATIONSHIP': return `Crear relación ${operation.relationship.sourceEntity} - ${operation.relationship.targetEntity}`
    case 'DELETE_RELATIONSHIP': return `Eliminar relación ${operation.relationship.sourceEntity} - ${operation.relationship.targetEntity}`
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
