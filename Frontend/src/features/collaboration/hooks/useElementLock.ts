import { createContext, useCallback, useContext, useSyncExternalStore } from 'react'
import { ElementLockStore } from '../services/elementLocks'
import type { ResolvedElementLock } from '../types/collaboration.types'

const fallbackLockStore = new ElementLockStore()

export const ElementLocksContext = createContext<ElementLockStore>(fallbackLockStore)

export function useElementLock(elementId: string | null | undefined): ResolvedElementLock | null {
  const store = useContext(ElementLocksContext)
  const getSnapshot = useCallback(() => store.getLock(elementId), [elementId, store])
  return useSyncExternalStore(store.subscribe, getSnapshot, getSnapshot)
}
