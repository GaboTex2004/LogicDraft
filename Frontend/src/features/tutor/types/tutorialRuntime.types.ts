import type { TutorialBinding, TutorialBindingKind, TutorialContext, TutorialParameterValues } from './tutorial.types.ts'

export type RuntimeId = string | number
export type RuntimeBindings = Partial<Record<TutorialBinding, RuntimeId>>

/** Minimal projection from accepted application state, never a second diagram model. */
export interface ObservedTutorialItem {
  readonly kind: TutorialBindingKind
  readonly id: RuntimeId
  readonly workspaceId: number
  readonly projectId: number | null
  readonly parentId?: RuntimeId
  readonly nodeKind?: string
  readonly sourceId?: RuntimeId
  readonly targetId?: RuntimeId
  readonly relationKind?: string
  readonly structural?: boolean
  readonly locked?: boolean
  readonly fields: Readonly<Record<string, string | boolean>>
}

export interface TutorialObservation {
  /** Stable across routes; changes on login/logout. Never a token. */
  readonly sessionId: string
  /** Monotonic counter of accepted state/evidence in this session. */
  readonly sequence: number
  readonly context: TutorialContext
  readonly items: readonly ObservedTutorialItem[]
  readonly selected: RuntimeBindings
  readonly inputs: TutorialParameterValues
  /** Only controls resolved ready by E3.2, with exact instance IDs. */
  readonly readyControls: readonly { target: string; instanceId: RuntimeId | null }[]
  readonly relationKind: string | null
}

/** Captured at action initiation. Async callbacks must not read a newer ticket. */
export interface TutorialTicket {
  readonly instanceId: string
  readonly stepId: string
  readonly visit: number
}

export interface TutorialActionEvidence extends TutorialTicket {
  readonly eventId: string
  readonly sessionId: string
  readonly userId: number
  readonly workspaceId: number
  readonly projectId: number | null
  readonly sequence: number
  readonly origin: 'local' | 'remote'
  readonly action: 'created' | 'changed' | 'selected' | 'saved'
  readonly entity?: TutorialBindingKind
  readonly entityId?: RuntimeId
  readonly nodeKind?: 'CLASS' | 'ENTITY'
  readonly parentId?: RuntimeId
  readonly sourceId?: RuntimeId
  readonly targetId?: RuntimeId
  readonly relationKind?: string
  readonly field?: string
  readonly value?: string | boolean
  /** Creation of a project requires successful API acceptance. */
  readonly accepted?: boolean
  readonly acknowledgedLocalRevision?: number
  readonly acknowledgedServerRevision?: number
}

export interface TutorialRunStart {
  readonly instanceId: string
  readonly bindings: RuntimeBindings
  readonly parameters: TutorialParameterValues
}

export interface TutorialRunSnapshot {
  readonly tutorialId: string
  readonly ticket: TutorialTicket
  readonly status: 'waiting' | 'paused' | 'completed' | 'incomplete' | 'cancelled'
  readonly reason: string | null
  readonly stepIndex: number
  readonly bindings: RuntimeBindings
  readonly outputs: RuntimeBindings
  readonly results: Readonly<Record<string, 'observed' | 'performed' | 'already-satisfied' | 'skipped'>>
}
