import type { TutorialTargetId } from '../content/targets.ts'
import { tutorialTargets } from '../content/targets.ts'
import type { TutorialScreen } from '../types/tutorial.types.ts'

/** Session is an ephemeral UI identifier, never a JWT. All scopes match exactly. */
export interface TargetAddress {
  readonly sessionId: string
  readonly screen: TutorialScreen
  readonly workspaceId: number | null
  readonly projectId: number | null
  readonly target: TutorialTargetId
  readonly instanceId: string | number | null
}

/** An adapter must observe these values again on every resolve, not cache them. */
export interface TargetAvailability {
  readonly mounted: boolean
  readonly visible: boolean
  readonly enabled: boolean
  readonly permitted: boolean
}

export type TargetResolution<T> =
  | { readonly status: 'ready'; readonly control: T }
  | { readonly status: 'missing' | 'ambiguous' | 'unavailable' }

function valid(address: TargetAddress): boolean {
  const definition = tutorialTargets[address.target]
  if (!definition || !address.sessionId.trim() || definition.screen !== address.screen) return false
  const hasInstance = 'instance' in definition
  if (hasInstance && (address.instanceId === null || address.instanceId === '')) return false
  if (!hasInstance && address.instanceId !== null) return false
  if (address.screen === 'projects' && address.workspaceId === null) return false
  if (address.screen === 'editor' && (address.workspaceId === null || address.projectId === null)) return false
  return true
}

function same(a: TargetAddress, b: TargetAddress): boolean {
  return a.sessionId === b.sessionId && a.screen === b.screen && a.workspaceId === b.workspaceId
    && a.projectId === b.projectId && a.target === b.target && a.instanceId === b.instanceId
}

/** No selectors, global singleton, DOM mutations, timers or business operations.
 * Create one registry per tutor host. Register from ref adapters; dispose on unmount.
 */
export function createTutorialTargetRegistry<T>() {
  const entries = new Map<symbol, { address: TargetAddress; control: T; inspect: () => TargetAvailability }>()

  return {
    register(address: TargetAddress, control: T, inspect: () => TargetAvailability): () => void {
      if (!valid(address)) throw new Error('Invalid tutorial target address')
      const token = Symbol(address.target)
      entries.set(token, { address: { ...address }, control, inspect })
      // A stale cleanup cannot remove a new registration of the same logical target.
      return () => { entries.delete(token) }
    },

    resolve(address: TargetAddress): TargetResolution<T> {
      if (!valid(address)) return { status: 'missing' }
      const matches = [...entries.values()].filter(entry => same(entry.address, address))
      if (!matches.length) return { status: 'missing' }
      // Never guess between duplicated controls, even if only one looks enabled.
      if (matches.length > 1) return { status: 'ambiguous' }
      const entry = matches[0]
      try {
        const state = entry.inspect()
        if (state.mounted && state.visible && state.enabled && state.permitted) {
          return { status: 'ready', control: entry.control }
        }
      } catch { /* A disappeared or invalid adapter pauses targeting. */ }
      return { status: 'unavailable' }
    },

    addresses(): TargetAddress[] { return [...entries.values()].map(entry => ({ ...entry.address })) },
    clear(): void { entries.clear() },
  }
}
