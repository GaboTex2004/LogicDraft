import test from 'node:test'
import assert from 'node:assert/strict'
import { createTutorialEngine } from '../src/features/tutor/services/tutorialEngine.ts'
import { tutorials } from '../src/features/tutor/content/catalog.ts'

const allBindings = { workspace: 1, project: 10, node: 'n1', attribute: 'a1', sourceNode: 'n1', targetNode: 'n2', relationship: 'r1' }
const parameters = { projectName: 'Nuevo', nodeName: 'Persona', attributeName: 'edad', attributeType: 'INTEGER', sourceCardinality: 'ONE_ONE', targetCardinality: 'ZERO_MANY' }
const item = (kind, id, rest = {}) => ({ kind, id, workspaceId: 1, projectId: kind === 'workspace' ? null : 10, fields: {}, ...rest })

function fixture(id, overrides = {}) {
  const definition = tutorials.find(t => t.id === id)
  const screen = definition.entryScreens[0]
  const observation = {
    sessionId: 'session', sequence: 10,
    context: { userId: 1, screen, workspaceId: screen === 'dashboard' ? null : 1, projectId: screen === 'editor' ? 10 : null,
      role: 'OWNER', documentVersion: screen === 'editor' ? 2 : null, bindings: {}, localRevision: 3, persistedLocalRevision: 2, serverRevision: 4, saveState: 'dirty' },
    items: [item('workspace', 1), item('project', 10), item('node', 'n1', { nodeKind: 'CLASS', fields: { name: 'Anterior' } }),
      item('node', 'n2', { nodeKind: 'ENTITY' }), item('attribute', 'a1', { parentId: 'n1', fields: { name: 'viejo', type: 'VARCHAR', primaryKey: false } }),
      item('relationship', 'r1', { sourceId: 'n1', targetId: 'n2', relationKind: 'ASSOCIATION', fields: { sourceCardinality: 'ZERO_ONE', targetCardinality: 'ZERO_ONE' } })],
    selected: {}, inputs: {}, readyControls: [], relationKind: 'DEPENDENCY', ...overrides,
  }
  const start = { instanceId: 'lesson-1', bindings: Object.fromEntries(definition.inputs.map(key => [key, allBindings[key]])), parameters }
  return { definition, observation, start, engine: createTutorialEngine(definition, start, observation) }
}
function event(engine, observation, extra = {}) {
  return { ...engine.read().ticket, eventId: `event-${observation.sequence}`, sessionId: 'session', userId: 1, workspaceId: 1,
    projectId: engine.read().bindings.project ?? null, sequence: observation.sequence, origin: 'local', action: 'changed', ...extra }
}
function changed(observation, changes = {}) { return { ...observation, sequence: observation.sequence + 1, ...changes } }
function newClass(f) {
  const ready = changed(f.observation, { readyControls: [{ target: 'editor.class', instanceId: null }] })
  f.engine.observe(ready)
  const observation = changed(ready, { items: [...ready.items, item('node', 'new', { nodeKind: 'CLASS' })] })
  return { observation, evidence: event(f.engine, observation, { action: 'created', entity: 'node', entityId: 'new', nodeKind: 'CLASS' }) }
}

