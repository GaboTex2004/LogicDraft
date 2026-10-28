import type { ReactNode } from 'react'
import { ElementLocksContext } from '../hooks/useElementLock'
import type { ElementLockStore } from '../services/elementLocks'

export function ElementLocksProvider({
  store,
  children,
}: {
  store: ElementLockStore
  children: ReactNode
}) {
  return <ElementLocksContext.Provider value={store}>{children}</ElementLocksContext.Provider>
}
