import test from 'node:test'
import assert from 'node:assert/strict'
import {
  applyDiagramImport, computeDiagramImportDiff, defaultImportSelection,
} from '../src/features/diagram/services/diagramImportDiff.ts'
import { validateAssociationDocument } from '../src/features/diagram/services/associationConversion.ts'

const metadata = externalId => ({ source: 'enterprise-architect', externalId })
const attribute = (id, name, type, primaryKey = false, nullable = true, externalId) => ({
  id, name, type, primaryKey, nullable, ...(externalId ? { externalMetadata: metadata(externalId) } : {}),
})
const node = (id, name, attributes, x, association, externalId) => ({
  id, type: 'entity', position: { x, y: 100 }, data: { id, name, attributes,
    ...(association ? { association } : {}), ...(externalId ? { externalMetadata: metadata(externalId) } : {}) },
})
const edge = (id, source, target, sourceCardinality, targetCardinality, externalId) => ({
  id, type: 'relationship', source, target, data: { sourceCardinality, targetCardinality,
    ...(externalId ? { externalMetadata: metadata(externalId) } : {}) },
})

function currentDocument() {
  return { version: 1, nodes: [
    node('local-user', 'Usuario', [attribute('local-user-id', 'ID', 'INTEGER', true, false)], 40),
    node('local-sale', 'Venta', [
      attribute('local-sale-id', 'ID', 'INTEGER', true, false),
      attribute('local-price', 'precio', 'INTEGER', false, false),
    ], 420),
  ], edges: [edge('local-user-sale', 'local-user', 'local-sale', 'ONE_ONE', 'ONE_MANY')] }
}

function importedDocument() {
  const association = {
    kind: 'MANY_TO_MANY_ASSOCIATION', tableName: 'venta_producto', uniquePair: true,
    endpoints: [
      { role: 'SOURCE', entityId: 'x-product', relationshipId: 'x-product-association', foreignKeyName: 'productos_id' },
      { role: 'TARGET', entityId: 'x-sale', relationshipId: 'x-sale-association', foreignKeyName: 'venta_id' },
    ],
  }
  return { version: 1, nodes: [
    node('x-product', 'Productos', [
      attribute('x-product-id', 'ID', 'INTEGER', false, false, 'product-id'),
      attribute('x-product-name', 'Nombre', 'VARCHAR', false, false, 'product-name'),
    ], 260, undefined, 'product'),
    node('x-user', ' usuario ', [
      attribute('x-user-id', 'ID', 'INTEGER', true, false, 'user-id'),
    ], 100, undefined, 'user'),
    node('x-sale', 'Venta', [
      attribute('x-sale-id', 'ID', 'INTEGER', true, false, 'sale-id'),
      attribute('x-price', 'precio', 'DECIMAL', false, false, 'price'),
      attribute('x-date', 'fecha', 'DATE', false, false, 'date'),
    ], 500, undefined, 'sale'),
    node('x-association', 'ventaProducto', [
      attribute('x-association-id', 'id', 'INTEGER', true, false, 'association-pk'),
      attribute('x-quantity', 'cantidad', 'INTEGER', false, false, 'quantity'),
    ], 700, association, 'association'),
  ], edges: [
    edge('x-user-sale', 'x-user', 'x-sale', 'ONE_ONE', 'ONE_MANY', 'user-sale'),
    edge('x-product-association', 'x-product', 'x-association', 'ONE_ONE', 'ONE_ONE', 'product-association'),
    edge('x-sale-association', 'x-sale', 'x-association', 'ONE_ONE', 'ONE_MANY', 'sale-association'),
  ] }
}

test('ExamenVentas incremental diff matches existing entities and relation without fuzzy duplicates', () => {
  const current = currentDocument()
  current.nodes.push(node('local-client', 'Cliente', [], 800))
  const diff = computeDiagramImportDiff(current, importedDocument(), ['Multiplicidad no reconocida "*...1"'])

  assert.deepEqual(diff.entities.filter(change => change.status === 'added').map(change => change.name).sort(),
    ['Productos', 'ventaProducto'])
  assert.deepEqual(diff.entities.filter(change => change.currentNodeId).map(change => change.currentNodeId).sort(),
    ['local-client', 'local-sale', 'local-user'])
  assert.equal(diff.entities.find(change => change.name === 'Venta').attributes.find(change => change.name === 'fecha').status, 'added')
  assert.equal(diff.entities.find(change => change.name === 'Venta').attributes.find(change => change.name === 'precio').status, 'modified')
  assert.equal(diff.entities.find(change => change.name === 'Cliente').status, 'missing')
  assert.equal(diff.relations.filter(change => change.status === 'unchanged').length, 1)
  assert.equal(diff.relations.filter(change => change.status === 'added').length, 2)
  assert.equal(diff.warnings.length, 1)
})

