import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import test from 'node:test'

const toolbar = await readFile(new URL('../src/features/diagram/components/DiagramToolbar.tsx', import.meta.url), 'utf8')
const api = await readFile(new URL('../src/features/diagram/api/generatorApi.ts', import.meta.url), 'utf8')
const page = await readFile(new URL('../src/features/diagram/pages/DiagramEditorPage.tsx', import.meta.url), 'utf8')

test('la acción de proyecto mantiene ambas opciones en un solo menú', () => {
  assert.match(toolbar, /'Exportar'/)
  assert.match(toolbar, />Solo backend</)
  assert.match(toolbar, />Proyecto completo</)
  assert.match(toolbar, /aria-haspopup="menu"/)
})

test('las opciones conservan los endpoints y el bloqueo por cambios pendientes', () => {
  assert.match(api, /generator\/\$\{kind\}/)
  assert.match(api, /responseType: 'blob'/)
  assert.match(api, /triggerBlobDownload/)
  assert.match(page, /handleExport\('backend'\)/)
  assert.match(page, /handleExport\('fullstack'\)/)
  assert.match(page, /saveStatus === 'dirty'/)
  assert.match(page, /saveStatus === 'saving'/)
  assert.match(page, /saveStatus === 'forbidden'/)
})

test('muestra loading y diferencia los errores de exportación', () => {
  assert.match(toolbar, /'Generando\.\.\.'/)
  assert.match(toolbar, /exportStatus !== null/)
  assert.match(page, /isUnauthorizedError\(requestError\)/)
  assert.match(page, /isForbiddenError\(requestError\)/)
  assert.match(page, /response\?\.status === 409/)
  assert.match(page, /!requestError\.response/)
  assert.match(page, />= 500/)
})
