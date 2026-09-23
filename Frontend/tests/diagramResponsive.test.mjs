import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import test from 'node:test'

const page = await readFile(new URL('../src/features/diagram/pages/DiagramEditorPage.tsx', import.meta.url), 'utf8')
const toolbar = await readFile(new URL('../src/features/diagram/components/DiagramToolbar.tsx', import.meta.url), 'utf8')
const sidebar = await readFile(new URL('../src/features/diagram/components/DiagramSidebar.tsx', import.meta.url), 'utf8')
const properties = await readFile(new URL('../src/features/diagram/components/DiagramPropertiesPanel.tsx', import.meta.url), 'utf8')
const styles = await readFile(new URL('../src/features/diagram/diagram.css', import.meta.url), 'utf8')

test('el editor ofrece controles accesibles para abrir y cerrar los paneles', () => {
  assert.match(toolbar, /onToggleSidebar/)
  assert.match(toolbar, /onToggleProperties/)
  assert.match(sidebar, /aria-label="Cerrar herramientas"/)
  assert.match(properties, /aria-label="Cerrar propiedades"/)
  assert.match(page, /diagram-panel-backdrop/)
  assert.match(page, /event\.key !== ["']Escape["']/)
})

test('tablet y movil convierten los paneles en superficies superpuestas', () => {
  assert.match(styles, /@media \(max-width: 1100px\)/)
  assert.match(styles, /@media \(max-width: 680px\)/)
  assert.match(styles, /\.diagram-editor\.is-sidebar-open \.diagram-sidebar\s*\{\s*transform: translateX\(0\)/)
  assert.match(styles, /\.diagram-editor\.is-properties-open \.diagram-properties\s*\{\s*transform: translate(?:X|Y)\(0\)/)
  assert.match(styles, /height: min\(72vh, 620px\)/)
  assert.match(styles, /min-height: 40px/)
})
