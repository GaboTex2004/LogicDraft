import { mkdir, stat, writeFile } from 'node:fs/promises'
import { createHash } from 'node:crypto'
import { fileURLToPath } from 'node:url'
import { tutorials } from '../src/features/tutor/content/catalog.ts'

const replacements = {
  nodeName: 'el nombre indicado en pantalla', attributeName: 'el nombre indicado en pantalla',
  attributeType: 'el tipo indicado en pantalla', sourceCardinality: 'la cardinalidad indicada en pantalla', targetCardinality: 'la cardinalidad indicada en pantalla',
}
const entries = {}
for (const tutorial of tutorials) for (const step of tutorial.steps) {
  const spoken = step.text.replace(/\{\{([^}]+)\}\}/g, (_, key) => {
    if (!replacements[key]) throw new Error(`Unresolved audio parameter: ${key}`)
    return replacements[key]
  }).replace('Selecciona ASSOCIATION', 'Selecciona asociación').replaceAll('UML', 'u eme ele')
  const hash = createHash('sha256').update(`Puck:gemini-2.5-flash-preview-tts:${spoken}`).digest('hex').slice(0, 20)
  entries[`${tutorial.id}/${step.id}`] = { source: step.text, spoken, file: `${hash}.wav` }
}
const output = fileURLToPath(new URL('../public/tutor-audio/', import.meta.url))
await mkdir(output, { recursive: true })
const files = []
for (const file of new Set(Object.values(entries).map(entry => entry.file))) {
  try { if ((await stat(`${output}/${file}`)).size > 44) files.push(file) } catch { /* Not generated yet. */ }
}
await writeFile(`${output}/manifest.json`, JSON.stringify({ version: 1, voice: 'Puck', model: 'gemini-2.5-flash-preview-tts', entries, files }, null, 2) + '\n')
console.log(`${Object.keys(entries).length} pasos; ${new Set(Object.values(entries).map(entry => entry.file)).size} audios únicos.`)
