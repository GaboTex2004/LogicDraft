import type { ButtonHTMLAttributes, Ref } from 'react'
import { AppIcon } from '../../../shared/components/AppIcon'
import type { Point } from '../services/bubblePosition.ts'

interface Props extends ButtonHTMLAttributes<HTMLButtonElement> {
  buttonRef: Ref<HTMLButtonElement>
  position: Point
  expanded: boolean
  moving: boolean
  keyboardMove: boolean
}

export function TutorBubble({ buttonRef, position, expanded, moving, keyboardMove, ...props }: Props) {
  return <button {...props} ref={buttonRef} type="button"
    className={`tutor-bubble${moving ? ' is-dragging' : ''}${keyboardMove ? ' is-positioning' : ''}`}
    style={{ left: position.x, top: position.y }}
    aria-label={expanded ? 'Cerrar instructor de LogicDraft' : 'Abrir instructor de LogicDraft'}
    aria-expanded={expanded} aria-controls={expanded ? 'logicdraft-tutor-panel' : undefined}
    aria-describedby="logicdraft-tutor-position-help"
    title="Instructor de LogicDraft · Arrastra para mover · F2 para mover con teclado">
    <AppIcon name="robot" />
  </button>
}
