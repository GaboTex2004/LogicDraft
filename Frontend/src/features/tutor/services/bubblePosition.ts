export interface Point { x: number; y: number }
export interface Viewport { width: number; height: number; left: number; top: number }
export const BUBBLE_SIZE = 56
export const BUBBLE_MARGIN = 12
export const DRAG_THRESHOLD = 6

export function clampPosition(point: Point, viewport: Viewport): Point {
  const maxX = Math.max(viewport.left, viewport.left + viewport.width - BUBBLE_SIZE - BUBBLE_MARGIN)
  const maxY = Math.max(viewport.top, viewport.top + viewport.height - BUBBLE_SIZE - BUBBLE_MARGIN)
  return {
    x: Math.min(maxX, Math.max(Math.min(maxX, viewport.left + BUBBLE_MARGIN), point.x)),
    y: Math.min(maxY, Math.max(Math.min(maxY, viewport.top + BUBBLE_MARGIN), point.y)),
  }
}

export function defaultPosition(viewport: Viewport): Point {
  return clampPosition({ x: viewport.left + viewport.width - 80, y: viewport.top + viewport.height - 96 }, viewport)
}

export function panelPlacement(point: Point, viewport: Viewport) {
  const width = Math.max(0, Math.min(360, viewport.width - 24))
  const height = Math.max(0, Math.min(500, viewport.height - 96))
  if (viewport.width <= 600) return { left: viewport.left + 12, top: viewport.top + 12, width, height }
  const left = Math.max(viewport.left + 12, Math.min(point.x + BUBBLE_SIZE - width, viewport.left + viewport.width - width - 12))
  const top = point.y - height - 12 >= viewport.top + 12
    ? point.y - height - 12
    : Math.min(point.y + BUBBLE_SIZE + 12, viewport.top + viewport.height - height - 12)
  return { left, top: Math.max(viewport.top + 12, top), width, height }
}

/** Same state transitions used by pointer handlers and unit tests. */
export function dragPosition(origin: Point, start: Point, current: Point, viewport: Viewport) {
  const delta = { x: current.x - start.x, y: current.y - start.y }
  return {
    moved: Math.hypot(delta.x, delta.y) >= DRAG_THRESHOLD,
    position: clampPosition({ x: origin.x + delta.x, y: origin.y + delta.y }, viewport),
  }
}
