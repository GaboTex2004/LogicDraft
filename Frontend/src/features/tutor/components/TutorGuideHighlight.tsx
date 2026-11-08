import { useContext, useEffect, useState, useSyncExternalStore } from 'react'
import { useTutorRuntime } from '../hooks/useTutorRuntime'
import { TutorTargetContext } from '../context/tutorTargetContext'
import { tutorials } from '../content/catalog.ts'
import { tutorialControlRect } from '../services/inspectTutorialControl.ts'

export function TutorGuideHighlight() {
  const runtime = useTutorRuntime()!
  const target = useContext(TutorTargetContext)!
  const view = useSyncExternalStore(runtime.subscribe, runtime.getSnapshot, runtime.getSnapshot)
  const [rect, setRect] = useState<ReturnType<typeof tutorialControlRect> | null>(null)
  useEffect(() => {
    const update = () => {
      const run = view.run
      const step = tutorials.find(item => item.id === run?.tutorialId)?.steps[run?.stepIndex ?? 0]
      const candidates = view.active && run?.status === 'waiting' && step ? target.registry.addresses().filter(address => address.sessionId === target.sessionId && address.target === step.target
        && address.workspaceId === run.bindings.workspace && address.projectId === (view.scene?.projectId ?? null)
        && (!step.binding || address.instanceId === run.bindings[step.binding])) : []
      const found = candidates.length === 1 ? target.registry.resolve(candidates[0]) : null
      setRect(found?.status === 'ready' ? tutorialControlRect(found.control.element) : null)
    }
    const timer = window.setInterval(update, 200)
    return () => window.clearInterval(timer)
  }, [view, target])
  return rect && <div aria-hidden="true" className="tutor-target-highlight" style={rect} />
}
