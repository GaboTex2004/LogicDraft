import type { AttributeType, DiagramCardinality } from '../../diagram/types/diagram.types.ts'

export type TutorialScreen = 'dashboard' | 'projects' | 'editor'
export type TutorialRole = 'OWNER' | 'EDITOR' | 'VIEWER'
export type TutorialBindingKind = 'workspace' | 'project' | 'node' | 'attribute' | 'relationship'
export type TutorialBinding = 'workspace' | 'project' | 'node' | 'attribute' | 'sourceNode' | 'targetNode' | 'relationship'
export type TutorialParameter = 'projectName' | 'nodeName' | 'attributeName' | 'attributeType' | 'sourceCardinality' | 'targetCardinality'
export type TutorialParameterValues = Partial<{
  projectName: string
  nodeName: string
  attributeName: string
  attributeType: AttributeType
  sourceCardinality: DiagramCardinality
  targetCardinality: DiagramCardinality
}>

/** Logical IDs, not CSS selectors. Registered refs do not imply an executable lesson. */
export interface TutorialTarget {
  readonly screen: TutorialScreen
  readonly sourceFile: string
  readonly control: string
  readonly instance?: TutorialBindingKind
  readonly integration: 'pending' | 'registered'
}

export type TutorialCondition =
  | { readonly kind: 'screen'; readonly screen: TutorialScreen; readonly scope: 'workspace' | 'project' }
  | { readonly kind: 'control-ready'; readonly target: string }
  | { readonly kind: 'input-matches'; readonly parameter: TutorialParameter }
  | { readonly kind: 'input-valid'; readonly parameter: 'projectName' }
  | { readonly kind: 'created'; readonly entity: TutorialBindingKind; readonly capture: TutorialBinding; readonly nodeKind?: 'CLASS' | 'ENTITY' }
  | { readonly kind: 'selected'; readonly binding: TutorialBinding }
  | { readonly kind: 'field-matches'; readonly binding: TutorialBinding; readonly field: 'name' | 'type' | 'sourceCardinality' | 'targetCardinality'; readonly parameter: TutorialParameter }
  | { readonly kind: 'primary-key-enabled'; readonly binding: 'attribute' }
  | { readonly kind: 'relation-kind'; readonly value: 'ASSOCIATION' }
  | { readonly kind: 'saved-revision'; readonly scope: 'project'; readonly require: 'all-local-changes-acknowledged' }

export interface TutorialStep {
  readonly id: string
  readonly target: string
  readonly binding?: TutorialBinding
  /** Visible and spoken text. Only declared {{parameters}} may be substituted. */
  readonly text: string
  readonly hint: string
  readonly completion: TutorialCondition
}

export interface TutorialDefinition {
  readonly id: string
  readonly contentVersion: 1
  readonly domain: 'platform' | 'modeling'
  readonly title: string
  readonly examples: readonly string[]
  readonly entryScreens: readonly TutorialScreen[]
  readonly roles: readonly TutorialRole[]
  readonly documentVersions: readonly (1 | 2)[]
  /** Conditional navigation only: skip if its resulting context already exists. */
  readonly prerequisites: readonly string[]
  readonly inputs: readonly TutorialBinding[]
  readonly parameters: readonly TutorialParameter[]
  readonly outputs: readonly TutorialBinding[]
  readonly steps: readonly TutorialStep[]
}

export interface TutorialCatalog {
  readonly version: 1
  readonly locale: 'es'
  readonly runtimeAvailable: boolean
  readonly targets: Readonly<Record<string, TutorialTarget>>
  readonly tutorials: readonly TutorialDefinition[]
}

export interface TutorialJourneyItem {
  readonly id: string
  readonly tutorialId: string
  readonly parameters: TutorialParameterValues
  /** Logical lesson slots, resolved to actual IDs by the future engine. */
  readonly inputs: Partial<Record<TutorialBinding, string>>
  readonly outputs: Partial<Record<TutorialBinding, string>>
}

export interface TutorialJourney {
  readonly id: string
  readonly catalogVersion: 1
  readonly title: string
  readonly items: readonly TutorialJourneyItem[]
}

/** Minimal observed context; never carries JWTs or a copy of the diagram. */
export interface TutorialContext {
  readonly userId: number
  readonly screen: TutorialScreen | 'other'
  readonly workspaceId: number | null
  readonly projectId: number | null
  readonly role: TutorialRole | null
  readonly documentVersion: 1 | 2 | null
  readonly bindings: Partial<Record<TutorialBinding, string | number>>
  readonly localRevision: number | null
  readonly persistedLocalRevision: number | null
  readonly serverRevision: number | null
  readonly saveState: 'clean' | 'dirty' | 'saving' | 'saved' | 'error' | 'conflict' | 'forbidden'
}

export interface TutorialProgress {
  readonly catalogVersion: number
  readonly userId: number
  readonly projectId: number | null
  readonly instanceId: string
  readonly tutorialId: string
  readonly contentVersion: number
  readonly stepId: string
  readonly state: 'ready' | 'active' | 'waiting' | 'paused' | 'completed'
  readonly completedStepIds: readonly string[]
  readonly skippedStepIds: readonly string[]
}

/** Repeated/remote events do not demonstrate a local exercise was performed. */
export interface TutorialEvidence {
  readonly eventId: string
  readonly origin: 'local' | 'remote'
  readonly projectId: number | null
  readonly occurredAt: number
  readonly instanceId: string
  readonly bindingIds: Partial<Record<TutorialBinding, string | number>>
  readonly localRevision: number | null
  readonly acknowledgedServerRevision: number | null
}
