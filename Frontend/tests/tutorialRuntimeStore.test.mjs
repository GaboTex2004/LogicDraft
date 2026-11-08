import test from 'node:test'
import assert from 'node:assert/strict'
import { createTutorRuntimeStore } from '../src/features/tutor/services/tutorialRuntimeStore.ts'

const scene = { path: '/proyectos/10/editor', screen: 'editor', workspaceId: 1, projectId: 10,
  items: [{ kind: 'project', id: 10, workspaceId: 1, projectId: 10, fields: { name: 'Prueba' } }],
  selected: {}, inputs: {}, documentVersion: 2, localRevision: 0, persistedLocalRevision: 0, serverRevision: 1, saveState: 'clean', relationKind: 'ASSOCIATION' }
function setup(role = 'OWNER') {
  const store = createTutorRuntimeStore()
  store.setEnvironment({ path: scene.path, userId: 1, sessionId: 's', ready: true, roles: new Map([[1, role]]) })
  store.publish(scene)
  store.setControls([{ target: 'editor.class', instanceId: null }])
  return store
}
test('evidencia aceptada antes del commit React espera el objeto real', () => {
  const store = setup()
  store.start('create_class', { project: 10 }, {})
  assert.equal(store.getSnapshot().run.ticket.stepId, 'create')
  const ticket = store.capture()
  store.emit(ticket, { action: 'created', entity: 'node', entityId: 'n1', nodeKind: 'CLASS' })
  assert.equal(store.getSnapshot().finished, false)
  store.publish({ ...scene, items: [...scene.items, { kind: 'node', id: 'n1', workspaceId: 1, projectId: 10, nodeKind: 'CLASS', fields: { name: 'Nueva clase' } }] })
  assert.equal(store.getSnapshot().finished, true)
  assert.equal(store.getSnapshot().run.outputs.node, 'n1')
})
test('cancelación y cambio de identidad invalidan callbacks pendientes', () => {
  const store = setup()
  store.start('create_class', { project: 10 }, {})
  const ticket = store.capture()
  store.cancel()
  store.emit(ticket, { action: 'created', entity: 'node', entityId: 'n1', nodeKind: 'CLASS' })
  assert.equal(store.getSnapshot().run, null)
  store.start('create_class', { project: 10 }, {})
  store.setEnvironment({ path: scene.path, userId: 2, sessionId: 's2', ready: true, roles: new Map([[1, 'OWNER']]) })
  assert.equal(store.getSnapshot().active, false)
})
test('VIEWER no inicia mutaciones y un estado sin evidencia no acredita creación', () => {
  const viewer = setup('VIEWER')
  viewer.start('create_class', { project: 10 }, {})
  assert.equal(viewer.capture(), null)
  const store = setup()
  store.start('create_class', { project: 10 }, {})
  store.publish({ ...scene, items: [...scene.items, { kind: 'node', id: 'remote', workspaceId: 1, projectId: 10, nodeKind: 'CLASS', fields: {} }] })
  assert.equal(store.getSnapshot().finished, false)
})
