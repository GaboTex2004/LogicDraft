import { tutorials } from '../content/catalog.ts'
import { firstDiagram } from '../content/firstDiagram.ts'
import { createTutorialEngine } from './tutorialEngine.ts'
import type { TutorialContext, TutorialParameterValues, TutorialRole } from '../types/tutorial.types.ts'
import type { ObservedTutorialItem, RuntimeBindings, TutorialActionEvidence, TutorialObservation, TutorialRunSnapshot, TutorialTicket } from '../types/tutorialRuntime.types.ts'

export interface TutorScene {
  path: string
  screen: TutorialContext['screen']
  workspaceId: number | null
  projectId: number | null
  items: readonly ObservedTutorialItem[]
  selected: RuntimeBindings
  inputs: TutorialParameterValues
  documentVersion: 1 | 2 | null
  localRevision: number | null
  persistedLocalRevision: number | null
  serverRevision: number | null
  saveState: TutorialContext['saveState']
  relationKind: string | null
}
export type TutorAction = Pick<TutorialActionEvidence, 'action' | 'entity' | 'entityId' | 'nodeKind' | 'parentId' | 'sourceId' | 'targetId' | 'relationKind' | 'field' | 'value' | 'accepted' | 'acknowledgedLocalRevision' | 'acknowledgedServerRevision'>
export interface TutorActionTicket extends TutorialTicket { sessionId: string; userId: number; workspaceId: number; projectId: number | null }
export interface TutorRuntimeView {
  scene: TutorScene | null
  run: TutorialRunSnapshot | null
  parameters: TutorialParameterValues
  active: boolean
  journey: boolean
  lesson: number
  total: number
  finished: boolean
  message: string | null
}
interface Environment { sessionId: string; userId: number; path: string; ready: boolean; roles: ReadonlyMap<number, string> }
interface Lesson { tutorialId: string; parameters: TutorialParameterValues; inputs: RuntimeBindings; outputSlots?: Partial<Record<keyof RuntimeBindings, string>> }

