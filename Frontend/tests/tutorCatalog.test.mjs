import assert from 'node:assert/strict'
import test from 'node:test'
import { existsSync } from 'node:fs'
import { tutorialCatalog } from '../src/features/tutor/content/catalog.ts'
import { firstDiagram } from '../src/features/tutor/content/firstDiagram.ts'
import { validateTutorialCatalog, validateTutorialJourney } from '../src/features/tutor/services/validateTutorialCatalog.ts'

const initial = { chosenWorkspace: 'workspace' }
const copy = () => structuredClone(tutorialCatalog)

test('catalog and first diagram have valid dependencies, bindings and parameters', () => {
  assert.deepEqual(validateTutorialCatalog(tutorialCatalog), [])
  assert.deepEqual(validateTutorialJourney(tutorialCatalog, firstDiagram, initial), [])
  assert.equal(tutorialCatalog.runtimeAvailable, false)
  for (const target of Object.values(tutorialCatalog.targets)) {
    assert.ok(existsSync(new URL(`../${target.sourceFile}`, import.meta.url)), target.sourceFile)
    assert.equal(target.integration, 'registered')
  }
})

test('cyclic, missing and duplicate tutorials cannot be published', () => {
  const catalog = copy()
  catalog.tutorials[0].prerequisites = ['create_project', 'missing']
  catalog.tutorials.push(structuredClone(catalog.tutorials[1]))
  const errors = validateTutorialCatalog(catalog).join('\n')
  assert.match(errors, /Cyclic/)
  assert.match(errors, /Unknown prerequisite missing/)
  assert.match(errors, /Duplicate tutorial/)
})

test('unregistered targets, unresolved bindings and invented placeholders are rejected', () => {
  const catalog = copy()
  const tutorial = catalog.tutorials.find(t => t.id === 'rename_node')
  tutorial.inputs = ['project']
  tutorial.steps[2].target = 'invented.button'
  tutorial.steps[2].text = 'Pulsa {{invented}}'
  const errors = validateTutorialCatalog(catalog).join('\n')
  assert.match(errors, /Unknown target/)
  assert.match(errors, /Unbound/)
  assert.match(errors, /Undeclared placeholder/)
})

test('VIEWER may navigate but cannot be assigned editing tutorials', () => {
  const catalog = copy()
  catalog.tutorials.find(t => t.id === 'set_primary_key').roles.push('VIEWER')
  assert.ok(validateTutorialCatalog(catalog).some(e => e.includes('Viewer cannot')))
  const editable = tutorialCatalog.tutorials.filter(t => t.roles.includes('VIEWER'))
  assert.deepEqual(editable.map(t => t.id), ['choose_workspace', 'open_editor'])
})

test('classes and entities have distinct creation evidence; no silent substitution', () => {
  const get = id => tutorialCatalog.tutorials.find(t => t.id === id).steps.at(-1).completion
  assert.equal(get('create_class').nodeKind, 'CLASS')
  assert.equal(get('create_entity').nodeKind, 'ENTITY')
})

test('two class creations retain distinct instance IDs and relationship endpoints', () => {
  const classes = firstDiagram.items.filter(i => i.tutorialId === 'create_class')
  assert.equal(classes.length, 2)
  assert.notEqual(classes[0].outputs.node, classes[1].outputs.node)
  const journey = structuredClone(firstDiagram)
  const relation = journey.items.find(i => i.tutorialId === 'create_relationship')
  relation.inputs.targetNode = relation.inputs.sourceNode
  assert.ok(validateTutorialJourney(tutorialCatalog, journey, initial).some(e => e.includes('endpoints must differ')))
})

test('journey rejects missing upstream outputs, wrong slot types and output reuse', () => {
  const journey = structuredClone(firstDiagram)
  journey.items.find(i => i.id === 'client-name').inputs.node = 'lessonProject'
  journey.items.find(i => i.id === 'order').outputs.node = 'client'
  journey.items.find(i => i.id === 'save').inputs.project = 'missing'
  const errors = validateTutorialJourney(tutorialCatalog, journey, initial).join('\n')
  assert.match(errors, /mistyped slot node/)
  assert.match(errors, /overwrites slot client/)
  assert.match(errors, /mistyped slot project/)
})

test('invalid types, cardinalities, versions and duplicate instances are rejected', () => {
  const journey = structuredClone(firstDiagram)
  journey.catalogVersion = 99
  journey.items.find(i => i.id === 'client-id').parameters.attributeType = 'String'
  journey.items.find(i => i.id === 'cardinality').parameters.sourceCardinality = 'many'
  journey.items.push(structuredClone(journey.items.at(-1)))
  const errors = validateTutorialJourney(tutorialCatalog, journey, initial).join('\n')
  assert.match(errors, /version mismatch/)
  assert.match(errors, /Unsupported attribute type/)
  assert.match(errors, /Unsupported cardinality/)
  assert.match(errors, /Duplicate journey instance/)
})

test('save completion requires acknowledged local changes, not an audio/click event', () => {
  const save = tutorialCatalog.tutorials.find(t => t.id === 'verify_save')
  assert.deepEqual(save.steps[0].completion, {
    kind: 'saved-revision', scope: 'project', require: 'all-local-changes-acknowledged',
  })
})
