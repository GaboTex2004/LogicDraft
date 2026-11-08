import { useCallback, useEffect, useRef, useState } from 'react'
import { useDraggableBubble } from '../hooks/useDraggableBubble'
import { panelPlacement } from '../services/bubblePosition.ts'
import { TutorBubble } from './TutorBubble'
import { TutorPanel } from './TutorPanel'
import '../tutor.css'
import { TutorHighlight } from './TutorHighlight'
import type { TargetAddress } from '../services/tutorialTargetRegistry.ts'
import { TutorLessonControls } from './TutorLessonControls'
import { TutorGuideHighlight } from './TutorGuideHighlight'

export function TutorHost() {
  const [open, setOpen] = useState(false)
  const [highlight, setHighlight] = useState<TargetAddress | null>(null)
  const dismissHighlight = useCallback(() => setHighlight(null), [])
  const [covered, setCovered] = useState(false)
  const buttonRef = useRef<HTMLButtonElement>(null)
  const drag = useDraggableBubble()

  // Existing modal dialogs keep priority, including keyboard focus, over the tutor.
  useEffect(() => {
    const check = () => setCovered(Boolean(document.querySelector('[role="dialog"][aria-modal="true"], dialog[open]')))
    check()
    const observer = new MutationObserver(check)
    observer.observe(document.body, { childList: true, subtree: true, attributes: true, attributeFilter: ['open', 'aria-modal', 'role'] })
    return () => observer.disconnect()
  }, [])

  function close() {
    setOpen(false)
    buttonRef.current?.focus()
  }

  return <div className="tutor-host" hidden={covered} onKeyDown={event => {
    event.stopPropagation()
    if (event.key === 'Escape' && open && !drag.keyboardMove) { event.preventDefault(); close() }
  }}>
    <TutorGuideHighlight />
    {!open && <div className="tutor-active-card"><TutorLessonControls compact /></div>}
    {highlight && <TutorHighlight address={highlight} onDismiss={dismissHighlight} />}
    <span id="logicdraft-tutor-position-help" className="tutor-sr-only">
      Pulsa F2 para mover el robot con las flechas. Mayúsculas mueve más rápido. Intro confirma y Escape cancela.
    </span>
    <span className="tutor-sr-only" role="status">{drag.keyboardMove ? 'Modo mover. Usa las flechas; Intro confirma y Escape cancela.' : ''}</span>
    {open && !covered && !drag.moving && !drag.keyboardMove && <TutorPanel
      style={panelPlacement(drag.position, drag.viewport)} onClose={close} onReset={drag.reset}
      onLocate={address => { setHighlight(address); close() }}
      onMove={() => { drag.toggleKeyboardMove(); buttonRef.current?.focus() }} />}
    <TutorBubble buttonRef={buttonRef} position={drag.position} expanded={open} moving={drag.moving} keyboardMove={drag.keyboardMove}
      {...drag.handlers} onClick={event => { event.stopPropagation(); if (drag.shouldToggle(event.detail)) setOpen(current => !current) }} />
  </div>
}