for (const definition of tutorials) test(`catálogo real: ${definition.id} completa sólo con estados/evidencia coherentes`, () => {
  const f = fixture(definition.id)
  let observation = f.observation
  for (const step of definition.steps) {
    assert.equal(f.engine.read().ticket.stepId, step.id)
    const condition = step.completion
    observation = changed(observation)
    let evidence = event(f.engine, observation)
    const bindings = f.engine.read().bindings
    switch (condition.kind) {
      case 'screen': observation = { ...observation, context: { ...observation.context, screen: condition.screen, workspaceId: 1, projectId: condition.scope === 'project' ? 10 : null, documentVersion: condition.screen === 'editor' ? 2 : null } }; break
      case 'control-ready': observation = { ...observation, readyControls: [{ target: condition.target, instanceId: condition.target === 'node.name' ? bindings.node : condition.target === 'relation.source' ? bindings.relationship : null }] }; break
      case 'selected': observation = { ...observation, selected: { [condition.binding]: bindings[condition.binding] } }; evidence = { ...evidence, action: 'selected', entity: condition.binding === 'relationship' ? 'relationship' : 'node', entityId: bindings[condition.binding] }; break
      case 'input-valid': case 'input-matches': observation = { ...observation, inputs: { [condition.parameter]: parameters[condition.parameter] } }; evidence = { ...evidence, field: condition.parameter, value: parameters[condition.parameter] }; break
      case 'relation-kind': observation = { ...observation, relationKind: 'ASSOCIATION' }; evidence = { ...evidence, field: 'relationKind', value: 'ASSOCIATION' }; break
      case 'created': {
        const created = item(condition.entity, condition.entity === 'project' ? 20 : 'new', { nodeKind: condition.nodeKind, parentId: bindings.node,
          sourceId: bindings.sourceNode, targetId: bindings.targetNode, relationKind: 'ASSOCIATION', fields: { name: parameters.projectName, type: 'VARCHAR' } })
        observation = { ...observation, items: [...observation.items, created] }
        evidence = { ...evidence, action: 'created', entity: condition.entity, entityId: created.id, accepted: true, value: parameters.projectName,
          nodeKind: condition.nodeKind, parentId: bindings.node, sourceId: bindings.sourceNode, targetId: bindings.targetNode, relationKind: 'ASSOCIATION' }; break
      }
      case 'field-matches': case 'primary-key-enabled': {
        const id = bindings[condition.binding], field = condition.kind === 'field-matches' ? condition.field : 'primaryKey'
        const value = condition.kind === 'field-matches' ? parameters[condition.parameter] : true
        observation = { ...observation, items: observation.items.map(valueItem => valueItem.id === id ? { ...valueItem, fields: { ...valueItem.fields, [field]: value } } : valueItem) }
        evidence = { ...evidence, field, value, entity: condition.binding, entityId: id }; break
      }
      case 'saved-revision': observation = { ...observation, context: { ...observation.context, persistedLocalRevision: 3, serverRevision: 5, saveState: 'saved' } }; evidence = { ...evidence, action: 'saved', acknowledgedLocalRevision: 3, acknowledgedServerRevision: 5 }; break
    }
    f.engine.observe(observation, [evidence])
    assert.ok(f.engine.read().results[step.id], `step ${step.id} must complete`)
  }
  assert.equal(f.engine.read().status, 'completed')
  assert.equal(Object.keys(f.engine.read().results).length, definition.steps.length)
})

test('creación: ignora eventos remotos, ajenos, repetidos, anteriores y de otro paso/visita', () => {
  const f = fixture('create_class'), { observation, evidence } = newClass(f)
  for (const wrong of [{ origin: 'remote' }, { instanceId: 'other' }, { projectId: 11 }, { workspaceId: 2 }, { sessionId: 'other' }, { userId: 2 },
    { stepId: 'tools' }, { visit: 0 }, { sequence: 10 }, { sequence: 100 }, { action: 'changed' }, { entityId: 'n1' }, { entityId: 'absent' }, { nodeKind: 'ENTITY' }]) {
    assert.equal(f.engine.observe(observation, [{ ...evidence, ...wrong }]).status, 'waiting')
  }
  const done = f.engine.observe(observation, [evidence, evidence])
  assert.equal(done.status, 'completed')
  assert.equal(done.outputs.node, 'new')
  assert.deepEqual(f.engine.observe(observation, [evidence]), done)
})

test('la clase creada debe existir, pertenecer al proyecto y tener el tipo correcto', () => {
  for (const wrong of [{ nodeKind: 'ENTITY' }, { projectId: 11 }, { workspaceId: 2 }, { locked: true }, { structural: true }]) {
    const f = fixture('create_class'), { observation, evidence } = newClass(f)
    const invalid = { ...observation, items: observation.items.map(i => i.id === 'new' ? { ...i, ...wrong } : i) }
    assert.equal(f.engine.observe(invalid, [evidence]).status, 'waiting')
    assert.deepEqual(f.engine.read().outputs, {})
  }
})

