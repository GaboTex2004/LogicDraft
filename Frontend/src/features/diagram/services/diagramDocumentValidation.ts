import { UML_NODE_KINDS, UML_RELATION_KINDS, UML_VISIBILITIES, type DiagramDocument } from '../types/diagram.types.ts'
import { validateAssociationDocument } from './associationConversion.ts'
import { cardinalities } from './relationshipCardinality.ts'

export class DiagramDocumentError extends Error {}
const key = (value: string) => value.normalize('NFKC').trim().toLocaleLowerCase()
const fail = (message: string): never => { throw new DiagramDocumentError(message) }

/** Shared invariant gate for manual persistence, XMI merge and AI actions. */
export function validateDiagramDocument(document: DiagramDocument): void {
  if (document.version !== 1 && document.version !== 2) fail('La versión del diagrama no está soportada.')
  const nodeIds = new Set<string>()
  const names = new Set<string>()
  for (const node of document.nodes) {
    if (nodeIds.has(node.id)) fail('El diagrama contiene IDs de entidad duplicados.')
    nodeIds.add(node.id)
    const entityName = key(node.data.name)
    if (!entityName || names.has(entityName)) fail(`El nombre de entidad "${node.data.name}" no es único.`)
    names.add(entityName)
    if (document.version === 2) {
      const kind = node.data.kind ?? 'ENTITY'
      if (!UML_NODE_KINDS.includes(kind)) fail(`El tipo UML de "${node.data.name}" no es válido.`)
      if (node.data.generateCrud !== undefined &&
          (kind !== 'CLASS' || typeof node.data.generateCrud !== 'boolean'))
        fail(`La opción CRUD de "${node.data.name}" solo aplica a clases UML.`)
      if (kind === 'ENUMERATION' && node.data.literals && new Set(node.data.literals.map(key)).size !== node.data.literals.length)
        fail(`La enumeración "${node.data.name}" contiene literales inválidos o duplicados.`)
      if (kind === 'ENUMERATION' && node.data.attributes.length > 0)
        fail(`La enumeración "${node.data.name}" debe usar literales, no atributos.`)
      if (kind !== 'ENUMERATION' && (node.data.literals?.length ?? 0) > 0)
        fail('Solo una enumeración puede declarar literales.')
      const methodIds = new Set<string>()
      for (const method of node.data.methods ?? []) {
        if (!method.id || methodIds.has(method.id) || !key(method.name) || !UML_VISIBILITIES.includes(method.visibility))
          fail(`La clase "${node.data.name}" contiene métodos inválidos o duplicados.`)
        methodIds.add(method.id)
        if (method.static && method.abstract)
          fail(`El método "${node.data.name}.${method.name}" no puede ser static y abstract.`)
        const parameterIds = new Set<string>()
        for (const parameter of method.parameters) {
          if (!parameter.id || parameterIds.has(parameter.id) || !key(parameter.name) || !key(parameter.type))
            fail(`El método "${method.name}" contiene parámetros inválidos o duplicados.`)
          parameterIds.add(parameter.id)
        }
      }
    }
    const attributeIds = new Set<string>()
    const attributeNames = new Set<string>()
    for (const attribute of node.data.attributes) {
      const attributeName = key(attribute.name)
      if (attributeIds.has(attribute.id) || !attributeName || attributeNames.has(attributeName))
        fail(`La entidad "${node.data.name}" contiene atributos duplicados.`)
      attributeIds.add(attribute.id)
      attributeNames.add(attributeName)
      if (attribute.primaryKey && attribute.nullable === true)
        fail(`La PK "${node.data.name}.${attribute.name}" no puede aceptar null.`)
    }
  }
  const edgeIds = new Set<string>()
  for (const edge of document.edges) {
    if (edgeIds.has(edge.id)) fail('El diagrama contiene IDs de relación duplicados.')
    edgeIds.add(edge.id)
    if (!nodeIds.has(edge.source) || !nodeIds.has(edge.target) || edge.source === edge.target)
      fail('El diagrama contiene una relación con extremos inválidos.')
    cardinalities(edge.data)
    if (document.version === 2 && edge.data?.relationKind) {
      if (!UML_RELATION_KINDS.includes(edge.data.relationKind)) fail('El diagrama contiene un tipo de relación UML inválido.')
      const sourceKind = document.nodes.find(node => node.id === edge.source)?.data.kind ?? 'ENTITY'
      const targetKind = document.nodes.find(node => node.id === edge.target)?.data.kind ?? 'ENTITY'
      const classLike = (kind: string) => ['ENTITY', 'CLASS', 'ASSOCIATION_CLASS'].includes(kind)
      if (edge.data.relationKind === 'REALIZATION' && (!classLike(sourceKind) || targetKind !== 'INTERFACE'))
        fail('Una realización debe partir de una clase o entidad y terminar en una interfaz.')
      if (edge.data.relationKind === 'GENERALIZATION' && !((classLike(sourceKind) && classLike(targetKind)) || (sourceKind === 'INTERFACE' && targetKind === 'INTERFACE')))
        fail('Una generalización debe conectar clases entre sí o interfaces entre sí.')
      if ((edge.data.relationKind === 'AGGREGATION' || edge.data.relationKind === 'COMPOSITION') && (!classLike(sourceKind) || !classLike(targetKind)))
        fail('Una agregación o composición solo puede conectar clases o entidades.')
      if (edge.data.aggregationEnd && (!['SOURCE', 'TARGET'].includes(edge.data.aggregationEnd)
          || !['AGGREGATION', 'COMPOSITION'].includes(edge.data.relationKind)))
        fail('El extremo de agregación no coincide con el tipo de relación.')
      if (!['ASSOCIATION', 'AGGREGATION', 'COMPOSITION'].includes(edge.data.relationKind)
          && (edge.data.sourceMultiplicity || edge.data.targetMultiplicity))
        fail(`La relación ${edge.data.relationKind} no admite multiplicidades.`)
    }
  }
  validateAssociationDocument(document.nodes, document.edges)
}
