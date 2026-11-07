import { useContext, useEffect, useState } from 'react'
import { TutorTargetContext } from '../context/tutorTargetContext'
import type { TargetAddress } from '../services/tutorialTargetRegistry.ts'
import { tutorialControlRect } from '../services/inspectTutorialControl.ts'

export function TutorHighlight({ address, onDismiss }: { address: TargetAddress; onDismiss: () => void }) {
  const runtime = useContext(TutorTargetContext)
  const [rect, setRect] = useState<{ left: number; top: number; width: number; height: number } | null>(null)
  useEffect(() => {
    let frame = 0
    const update = () => {
      const result = runtime?.sessionId === address.sessionId ? runtime.registry.resolve(address) : null
      if (result?.status !== 'ready' || document.querySelector('[role="dialog"][aria-modal="true"], dialog[open]')) { onDismiss(); return }
      const box = tutorialControlRect(result.control.element)
      setRect(current => current?.left === box.left && current.top === box.top && current.width === box.width && current.height === box.height
        ? current : { left: box.left, top: box.top, width: box.width, height: box.height })
      frame = requestAnimationFrame(update)
    }
    const dismiss = (event: Event) => {
      if (event instanceof KeyboardEvent && event.key !== 'Escape') return
      onDismiss()
    }
    frame = requestAnimationFrame(update)
    document.addEventListener('pointerdown', dismiss, true)
    document.addEventListener('keydown', dismiss, true)
    return () => { cancelAnimationFrame(frame); document.removeEventListener('pointerdown', dismiss, true); document.removeEventListener('keydown', dismiss, true) }
  }, [address, runtime, onDismiss])
  return rect && <><div className="tutor-target-highlight" style={rect} aria-hidden="true" /><span className="tutor-sr-only" role="status">Control señalado. Escape o cualquier clic retira el resaltado.</span></>
}