/** Session-local orchestration; consumes accepted state, never modifies the platform. */
export function createTutorRuntimeStore() {
  let environment: Environment | null = null, scene: TutorScene | null = null
  let controls: TutorialObservation['readyControls'] = [], sequence = 0, instance = 0
  let engine: ReturnType<typeof createTutorialEngine> | null = null
  let lessons: Lesson[] = [], lessonIndex = 0, journey = false, active = false, finished = false
  let slots: Record<string, string | number> = {}, message: string | null = null
  let pending: TutorialActionEvidence[] = []
  const listeners = new Set<() => void>()
  let view: TutorRuntimeView = { scene: null, run: null, parameters: {}, active: false, journey: false, lesson: 0, total: 0, finished: false, message: null }

  function notify() {
    const next = { scene: scene?.path === environment?.path ? scene : null, run: engine?.read() ?? null, parameters: lessons[lessonIndex]?.parameters ?? {}, active, journey,
      lesson: lessonIndex + 1, total: lessons.length, finished, message }
    if (JSON.stringify(next) !== JSON.stringify(view)) { view = next; listeners.forEach(listener => listener()) }
  }
  function observation(): TutorialObservation | null {
    if (!environment?.ready || !scene || scene.path !== environment.path) return null
    const workspaceId = scene.workspaceId ?? (journey ? slots.chosenWorkspace : lessons[lessonIndex]?.inputs.workspace)
    const role = environment.roles.get(Number(workspaceId))
    const workspaces: ObservedTutorialItem[] = [...environment.roles.keys()].map(id => ({ kind: 'workspace', id, workspaceId: id, projectId: null, fields: { name: `Workspace ${id}` } }))
    return { sessionId: environment.sessionId, sequence, context: { userId: environment.userId, screen: scene.screen, workspaceId: scene.workspaceId,
      projectId: scene.projectId, role: ['OWNER', 'EDITOR', 'VIEWER'].includes(role ?? '') ? role as TutorialRole : null, documentVersion: scene.documentVersion,
      bindings: {}, localRevision: scene.localRevision, persistedLocalRevision: scene.persistedLocalRevision, serverRevision: scene.serverRevision, saveState: scene.saveState },
      items: [...workspaces, ...scene.items.filter(item => item.kind !== 'workspace')], selected: scene.selected, inputs: scene.inputs, readyControls: controls, relationKind: scene.relationKind }
  }
  function settle() {
    if (!active) { notify(); return }
    // A finite loop only for already-satisfied transitions, never fabricates actions.
    for (let transitions = 0; transitions < 100; transitions++) {
      const current = observation()
      if (!current) break
      if (!engine) {
        const lesson = lessons[lessonIndex]
        const definition = tutorials.find(item => item.id === lesson.tutorialId)!
        try {
          const inputs = journey ? Object.fromEntries(Object.entries(firstDiagram.items[lessonIndex].inputs).map(([key, slot]) => [key, slots[slot]])) : lesson.inputs
          engine = createTutorialEngine(definition, { instanceId: `tutorial-${++instance}`, bindings: inputs, parameters: lesson.parameters }, current)
          message = null
        } catch {
          message = 'Abre la pantalla indicada y comprueba los elementos elegidos y tus permisos. Luego pulsa Reintentar.'
          break
        }
      }
      const before = engine.read()
      const after = engine.observe(current, pending)
      pending = pending.filter(event => event.instanceId === after.ticket.instanceId && event.stepId === after.ticket.stepId && event.visit === after.ticket.visit)
      if (after.status === 'completed') {
        for (const [key, slot] of Object.entries(lessons[lessonIndex].outputSlots ?? {})) {
          const id = after.outputs[key as keyof RuntimeBindings]
          if (id !== undefined) slots[slot] = id
        }
        if (lessonIndex < lessons.length - 1) { lessonIndex++; engine = null; pending = []; continue }
        finished = true; active = false; break
      }
      if (after.status === 'incomplete' || after.status === 'cancelled') { active = false; break }
      if (after.status === 'paused' || JSON.stringify(before) === JSON.stringify(after)) break
    }
    notify()
  }
  function stop() { engine?.cancel(); engine = null; active = false; finished = false; lessons = []; pending = []; slots = {}; message = null; notify() }

  return {
    subscribe(listener: () => void) { listeners.add(listener); return () => { listeners.delete(listener) } },
    getSnapshot: () => view,
    setEnvironment(next: Environment) {
      if (environment && (environment.sessionId !== next.sessionId || environment.userId !== next.userId)) stop()
      if (environment?.path !== next.path) { controls = []; pending = [] }
      environment = next; sequence++; settle()
    },
    publish(next: TutorScene) { scene = next; sequence++; settle() },
    clearScene(path: string) { if (scene?.path === path) { scene = null; controls = []; notify() } },
    setControls(next: TutorialObservation['readyControls']) {
      if (JSON.stringify(next) !== JSON.stringify(controls)) { controls = next; sequence++; settle() }
    },
    capture(): TutorActionTicket | null {
      const run = engine?.read()
      if (!active || !environment || !run || run.status !== 'waiting') return null
      return { ...run.ticket, sessionId: environment.sessionId, userId: environment.userId, workspaceId: Number(run.bindings.workspace), projectId: typeof run.bindings.project === 'number' ? run.bindings.project : null }
    },
    emit(ticket: TutorActionTicket | null, action: TutorAction) {
      if (!ticket || !active || ticket.sessionId !== environment?.sessionId) return
      pending.push({ ...ticket, ...action, origin: 'local', eventId: `e-${++sequence}`, sequence })
      // Retained until a committed scene contains the result. Old visits cannot advance.
      pending = pending.slice(-100)
      settle()
    },
    start(tutorialId: string, inputs: RuntimeBindings, parameters: TutorialParameterValues) {
      stop(); lessons = [{ tutorialId, inputs: { ...inputs }, parameters: { ...parameters } }]; lessonIndex = 0; journey = false; active = true; settle()
    },
    startJourney(workspaceId: number) {
      stop(); slots = { chosenWorkspace: workspaceId }; lessons = firstDiagram.items.map(item => ({ tutorialId: item.tutorialId, inputs: {}, parameters: item.parameters, outputSlots: item.outputs }))
      lessonIndex = 0; journey = true; active = true; settle()
    },
    pause() { engine?.pause(); notify() },
    resume() { const current = observation(); if (current) engine?.resume(current); settle() },
    back() { const current = observation(); if (current) engine?.back(current); pending = []; active = true; finished = false; notify() },
    skip() { const current = observation(); if (current) engine?.skip(current); pending = []; settle() },
    cancel: stop,
  }
}
export type TutorRuntimeStore = ReturnType<typeof createTutorRuntimeStore>
