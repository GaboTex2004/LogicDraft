import { createContext } from 'react'
import type { createTutorialTargetRegistry } from '../services/tutorialTargetRegistry.ts'
import type { TutorialScreen } from '../types/tutorial.types.ts'

export interface TutorControl { element: Element; label: string }
export interface TutorScopeValue { screen: TutorialScreen; workspaceId: number | null; projectId: number | null; blocked?: boolean }
export interface TutorTargetRuntime {
  sessionId: string
  registry: ReturnType<typeof createTutorialTargetRegistry<TutorControl>>
  roles: ReadonlyMap<number, string>
}
export const TutorTargetContext = createContext<TutorTargetRuntime | null>(null)
export const TutorScopeContext = createContext<TutorScopeValue | null>(null)
