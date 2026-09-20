import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'

const edgeComponent = readFileSync(new URL('../src/features/diagram/components/RelationshipEdge.tsx', import.meta.url), 'utf8')
const propertiesPanel = readFileSync(new URL('../src/features/diagram/components/DiagramPropertiesPanel.tsx', import.meta.url), 'utf8')
const styles = readFileSync(new URL('../src/features/diagram/diagram.css', import.meta.url), 'utf8')

test('RelationshipEdge renders the derived N:M table without adding a node type', () => {
  assert.match(edgeComponent, /deriveJoinTable\(props, sourceNode\?\.data, targetNode\?\.data\)/)
  assert.match(edgeComponent, /derived-join-table/)
  assert.match(edgeComponent, /joinTable\.columns\.map/)
  assert.match(edgeComponent, /UNIQUE \(/)
  assert.doesNotMatch(edgeComponent, /addNodes|convertManyToManyAssociation/)
})

test('derived table is visibly styled and documented as read-only', () => {
  assert.match(styles, /\.derived-join-table\s*\{/)
  assert.match(styles, /\.derived-join-columns/)
  assert.match(propertiesPanel, /se deriva de la relación N:M y es de solo lectura/)
  assert.match(propertiesPanel, /entidad asociativa/)
})
