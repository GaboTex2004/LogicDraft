import { useEffect, useMemo, useState, type ReactNode } from 'react'
import { useLocation } from 'react-router-dom'
import { obtenerMisWorkspaces } from '../../workspace/api/workspaceApi'
import { TutorScopeContext, TutorTargetContext, type TutorControl, type TutorScopeValue } from '../context/tutorTargetContext'
import { createTutorialTargetRegistry } from '../services/tutorialTargetRegistry.ts'

export function TutorTargetProvider({ children }: { children: ReactNode }) {
  const { pathname } = useLocation()
  const [registry] = useState(() => createTutorialTargetRegistry<TutorControl>())
  const [session] = useState(() => crypto.randomUUID())
  const [access, setAccess] = useState<{ path: string; roles: Map<number, string> } | null>(null)
  useEffect(() => {
    let active = true
    // Read existing membership API; unknown/error means no targeting, never assumed EDITOR.
    void obtenerMisWorkspaces().then(workspaces => {
      if (active) setAccess({ path: pathname, roles: new Map(workspaces.map(item => [item.id, item.rol])) })
    }).catch(() => { if (active) setAccess({ path: pathname, roles: new Map() }) })
    return () => { active = false }
  }, [pathname])
  const value = useMemo(() => ({ registry, sessionId: `${session}:${pathname}`, roles: access?.path === pathname ? access.roles : new Map<number, string>() }), [registry, session, pathname, access])
  return <TutorTargetContext.Provider value={value}>{children}</TutorTargetContext.Provider>
}

export function TutorScope({ children, screen, workspaceId, projectId, blocked = false }: TutorScopeValue & { children: ReactNode }) {
  const value = useMemo(() => ({ screen, workspaceId, projectId, blocked }), [screen, workspaceId, projectId, blocked])
  return <TutorScopeContext.Provider value={value}>{children}</TutorScopeContext.Provider>
}