test('sin clic de siguiente: sólo un paso por observación, incluso con evidencia de varios pasos', () => {
  const f = fixture('create_class')
  const observation = changed(f.observation, { readyControls: [{ target: 'editor.class', instanceId: null }], items: [...f.observation.items, item('node', 'new', { nodeKind: 'CLASS' })] })
  f.engine.observe(observation, [event(f.engine, observation, { action: 'created', entity: 'node', entityId: 'new' })])
  assert.equal(f.engine.read().ticket.stepId, 'create')
  assert.deepEqual(f.engine.read().outputs, {})
  const next = changed(observation)
  // Already present when the create step started: must not be captured later.
  assert.equal(f.engine.observe(next, [event(f.engine, next, { action: 'created', entity: 'node', entityId: 'new' })]).status, 'waiting')
})

test('pausa explícita, recuperación y evidencia anterior a reanudar', () => {
  const f = fixture('create_class'), { observation, evidence } = newClass(f)
  f.engine.pause()
  assert.equal(f.engine.observe(observation, [evidence]).status, 'paused')
  f.engine.resume(observation)
  assert.equal(f.engine.observe(changed(observation), [evidence]).status, 'waiting')
})

test('conflicto, 422/error, permisos y cambio de proyecto pausan sin outputs', () => {
  for (const context of [{ saveState: 'conflict' }, { saveState: 'error' }, { saveState: 'forbidden' }, { role: 'VIEWER' }, { projectId: 11 }, { workspaceId: 2 }, { documentVersion: null }]) {
    const f = fixture('create_class'), { observation, evidence } = newClass(f)
    const bad = { ...observation, context: { ...observation.context, ...context } }
    assert.equal(f.engine.observe(bad, [evidence]).status, 'paused')
    assert.deepEqual(f.engine.read().outputs, {})
    assert.equal(f.engine.observe(observation, [evidence]).status, 'paused', 'does not auto-resume')
  }
})

test('cambio de sesión/usuario cancela y limpia outputs', () => {
  const f = fixture('create_class'), { observation, evidence } = newClass(f)
  f.engine.observe(observation, [evidence])
  const state = f.engine.observe({ ...observation, sessionId: 'another-login' })
  assert.equal(state.status, 'cancelled')
  assert.deepEqual(state.outputs, {})
  assert.deepEqual(state.bindings, {})
})

test('un campo remoto coincidente no completa; evidencia local exige también el valor correcto', () => {
  const f = fixture('rename_node', { selected: { node: 'n1' }, readyControls: [{ target: 'node.name', instanceId: 'n1' }] })
  f.engine.observe(f.observation); f.engine.observe(f.observation)
  const observation = changed(f.observation, { items: f.observation.items.map(i => i.id === 'n1' ? { ...i, fields: { name: 'Persona' } } : i) })
  const evidence = event(f.engine, observation, { entity: 'node', entityId: 'n1', field: 'name', value: 'Persona' })
  assert.equal(f.engine.observe(observation).status, 'waiting')
  assert.equal(f.engine.observe(observation, [{ ...evidence, origin: 'remote' }]).status, 'waiting')
  assert.equal(f.engine.observe(observation, [{ ...evidence, value: 'Otro' }]).status, 'waiting')
  assert.equal(f.engine.observe(observation, [evidence]).status, 'completed')
})

test('estado inicialmente satisfecho se distingue de una edición realizada', () => {
  const f = fixture('rename_node', { selected: { node: 'n1' }, readyControls: [{ target: 'node.name', instanceId: 'n1' }] })
  const observation = { ...f.observation, items: f.observation.items.map(i => i.id === 'n1' ? { ...i, fields: { name: 'Persona' } } : i) }
  const engine = createTutorialEngine(f.definition, f.start, observation)
  engine.observe(observation); engine.observe(observation); engine.observe(observation)
  assert.equal(engine.read().results.name, 'already-satisfied')
})

