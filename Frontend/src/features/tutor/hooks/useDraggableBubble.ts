import { useEffect, useRef, useState, type KeyboardEvent, type PointerEvent } from 'react'
import { clampPosition, defaultPosition, dragPosition, type Point, type Viewport } from '../services/bubblePosition.ts'
import { readPosition, savePosition } from '../services/tutorPreferences.ts'

function viewportNow(): Viewport {
  const visual = window.visualViewport
  return { width: visual?.width ?? window.innerWidth, height: visual?.height ?? window.innerHeight, left: visual?.offsetLeft ?? 0, top: visual?.offsetTop ?? 0 }
}

export function useDraggableBubble() {
  const [viewport, setViewport] = useState(viewportNow)
  const [position, setPosition] = useState(() => clampPosition(readPosition() ?? defaultPosition(viewport), viewport))
  const [moving, setMoving] = useState(false)
  const [keyboardMove, setKeyboardMove] = useState(false)
  const keyboardOrigin = useRef<Point | null>(null)
  const pointer = useRef<{ id: number; start: Point; origin: Point; moved: boolean } | null>(null)
  const suppressClick = useRef(false)

  useEffect(() => {
    const resize = () => {
      const next = viewportNow()
      setViewport(next)
      setPosition(current => clampPosition(current, next))
    }
    window.addEventListener('resize', resize)
    window.visualViewport?.addEventListener('resize', resize)
    window.visualViewport?.addEventListener('scroll', resize)
    return () => {
      window.removeEventListener('resize', resize)
      window.visualViewport?.removeEventListener('resize', resize)
      window.visualViewport?.removeEventListener('scroll', resize)
    }
  }, [])

  useEffect(() => { if (!moving && !keyboardMove) savePosition(position) }, [position, moving, keyboardMove])

  function onPointerDown(event: PointerEvent<HTMLButtonElement>) {
    if (!event.isPrimary || event.button !== 0 || keyboardMove) return
    event.stopPropagation()
    suppressClick.current = false
    pointer.current = { id: event.pointerId, start: { x: event.clientX, y: event.clientY }, origin: position, moved: false }
    event.currentTarget.setPointerCapture(event.pointerId)
  }

  function onPointerMove(event: PointerEvent<HTMLButtonElement>) {
    const active = pointer.current
    if (!active || active.id !== event.pointerId) return
    const next = dragPosition(active.origin, active.start, { x: event.clientX, y: event.clientY }, viewportNow())
    active.moved ||= next.moved
    if (!active.moved) return
    event.preventDefault()
    event.stopPropagation()
    suppressClick.current = true
    setMoving(true)
    setPosition(next.position)
  }

  function finishPointer(event: PointerEvent<HTMLButtonElement>) {
    const active = pointer.current
    if (!active || active.id !== event.pointerId) return
    event.stopPropagation()
    suppressClick.current = active.moved || event.type === 'pointercancel'
    if (event.type === 'pointercancel') setPosition(clampPosition(active.origin, viewportNow()))
    pointer.current = null
    setMoving(false)
    if (event.currentTarget.hasPointerCapture(event.pointerId)) event.currentTarget.releasePointerCapture(event.pointerId)
  }

  function toggleKeyboardMove() {
    keyboardOrigin.current = position
    setKeyboardMove(current => !current)
  }

  function onKeyDown(event: KeyboardEvent<HTMLButtonElement>) {
    if (event.key === 'F2') { event.preventDefault(); event.stopPropagation(); toggleKeyboardMove(); return }
    if (!keyboardMove) return
    event.stopPropagation()
    if (['Enter', ' ', 'Escape'].includes(event.key)) {
      event.preventDefault()
      if (event.key === 'Escape' && keyboardOrigin.current) setPosition(clampPosition(keyboardOrigin.current, viewportNow()))
      setKeyboardMove(false)
      return
    }
    const directions: Record<string, Point> = { ArrowLeft: { x: -1, y: 0 }, ArrowRight: { x: 1, y: 0 }, ArrowUp: { x: 0, y: -1 }, ArrowDown: { x: 0, y: 1 } }
    const delta = directions[event.key]
    if (delta) {
      event.preventDefault()
      const distance = event.shiftKey ? 32 : 12
      setPosition(current => clampPosition({ x: current.x + delta.x * distance, y: current.y + delta.y * distance }, viewportNow()))
    }
  }

  function shouldToggle(detail: number) {
    if (detail === 0) return !keyboardMove
    const blocked = suppressClick.current
    suppressClick.current = false
    return !blocked && !keyboardMove
  }

  function reset() {
    setKeyboardMove(false)
    setPosition(defaultPosition(viewportNow()))
  }

  return { position, viewport, moving, keyboardMove, reset, toggleKeyboardMove, shouldToggle,
    handlers: { onPointerDown, onPointerMove, onPointerUp: finishPointer, onPointerCancel: finishPointer, onLostPointerCapture: finishPointer, onKeyDown } }
}
