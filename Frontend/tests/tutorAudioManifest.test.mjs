import test from 'node:test'
import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import { createHash } from 'node:crypto'
import { tutorials } from '../src/features/tutor/content/catalog.ts'

test('guion Puck cubre cada paso vigente sin parámetros ni rutas arbitrarias', async () => {
  const manifest = JSON.parse(await readFile(new URL('../public/tutor-audio/manifest.json', import.meta.url), 'utf8'))
  assert.equal(manifest.voice, 'Puck')
  assert.equal(manifest.version, 1)
  let count = 0
  for (const tutorial of tutorials) for (const step of tutorial.steps) {
    const entry = manifest.entries[`${tutorial.id}/${step.id}`]
    assert.equal(entry.source, step.text)
    assert.ok(entry.spoken.trim())
    assert.ok(!entry.spoken.includes('{{'))
    const hash = createHash('sha256').update(`${manifest.voice}:${manifest.model}:${entry.spoken}`).digest('hex').slice(0, 20)
    assert.equal(entry.file, `${hash}.wav`)
    count++
  }
  assert.equal(Object.keys(manifest.entries).length, count)
})

test('cada audio declarado disponible existe y contiene un WAV completo', async () => {
  const manifest = JSON.parse(await readFile(new URL('../public/tutor-audio/manifest.json', import.meta.url), 'utf8'))
  const expected = new Set(Object.values(manifest.entries).map(entry => entry.file))
  assert.equal(new Set(manifest.files).size, manifest.files.length)
  for (const filename of manifest.files) {
    assert.ok(expected.has(filename))
    assert.match(filename, /^[a-f0-9]{20}\.wav$/)
    const bytes = await readFile(new URL(`../public/tutor-audio/${filename}`, import.meta.url))
    assert.ok(bytes.length > 44)
    assert.equal(bytes.toString('ascii', 0, 4), 'RIFF')
    assert.equal(bytes.toString('ascii', 8, 12), 'WAVE')
    assert.equal(bytes.readUInt32LE(4) + 8, bytes.length)
  }
})
