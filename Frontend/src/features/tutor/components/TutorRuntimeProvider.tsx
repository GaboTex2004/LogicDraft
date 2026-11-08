import { useContext, useEffect, useState, type ReactNode } from 'react'
import { obtenerUsuarioActual } from '../../auth/api/authApi'
import { TutorTargetContext } from '../context/tutorTargetContext'
import { TutorRuntimeContext } from '../context/tutorRuntimeContext'
import { createTutorRuntimeStore } from '../services/tutorialRuntimeStore.ts'

export function TutorRuntimeProvider({ sessionId, path, ready, children }: { sessionId: string; path: string; ready: boolean; children: ReactNode }) {
  const target = useContext(TutorTargetContext)!
  const [store] = useState(createTutorRuntimeStore)
  const [userId, setUserId] = useState<number | null>(null)
  useEffect(() => {
    let active = true
    void obtenerUsuarioActual().then(user => { if (active) setUserId(user.id) }).catch(() => { if (active) setUserId(null) })
    return () => { active = false }
  }, [])
  useEffect(() => {
    store.setEnvironment({ sessionId, userId: userId ?? 0, path, ready: ready && userId !== null, roles: target.roles })
  }, [store, sessionId, userId, path, ready, target.roles])
  useEffect(() => {
    const refresh = () => {
      if (!store.getSnapshot().active) return
      const controls = target.registry.addresses().filter(address => address.sessionId === target.sessionId && target.registry.resolve(address).status === 'ready')
        .map(address => ({ target: address.target, instanceId: address.instanceId }))
      store.setControls(controls)
    }
    const timer = window.setInterval(refresh, 200)
    return () => window.clearInterval(timer)
  }, [store, target])
  return <TutorRuntimeContext.Provider value={store}>{children}</TutorRuntimeContext.Provider>
}
