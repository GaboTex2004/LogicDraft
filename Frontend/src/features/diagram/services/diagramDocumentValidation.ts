import type { DiagramDocument } from '../types/diagram.types.ts'
import { validateAssociationDocument } from './associationConversion.ts'
import { cardinalities } from './relationshipCardinality.ts'

export class DiagramDocumentError extends Error {}
const key = (value: string) => value.normalize('NFKC').trim().toLocaleLowerCase()
const fail = (message: string): never => { throw new DiagramDocumentError(message) }

/** Shared invariant gate for manual persistence, XMI merge and AI actions. */
export function validateDiagramDocument(document: DiagramDocument): void {
  const nodeIds = new Set<string>()
  const names = new Set<string>()
  for (const node of document.nodes) {
    if (nodeIds.has(node.id)) fail('El diagrama contiene IDs de entidad duplicados.')
    nodeIds.add(node.id)
    const entityName = key(node.data.name)
    if (!entityName || names.has(entityName)) fail(`El nombre de entidad "${node.data.name}" no es único.`)
    names.add(entityName)
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
  }
  validateAssociationDocument(document.nodes, document.edges)
}