test('applying the default selection preserves local IDs and positions and adds AssociationClass atomically', () => {
  const current = currentDocument()
  const before = structuredClone(current)
  const imported = importedDocument()
  const diff = computeDiagramImportDiff(current, imported)
  const merged = applyDiagramImport(current, imported, defaultImportSelection(diff), () => 'generated')

  assert.deepEqual(current, before, 'preview and merge must not mutate the loaded document')
  assert.equal(merged.nodes.length, 4)
  assert.equal(merged.edges.length, 3)
  assert.equal(merged.nodes.find(item => item.id === 'local-user').position.x, 40)
  assert.equal(merged.nodes.find(item => item.id === 'local-sale').position.x, 420)
  assert.equal(merged.nodes.filter(item => item.data.name.trim().toLowerCase() === 'usuario').length, 1)
  assert.equal(merged.edges.some(item => item.id === 'local-user-sale'), true)
  const sale = merged.nodes.find(item => item.id === 'local-sale')
  assert.equal(sale.data.attributes.find(item => item.name === 'fecha').type, 'DATE')
  assert.equal(sale.data.attributes.find(item => item.name === 'precio').type, 'DECIMAL')
  const association = merged.nodes.find(item => item.data.name === 'ventaProducto')
  assert.equal(association.data.association.endpoints.every(endpoint => merged.edges.some(item => item.id === endpoint.relationshipId)), true)
  assert.doesNotThrow(() => validateAssociationDocument(merged.nodes, merged.edges))

  const restored = JSON.parse(JSON.stringify(merged))
  assert.doesNotThrow(() => validateAssociationDocument(restored.nodes, restored.edges))
  const second = computeDiagramImportDiff(restored, imported)
  assert.equal(second.entities.every(change => change.status === 'unchanged'), true)
  assert.equal(second.relations.every(change => change.status === 'unchanged'), true)
})

test('missing local elements are safe by default and require explicit deletion', () => {
  const current = currentDocument()
  current.nodes.push(node('local-client', 'Cliente', [], 800))
  const imported = importedDocument()
  const diff = computeDiagramImportDiff(current, imported)
  const missing = diff.entities.find(change => change.name === 'Cliente')
  const defaults = defaultImportSelection(diff)
  assert.equal(defaults.has(missing.id), false)
  assert.equal(applyDiagramImport(current, imported, defaults).nodes.some(item => item.id === 'local-client'), true)
  defaults.add(missing.id)
  assert.equal(applyDiagramImport(current, imported, defaults).nodes.some(item => item.id === 'local-client'), false)
})

test('relationship cardinality changes are detected and reversed orientation remains conceptual match', () => {
  const current = currentDocument()
  current.edges[0] = edge('local-user-sale', 'local-sale', 'local-user', 'ZERO_MANY', 'ONE_ONE')
  const diff = computeDiagramImportDiff(current, importedDocument())
  const relation = diff.relations.find(change => change.currentEdgeId === 'local-user-sale')
  assert.equal(relation.reversed, true)
  assert.equal(relation.status, 'modified')
  const merged = applyDiagramImport(current, importedDocument(), defaultImportSelection(diff))
  assert.equal(merged.edges.find(item => item.id === 'local-user-sale').data.sourceCardinality, 'ONE_MANY')
  assert.equal(merged.edges.find(item => item.id === 'local-user-sale').data.targetCardinality, 'ONE_ONE')
})

test('deselecting an AssociationClass metadata change preserves the current structure', () => {
  const imported = importedDocument()
  const initialDiff = computeDiagramImportDiff(currentDocument(), imported)
  const current = applyDiagramImport(currentDocument(), imported, defaultImportSelection(initialDiff))
  const changedImport = structuredClone(imported)
  changedImport.nodes.find(item => item.data.name === 'ventaProducto').data.association.tableName = 'venta_producto_v2'

  const diff = computeDiagramImportDiff(current, changedImport)
  const associationChange = diff.entities.find(item => item.name === 'ventaProducto')
  assert.equal(associationChange.status, 'modified')
  const selected = defaultImportSelection(diff)
  selected.delete(associationChange.id)

  const merged = applyDiagramImport(current, changedImport, selected)
  assert.equal(merged.nodes.find(item => item.data.name === 'ventaProducto').data.association.tableName,
    'venta_producto')
  assert.doesNotThrow(() => validateAssociationDocument(merged.nodes, merged.edges))
})
