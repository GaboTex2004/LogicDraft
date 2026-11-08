import { ATTRIBUTE_TYPES, DIAGRAM_CARDINALITIES } from '../../diagram/types/diagram.types.ts'
import { bindingKinds } from './validateTutorialCatalog.ts'
import { tutorialTargets } from '../content/targets.ts'
import type { TutorialBinding, TutorialDefinition, TutorialStep } from '../types/tutorial.types.ts'
import type { ObservedTutorialItem, RuntimeBindings, TutorialActionEvidence, TutorialObservation, TutorialRunSnapshot, TutorialRunStart } from '../types/tutorialRuntime.types.ts'

/** Pure deterministic runtime. No DOM, network, clocks, timers, persistence or mutations. */
function validProjectName(value: unknown): boolean {
  return typeof value === 'string' && value.trim().length > 0 && value.length <= 100
}

export function createTutorialEngine(definition: TutorialDefinition, start: TutorialRunStart, initial: TutorialObservation) {
  const tutorial = structuredClone(definition)
  const runId = start.instanceId
  const parameters = structuredClone(start.parameters)
  // The project API stores nombre.trim(); compare the same value the form submits.
  if (parameters.projectName !== undefined) parameters.projectName = parameters.projectName.trim()
  if (!start.instanceId.trim() || !tutorial.steps.length || new Set(tutorial.steps.map(step => step.id)).size !== tutorial.steps.length) throw new Error('Invalid tutorial run')
  if (!Number.isSafeInteger(initial.sequence) || initial.sequence < 0 || !initial.sessionId) throw new Error('Invalid observation sequence/session')
  if (initial.context.screen === 'other' || !tutorial.entryScreens.includes(initial.context.screen)) throw new Error('Wrong entry screen')
  for (const name of tutorial.parameters) {
    if (name === 'projectName' && tutorial.steps.some(step => step.completion.kind === 'input-valid')) continue
    const value = parameters[name]
    if (typeof value !== 'string' || !value.trim()) throw new Error(`Missing parameter: ${name}`)
    if (name === 'attributeType' && !ATTRIBUTE_TYPES.some(type => type === value)) throw new Error('Invalid attribute type')
    if ((name === 'sourceCardinality' || name === 'targetCardinality') && !DIAGRAM_CARDINALITIES.some(type => type === value)) throw new Error('Invalid cardinality')
  }
  for (const binding of tutorial.inputs) if (start.bindings[binding] === undefined) throw new Error(`Missing binding: ${binding}`)
  const sessionId = initial.sessionId, userId = initial.context.userId
  const workspaceId = initial.context.workspaceId ?? start.bindings.workspace
  if (typeof workspaceId !== 'number' || !Number.isSafeInteger(workspaceId) || workspaceId <= 0) throw new Error('Missing workspace scope')
  if (start.bindings.workspace !== undefined && start.bindings.workspace !== workspaceId) throw new Error('Workspace binding mismatch')
  let bindings: RuntimeBindings = Object.fromEntries(tutorial.inputs.map(key => [key, start.bindings[key]]))
  // Workspace context is always scoped even when not declared as an explicit input.
  bindings.workspace = workspaceId
  let outputs: RuntimeBindings = {}
  let index = 0, visit = 1, baseline = initial.sequence, latest = initial.sequence
  let status: TutorialRunSnapshot['status'] = 'waiting'
  let reason: string | null = null
  const results: Record<string, 'observed' | 'performed' | 'already-satisfied' | 'skipped'> = {}
  const consumed = new Set<string>()
  let existingItems = new Set(initial.items.map(value => JSON.stringify([value.kind, value.id])))
  const step = () => tutorial.steps[Math.min(index, tutorial.steps.length - 1)]

  function item(binding: TutorialBinding, observation: TutorialObservation): ObservedTutorialItem | undefined {
    const id = bindings[binding]
    if (id === undefined) return undefined
    const matches = observation.items.filter(value => value.kind === bindingKinds[binding] && value.id === id && value.workspaceId === workspaceId
      && (value.kind === 'workspace' || value.kind === 'project' || value.projectId === bindings.project))
    if (matches.length !== 1) return undefined
    const found = matches[0]
    if (binding === 'attribute' && found.parentId !== bindings.node) return undefined
    return found
  }

  function guard(observation: TutorialObservation): string | null {
    const context = observation.context
    if (!context.role || !tutorial.roles.includes(context.role)) return 'permission'
    if (context.workspaceId !== workspaceId && !(context.screen === 'dashboard' && step().completion.kind === 'screen')) return 'workspace'
    if (bindings.project !== undefined && context.projectId !== bindings.project
      && !(context.screen === 'projects' && step().completion.kind === 'screen')) return 'project'
    if (['error', 'conflict', 'forbidden'].includes(context.saveState)) return `save-${context.saveState}`
    if (context.screen === 'editor' && (context.documentVersion === null
      || tutorial.documentVersions.length > 0 && !tutorial.documentVersions.includes(context.documentVersion))) return 'document-version'
    const condition = step().completion
    const required = new Set<TutorialBinding>([...tutorial.inputs, ...(step().binding ? [step().binding!] : []), ...('binding' in condition ? [condition.binding] : [])])
    for (const binding of required) if (bindings[binding] === undefined) return `missing-${binding}`
    const allowedScreens = [...tutorial.entryScreens, ...(condition.kind === 'screen' ? [condition.screen] : [])]
    if (context.screen === 'other' || !allowedScreens.includes(context.screen)) return 'screen'
    for (const binding of Object.keys(bindings) as TutorialBinding[]) {
      const current = item(binding, observation)
      if (!current) return `missing-${binding}`
      if (current.locked) return `locked-${binding}`
      if ((binding === 'node' || binding === 'sourceNode' || binding === 'targetNode') && !['CLASS', 'ENTITY'].includes(current.nodeKind ?? '')) return 'incompatible-node'
      if (current.structural && (binding === 'attribute' || binding === 'relationship')) return 'structural-element'
    }
    if (bindings.sourceNode !== undefined && bindings.sourceNode === bindings.targetNode) return 'same-endpoints'
    if (condition.kind === 'field-matches' && condition.binding === 'relationship' && item('relationship', observation)?.relationKind !== 'ASSOCIATION') return 'incompatible-relationship'
    return null
  }

  function conditionMatches(current: TutorialStep, observation: TutorialObservation): boolean {
    const condition = current.completion
    switch (condition.kind) {
      case 'screen': return observation.context.screen === condition.screen && observation.context[condition.scope === 'workspace' ? 'workspaceId' : 'projectId'] === bindings[condition.scope]
      case 'control-ready': {
        const target = tutorialTargets[condition.target as keyof typeof tutorialTargets]
        if (!target) return false
        const kind = 'instance' in target ? target.instance : null
        const instance = kind ? bindings[kind] : null
        return instance !== undefined && observation.readyControls.some(control => control.target === condition.target && control.instanceId === instance)
      }
      case 'input-valid': return validProjectName(observation.inputs.projectName)
      case 'input-matches': return (condition.parameter === 'projectName' ? observation.inputs.projectName?.trim() : observation.inputs[condition.parameter]) === parameters[condition.parameter]
      case 'selected': return bindings[condition.binding] !== undefined && observation.selected[condition.binding] === bindings[condition.binding]
      case 'field-matches': return item(condition.binding, observation)?.fields[condition.field] === parameters[condition.parameter]
      case 'primary-key-enabled': return item(condition.binding, observation)?.fields.primaryKey === true
      case 'relation-kind': return observation.relationKind === condition.value
      case 'created': case 'saved-revision': return false
    }
  }

  let initiallySatisfied = conditionMatches(step(), initial)
  const initialProblem = guard(initial)
  if (initialProblem) throw new Error(`Cannot start tutorial: ${initialProblem}`)

  function snapshot(): TutorialRunSnapshot {
    return { tutorialId: tutorial.id, ticket: { instanceId: runId, stepId: step().id, visit }, status, reason, stepIndex: index,
      bindings: { ...bindings }, outputs: { ...outputs }, results: { ...results } }
  }

  function cancel() {
    status = 'cancelled'; reason = 'cancelled'; bindings = {}; outputs = {}
    return snapshot()
  }

  function check(observation: TutorialObservation): boolean {
    if (observation.sessionId !== sessionId || observation.context.userId !== userId) { cancel(); return false }
    if (!Number.isSafeInteger(observation.sequence) || observation.sequence < latest) return false
    latest = observation.sequence
    return true
  }

  function enter(observation: TutorialObservation) {
    visit++; baseline = observation.sequence
    existingItems = new Set(observation.items.map(value => JSON.stringify([value.kind, value.id])))
    initiallySatisfied = conditionMatches(step(), observation)
  }

  function advance(result: 'observed' | 'performed' | 'already-satisfied' | 'skipped', observation: TutorialObservation) {
    results[step().id] = result
    if (index === tutorial.steps.length - 1) {
      status = Object.values(results).includes('skipped') || tutorial.outputs.some(output => outputs[output] === undefined) ? 'incomplete' : 'completed'
    } else { index++; enter(observation) }
  }

  function validEvent(event: TutorialActionEvidence, observation: TutorialObservation) {
    return event.origin === 'local' && event.sessionId === sessionId && event.userId === userId && event.workspaceId === workspaceId
      && event.projectId === (bindings.project ?? null) && event.instanceId === runId && event.stepId === step().id && event.visit === visit
      && Boolean(event.eventId) && !consumed.has(event.eventId) && Number.isSafeInteger(event.sequence) && event.sequence > baseline && event.sequence <= observation.sequence
  }

  function creation(event: TutorialActionEvidence, observation: TutorialObservation): boolean {
    const condition = step().completion
    if (condition.kind !== 'created' || event.action !== 'created' || event.entity !== condition.entity || event.entityId === undefined) return false
    if (existingItems.has(JSON.stringify([event.entity, event.entityId]))) return false
    if (condition.entity === 'project' && !event.accepted) return false
    const found = observation.items.filter(value => value.kind === condition.entity && value.id === event.entityId && value.workspaceId === workspaceId
      && (condition.entity === 'project' || value.projectId === bindings.project))
    if (found.length !== 1) return false
    const created = found[0]
    if (created.locked || created.structural || (condition.nodeKind && (created.nodeKind !== condition.nodeKind || event.nodeKind !== condition.nodeKind))) return false
    if (condition.entity === 'project' && (!validProjectName(event.value) || created.fields.name !== (event.value as string).trim())) return false
    if (condition.entity === 'attribute' && (created.parentId !== bindings.node || event.parentId !== bindings.node)) return false
    if (condition.entity === 'relationship' && (created.sourceId !== bindings.sourceNode || created.targetId !== bindings.targetNode || created.relationKind !== 'ASSOCIATION'
      || event.sourceId !== bindings.sourceNode || event.targetId !== bindings.targetNode || event.relationKind !== 'ASSOCIATION')) return false
    bindings[condition.capture] = created.id
    outputs[condition.capture] = created.id
    return true
  }

  function actionMatches(event: TutorialActionEvidence, observation: TutorialObservation): boolean {
    const condition = step().completion
    if (condition.kind === 'created') return creation(event, observation)
    if (condition.kind === 'saved-revision') {
      const context = observation.context
      return event.action === 'saved' && context.localRevision !== null && context.persistedLocalRevision !== null && context.serverRevision !== null
        && Number.isSafeInteger(context.localRevision) && context.localRevision >= 0 && Number.isSafeInteger(context.serverRevision) && context.serverRevision >= 0
        && ['saved', 'clean'].includes(context.saveState) && context.persistedLocalRevision === context.localRevision
        && event.acknowledgedLocalRevision === context.localRevision && event.acknowledgedServerRevision === context.serverRevision
    }
    if (!conditionMatches(step(), observation)) return false
    if (condition.kind === 'input-valid') return event.action === 'changed' && event.field === condition.parameter && typeof event.value === 'string'
      && event.value.trim() === observation.inputs.projectName?.trim()
    if (condition.kind === 'input-matches') return event.action === 'changed' && event.field === condition.parameter
      && (condition.parameter === 'projectName' && typeof event.value === 'string' ? event.value.trim() : event.value) === parameters[condition.parameter]
    if (condition.kind === 'relation-kind') return event.action === 'changed' && event.field === 'relationKind' && event.value === condition.value
    if ('binding' in condition) {
      if (event.entityId !== bindings[condition.binding] || event.entity !== bindingKinds[condition.binding]) return false
      if (condition.kind === 'selected') return event.action === 'selected'
      return event.action === 'changed' && event.field === (condition.kind === 'primary-key-enabled' ? 'primaryKey' : condition.field)
        && event.value === (condition.kind === 'primary-key-enabled' ? true : parameters[condition.parameter])
    }
    return false
  }

  return {
    read: snapshot,
    cancel,
    observe(observation: TutorialObservation, evidence: readonly TutorialActionEvidence[] = []) {
      if (!check(observation) || status !== 'waiting') return snapshot()
      const problem = guard(observation)
      if (problem) { status = 'paused'; reason = problem; return snapshot() }
      const condition = step().completion
      if (conditionMatches(step(), observation) && (initiallySatisfied || condition.kind === 'screen' || condition.kind === 'control-ready')) {
        advance(initiallySatisfied ? 'already-satisfied' : 'observed', observation)
      } else {
        for (const event of evidence) {
          if (validEvent(event, observation) && actionMatches(event, observation)) {
            consumed.add(event.eventId); advance('performed', observation); break
          }
        }
      }
      return snapshot()
    },
    pause() { if (status === 'waiting') { status = 'paused'; reason = 'manual' }; return snapshot() },
    resume(observation: TutorialObservation) {
      if (!check(observation) || status !== 'paused') return snapshot()
      const problem = guard(observation)
      if (problem) { reason = problem; return snapshot() }
      status = 'waiting'; reason = null; visit++; baseline = observation.sequence
      existingItems = new Set(observation.items.map(value => JSON.stringify([value.kind, value.id])))
      // Preserve the initial satisfaction flag: remote changes during pause are not local work.
      return snapshot()
    },
    back(observation: TutorialObservation) {
      if (!check(observation) || status === 'cancelled' || index === 0) return snapshot()
      index--
      for (const current of tutorial.steps.slice(index)) {
        delete results[current.id]
        if (current.completion.kind === 'created') { delete outputs[current.completion.capture]; delete bindings[current.completion.capture] }
      }
      status = 'paused'; reason = 'review'; enter(observation)
      return snapshot()
    },
    skip(observation: TutorialObservation) {
      if (!check(observation) || status !== 'waiting') return snapshot()
      const problem = guard(observation)
      if (problem) { status = 'paused'; reason = problem; return snapshot() }
      const condition = step().completion
      if (condition.kind === 'created') { delete bindings[condition.capture]; delete outputs[condition.capture] }
      advance('skipped', observation)
      return snapshot()
    },
  }
}
