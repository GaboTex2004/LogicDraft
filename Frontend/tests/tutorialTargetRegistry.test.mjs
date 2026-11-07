import test from 'node:test'
import assert from 'node:assert/strict'
import { createTutorialTargetRegistry } from '../src/features/tutor/services/tutorialTargetRegistry.ts'
import { tutorialCatalog } from '../src/features/tutor/content/catalog.ts'

const address = { sessionId: 'session-a', screen: 'editor', workspaceId: 1, projectId: 10, target: 'node.name', instanceId: 'node-a' }
const ready = () => ({ mounted: true, visible: true, enabled: true, permitted: true })

test('resuelve únicamente el control de la sesión, pantalla, workspace, proyecto e instancia elegidos', () => {
  const registry = createTutorialTargetRegistry()
  const control = { name: 'input' }
  registry.register(address, control, ready)
  assert.deepEqual(registry.resolve(address), { status: 'ready', control })
  for (const changes of [{ sessionId: 'session-b' }, { screen: 'projects' }, { workspaceId: 2 }, { projectId: 11 }, { instanceId: 'node-b' }, { target: 'attribute.name' }]) {
    assert.equal(registry.resolve({ ...address, ...changes }).status, 'missing')
  }
})

test('todos los destinos del catálogo admiten registro contextual sin selectores CSS', () => {
  const registry = createTutorialTargetRegistry()
  for (const [target, definition] of Object.entries(tutorialCatalog.targets)) {
    const location = { ...address, target, screen: definition.screen, instanceId: definition.instance ? 'item' : null }
    const dispose = registry.register(location, target, ready)
    assert.equal(registry.resolve(location).control, target)
    dispose()
    assert.equal(registry.resolve(location).status, 'missing')
  }
})

test('un control oculto, desmontado, deshabilitado o sin permiso nunca está listo', () => {
  const registry = createTutorialTargetRegistry()
  let state = ready()
  registry.register(address, {}, () => state)
  for (const field of Object.keys(state)) {
    state = { ...ready(), [field]: false }
    assert.equal(registry.resolve(address).status, 'unavailable')
  }
  state = ready()
  assert.equal(registry.resolve(address).status, 'ready')
})

test('duplicados pausan; limpieza tardía o repetida no elimina un reemplazo', () => {
  const registry = createTutorialTargetRegistry()
  const oldCleanup = registry.register(address, 'old', ready)
  registry.register(address, 'new', ready)
  assert.equal(registry.resolve(address).status, 'ambiguous')
  oldCleanup()
  oldCleanup()
  assert.equal(registry.resolve(address).control, 'new')
  registry.clear()
  registry.register(address, 'next-session', ready)
  oldCleanup()
  assert.equal(registry.resolve(address).control, 'next-session')
})

test('direcciones inválidas se rechazan y el registro copia la dirección recibida', () => {
  const registry = createTutorialTargetRegistry()
  for (const changes of [{ target: 'invented' }, { instanceId: null }, { sessionId: '' }, { projectId: null }, { workspaceId: null }, { target: 'editor.tools', instanceId: 'unexpected' }]) {
    assert.throws(() => registry.register({ ...address, ...changes }, {}, ready), /Invalid/)
  }
  const mutable = { ...address }
  registry.register(mutable, 'original', ready)
  mutable.instanceId = 'different'
  assert.equal(registry.resolve(address).control, 'original')
})

test('un adaptador que falla pausa y clear retira todos los controles', () => {
  const registry = createTutorialTargetRegistry()
  registry.register(address, {}, () => { throw new Error('unmounted') })
  assert.equal(registry.resolve(address).status, 'unavailable')
  registry.clear()
  assert.equal(registry.resolve(address).status, 'missing')
})

test('enumerar destinos no permite modificar direcciones internas ni omite la validación', () => {
  const registry = createTutorialTargetRegistry()
  registry.register(address, 'control', () => ({ ...ready(), enabled: false }))
  const snapshot = registry.addresses()
  snapshot[0].projectId = 999
  assert.equal(registry.resolve(address).status, 'unavailable')
  assert.equal(registry.addresses()[0].projectId, address.projectId)
  registry.clear()
  assert.deepEqual(registry.addresses(), [])
})
