import { useContext, type RefCallback } from 'react'
import { TutorScopeContext, TutorTargetContext, type TutorScopeValue } from '../context/tutorTargetContext'
import type { TutorialTargetId } from '../content/targets.ts'
import { inspectTutorialControl } from '../services/inspectTutorialControl.ts'

/** React 19 callback-ref cleanup handles unmount/replacement, including StrictMode.
 * May be called without a provider: existing isolated components remain usable.
 */
export function useTutorTargets(override?: TutorScopeValue) {
  const runtime = useContext(TutorTargetContext)
  const inherited = useContext(TutorScopeContext)
  const scope = override ?? inherited
  return (target: TutorialTargetId, instanceId: string | number | null = null, label = '', editable = true, allowed = true): RefCallback<Element> => {
    let dispose: (() => void) | undefined
    return element => {
      // Composed refs (Link, React Flow) may use the older null callback convention.
      dispose?.()
      dispose = undefined
      if (!element || !runtime || !scope || scope.workspaceId === null) return
      const role = runtime.roles.get(scope.workspaceId)
      const permitted = allowed && !scope.blocked && (editable ? role === 'OWNER' || role === 'EDITOR' : ['OWNER', 'EDITOR', 'VIEWER'].includes(role ?? ''))
      const cleanup = runtime.registry.register({ sessionId: runtime.sessionId, screen: scope.screen, workspaceId: scope.workspaceId, projectId: scope.projectId, target, instanceId },
        { element, label }, () => inspectTutorialControl(element, permitted))
      dispose = cleanup
      return () => { cleanup(); if (dispose === cleanup) dispose = undefined }
    }
  }
}