test('guardar exige ACK local actual, nunca el estado saved ni un evento remoto', () => {
  const f = fixture('verify_save')
  const observation = changed(f.observation, { context: { ...f.observation.context, localRevision: 4, persistedLocalRevision: 4, serverRevision: 8, saveState: 'saved' } })
  const evidence = event(f.engine, observation, { action: 'saved', acknowledgedLocalRevision: 4, acknowledgedServerRevision: 8 })
  for (const wrong of [{ origin: 'remote' }, { acknowledgedLocalRevision: 3 }, { acknowledgedServerRevision: 7 }, { action: 'changed' }]) {
    assert.equal(f.engine.observe(observation, [{ ...evidence, ...wrong }]).status, 'waiting')
  }
  assert.equal(f.engine.observe(observation).status, 'waiting')
  const dirty = changed(observation, { context: { ...observation.context, localRevision: 5, saveState: 'dirty' } })
  assert.equal(f.engine.observe(dirty, [evidence]).status, 'waiting')
  assert.equal(f.engine.observe(observation, [evidence]).status, 'waiting', 'stale snapshot is ignored')
  const confirmed = changed(dirty, { context: { ...dirty.context, persistedLocalRevision: 5, serverRevision: 9, saveState: 'saved' } })
  assert.equal(f.engine.observe(confirmed, [event(f.engine, confirmed, { action: 'saved', acknowledgedLocalRevision: 5, acknowledgedServerRevision: 9 })]).status, 'completed')
})

test('omitir creación no inventa bindings ni produce final exitoso', () => {
  const f = fixture('add_attribute', { selected: { node: 'n1' }, readyControls: [{ target: 'node.name', instanceId: 'n1' }] })
  f.engine.observe(f.observation); f.engine.observe(f.observation)
  f.engine.skip(f.observation)
  assert.deepEqual(f.engine.read().outputs, {})
  assert.equal(f.engine.observe(f.observation).reason, 'missing-attribute')
  const single = fixture('verify_save')
  assert.equal(single.engine.skip(single.observation).status, 'incomplete')
})

test('atrás invalida progreso posterior sin mutar datos ni permitir respuestas antiguas', () => {
  const f = fixture('create_class'), { observation, evidence } = newClass(f)
  const before = structuredClone(observation)
  f.engine.observe(observation, [evidence])
  f.engine.back(observation)
  assert.equal(f.engine.read().status, 'paused')
  assert.deepEqual(f.engine.read().outputs, {})
  assert.deepEqual(observation, before)
  const copy = f.engine.read(); copy.bindings.project = 99
  assert.equal(f.engine.read().bindings.project, 10)
})

test('bindings y parámetros inválidos se rechazan antes de iniciar', () => {
  const f = fixture('add_attribute')
  for (const start of [{ ...f.start, bindings: { project: 10 } }, { ...f.start, parameters: {} }, { ...f.start, parameters: { ...parameters, attributeType: 'String' } }]) {
    assert.throws(() => createTutorialEngine(f.definition, start, f.observation))
  }
  const badParent = { ...f.observation, items: f.observation.items.map(i => i.id === 'a1' ? { ...i, parentId: 'n2' } : i) }
  assert.throws(() => createTutorialEngine(tutorials.find(t => t.id === 'set_primary_key'), { ...f.start, bindings: allBindings }, badParent), /missing-attribute/)
  assert.throws(() => createTutorialEngine(f.definition, f.start, { ...f.observation, context: { ...f.observation.context, role: 'VIEWER' } }), /permission/)
})

test('atributo nuevo: exige el mismo padre en el evento y en el estado actual', () => {
  const f = fixture('add_attribute', { selected: { node: 'n1' }, readyControls: [{ target: 'node.name', instanceId: 'n1' }] })
  f.engine.observe(f.observation); f.engine.observe(f.observation)
  const observation = changed(f.observation, { items: [...f.observation.items, item('attribute', 'new', { parentId: 'n1' })] })
  const evidence = event(f.engine, observation, { action: 'created', entity: 'attribute', entityId: 'new', parentId: 'n1' })
  assert.equal(f.engine.observe(observation, [{ ...evidence, parentId: 'n2' }]).ticket.stepId, 'add')
  assert.equal(f.engine.observe({ ...observation, items: observation.items.map(i => i.id === 'new' ? { ...i, parentId: 'n2' } : i) }, [evidence]).ticket.stepId, 'add')
  assert.equal(f.engine.observe(observation, [evidence]).outputs.attribute, 'new')
  const removed = changed(observation, { items: observation.items.filter(i => i.id !== 'new') })
  assert.equal(f.engine.observe(removed).reason, 'missing-attribute')
})

