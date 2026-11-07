import { ATTRIBUTE_TYPES, DIAGRAM_CARDINALITIES } from '../../diagram/types/diagram.types.ts'
import type { TutorialBinding, TutorialBindingKind, TutorialCatalog, TutorialJourney, TutorialParameter } from '../types/tutorial.types.ts'

export const bindingKinds: Readonly<Record<TutorialBinding, TutorialBindingKind>> = {
  workspace: 'workspace', project: 'project', node: 'node', attribute: 'attribute',
  sourceNode: 'node', targetNode: 'node', relationship: 'relationship',
}

/** Authoring-time validation only. This is not the tutorial execution engine. */
export function validateTutorialCatalog(catalog: TutorialCatalog): string[] {
  const errors: string[] = []
  const byId = new Map(catalog.tutorials.map(t => [t.id, t]))
  if (byId.size !== catalog.tutorials.length) errors.push('Duplicate tutorial IDs')
  for (const tutorial of catalog.tutorials) {
    const fail = (reason: string) => errors.push(`${tutorial.id}: ${reason}`)
    if (!tutorial.title.trim() || !tutorial.examples.length || !tutorial.steps.length) fail('Missing content')
    if (!tutorial.roles.length || !tutorial.entryScreens.length) fail('Missing access requirements')
    if (new Set(tutorial.steps.map(s => s.id)).size !== tutorial.steps.length) fail('Duplicate step IDs')
    for (const list of [tutorial.inputs, tutorial.outputs, tutorial.parameters, tutorial.prerequisites]) {
      if (new Set(list).size !== list.length) fail('Duplicate contract entries')
    }
    const available = new Set(tutorial.inputs)
    for (const step of tutorial.steps) {
      const target = catalog.targets[step.target]
      if (!target) fail(`Unknown target ${step.target}`)
      else {
        if (!tutorial.entryScreens.includes(target.screen)) fail(`Target screen mismatch ${step.target}`)
        if (target.instance && (!step.binding || bindingKinds[step.binding] !== target.instance)) fail(`Target binding mismatch ${step.id}`)
      }
      if (!step.text.trim() || !step.hint.trim()) fail(`Missing copy ${step.id}`)
      for (const match of (step.text + step.hint).matchAll(/\{\{([^{}]+)\}\}/g)) {
        if (!tutorial.parameters.includes(match[1] as TutorialParameter)) fail(`Undeclared placeholder ${match[1]}`)
      }
      if (step.binding && !available.has(step.binding)) fail(`Unbound target ${step.binding}`)
      const condition = step.completion
      if (tutorial.roles.includes('VIEWER') && ['created', 'field-matches', 'primary-key-enabled', 'saved-revision', 'relation-kind'].includes(condition.kind)) fail('Viewer cannot perform editing exercise')
      if ('binding' in condition && !available.has(condition.binding)) fail(`Unbound condition ${condition.binding}`)
      if ('parameter' in condition && !tutorial.parameters.includes(condition.parameter)) fail(`Undeclared parameter ${condition.parameter}`)
      if (condition.kind === 'control-ready' && !catalog.targets[condition.target]) fail(`Unknown ready target ${condition.target}`)
      if (condition.kind === 'screen' && !available.has(condition.scope)) fail(`Unbound route scope ${condition.scope}`)
      if (condition.kind === 'saved-revision' && !available.has('project')) fail('Unbound save project')
      if (condition.kind === 'created') {
        if (bindingKinds[condition.capture] !== condition.entity) fail(`Capture kind mismatch ${condition.capture}`)
        if (!tutorial.outputs.includes(condition.capture)) fail(`Undeclared output ${condition.capture}`)
        if (tutorial.roles.includes('VIEWER')) fail('Viewer cannot perform creation')
        available.add(condition.capture)
      }
    }
    for (const output of tutorial.outputs) {
      if (!available.has(output) || tutorial.inputs.includes(output)) fail(`Output not captured ${output}`)
    }
    for (const dependency of tutorial.prerequisites) {
      if (!byId.has(dependency)) fail(`Unknown prerequisite ${dependency}`)
    }
  }
  const visited = new Set<string>()
  const active = new Set<string>()
  function visit(id: string) {
    if (active.has(id)) { errors.push(`Cyclic prerequisites at ${id}`); return }
    if (visited.has(id)) return
    active.add(id)
    for (const dependency of byId.get(id)?.prerequisites ?? []) visit(dependency)
    active.delete(id)
    visited.add(id)
  }
  for (const id of byId.keys()) visit(id)
  return errors
}

export function validateTutorialJourney(catalog: TutorialCatalog, journey: TutorialJourney, initialSlots: Readonly<Record<string, TutorialBindingKind>>): string[] {
  const errors = validateTutorialCatalog(catalog)
  if (journey.catalogVersion !== catalog.version) errors.push('Journey/catalog version mismatch')
  if (new Set(journey.items.map(i => i.id)).size !== journey.items.length) errors.push('Duplicate journey instance IDs')
  const slots = new Map(Object.entries(initialSlots))
  for (const item of journey.items) {
    const fail = (reason: string) => errors.push(`${item.id}: ${reason}`)
    const tutorial = catalog.tutorials.find(t => t.id === item.tutorialId)
    if (!tutorial) { fail(`Unknown tutorial ${item.tutorialId}`); continue }
    for (const [entries, expected, label] of [[item.inputs, tutorial.inputs, 'input'], [item.outputs, tutorial.outputs, 'output']] as const) {
      if (Object.keys(entries).length !== expected.length || expected.some(key => !entries[key])) fail(`Invalid ${label} bindings`)
    }
    for (const input of tutorial.inputs) {
      const slot = item.inputs[input]
      if (!slot || slots.get(slot) !== bindingKinds[input]) fail(`Unresolved or mistyped slot ${input}`)
    }
    if (item.inputs.sourceNode && item.inputs.sourceNode === item.inputs.targetNode) fail('Relationship endpoints must differ')
    if (Object.keys(item.parameters).length !== tutorial.parameters.length) fail('Invalid parameter count')
    for (const parameter of tutorial.parameters) {
      const value = item.parameters[parameter]
      if (typeof value !== 'string' || !value.trim() || value !== value.trim() || value.length > 100) { fail(`Invalid parameter ${parameter}`); continue }
      if (parameter === 'attributeType' && !(ATTRIBUTE_TYPES as readonly string[]).includes(value)) fail('Unsupported attribute type')
      if ((parameter === 'sourceCardinality' || parameter === 'targetCardinality') && !(DIAGRAM_CARDINALITIES as readonly string[]).includes(value)) fail('Unsupported cardinality')
    }
    for (const output of tutorial.outputs) {
      const slot = item.outputs[output]
      if (!slot) continue
      if (slots.has(slot)) fail(`Output overwrites slot ${slot}`)
      slots.set(slot, bindingKinds[output])
    }
  }
  return errors
}