test('relación: exige orientación e identidad de extremos, sin aceptar herencia', () => {
  const f = fixture('create_relationship', { relationKind: 'ASSOCIATION', readyControls: [{ target: 'relation.mode', instanceId: null }] })
  f.engine.observe(f.observation); f.engine.observe(f.observation)
  const observation = changed(f.observation, { items: [...f.observation.items, item('relationship', 'new', { sourceId: 'n1', targetId: 'n2', relationKind: 'ASSOCIATION' })] })
  const evidence = event(f.engine, observation, { action: 'created', entity: 'relationship', entityId: 'new', sourceId: 'n1', targetId: 'n2', relationKind: 'ASSOCIATION' })
  for (const wrong of [{ sourceId: 'n2', targetId: 'n1' }, { relationKind: 'GENERALIZATION' }]) {
    assert.equal(f.engine.observe(observation, [{ ...evidence, ...wrong }]).status, 'waiting')
    assert.equal(f.engine.observe({ ...observation, items: observation.items.map(i => i.id === 'new' ? { ...i, ...wrong } : i) }, [evidence]).status, 'waiting')
  }
  assert.equal(f.engine.observe(observation, [evidence]).status, 'completed')
})

test('proyecto requiere aceptación de API y el nombre que se pidió', () => {
  const f = fixture('create_project', { inputs: { projectName: 'Nuevo' }, readyControls: [{ target: 'project.name', instanceId: null }] })
  f.engine.observe(f.observation); f.engine.observe(f.observation)
  const observation = changed(f.observation, { items: [...f.observation.items, item('project', 20, { fields: { name: 'Nuevo' } })] })
  const evidence = event(f.engine, observation, { action: 'created', entity: 'project', entityId: 20, accepted: true, value: 'Nuevo' })
  assert.equal(f.engine.observe(observation, [{ ...evidence, accepted: false }]).status, 'waiting')
  assert.equal(f.engine.observe(observation, [{ ...evidence, value: 'Otro' }]).status, 'waiting')
  assert.equal(f.engine.observe(observation, [evidence]).outputs.project, 20)
})

test('nombre del proyecto: ignora espacios exteriores como la API, conserva la evidencia local', () => {
  const f = fixture('create_project', { readyControls: [{ target: 'project.name', instanceId: null }] })
  f.engine.observe(f.observation)
  const observation = changed(f.observation, { inputs: { projectName: '  Nuevo  ' } })
  assert.equal(f.engine.observe(observation).ticket.stepId, 'name')
  const evidence = event(f.engine, observation, { action: 'changed', field: 'projectName', value: '  Nuevo  ' })
  assert.equal(f.engine.observe(observation, [{ ...evidence, value: 'Otro' }]).ticket.stepId, 'name')
  assert.equal(f.engine.observe(observation, [evidence]).ticket.stepId, 'create')
})

test('el nombre del proyecto lo elige el alumno; vacío no completa y un valor distinto al ejemplo sí', () => {
  const f = fixture('create_project', { readyControls: [{ target: 'project.name', instanceId: null }] })
  f.engine.observe(f.observation)
  const blank = changed(f.observation, { inputs: { projectName: '   ' } })
  assert.equal(f.engine.observe(blank, [event(f.engine, blank, { action: 'changed', field: 'projectName', value: '   ' })]).ticket.stepId, 'name')
  const named = changed(blank, { inputs: { projectName: 'xcdf' } })
  assert.equal(f.engine.observe(named).ticket.stepId, 'name')
  assert.equal(f.engine.observe(named, [event(f.engine, named, { action: 'changed', field: 'projectName', value: 'xcdf' })]).ticket.stepId, 'create')
  const created = changed(named, { items: [...named.items, item('project', 20, { fields: { name: 'xcdf' } })] })
  assert.equal(f.engine.observe(created, [event(f.engine, created, { action: 'created', entity: 'project', entityId: 20, accepted: true, value: 'xcdf' })]).status, 'completed')
})
