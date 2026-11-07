import type { TargetAvailability } from './tutorialTargetRegistry.ts'

export function tutorialControlRect(element: Element) {
  const rect = element.getBoundingClientRect()
  // SVG paths can have zero height/width (horizontal/vertical relationships).
  const path = element.querySelector('path.react-flow__edge-interaction')
  const pad = path ? 3 : 0
  return { left: rect.left - pad, top: rect.top - pad, right: rect.right + pad, bottom: rect.bottom + pad, width: rect.width + pad * 2, height: rect.height + pad * 2 }
}

/** Read-only DOM observation. Does not focus, click, scroll or change a control. */
export function inspectTutorialControl(element: Element, permitted: boolean): TargetAvailability {
  const mounted = element.isConnected
  const view = element.ownerDocument.defaultView
  const rect = tutorialControlRect(element)
  let visible = mounted && Boolean(view) && rect.width > 0 && rect.height > 0
  let enabled = !element.matches(':disabled, [readonly], [aria-disabled="true"]')
  let left = Math.max(0, rect.left), top = Math.max(0, rect.top)
  let right = Math.min(view?.innerWidth ?? 0, rect.right), bottom = Math.min(view?.innerHeight ?? 0, rect.bottom)
  for (let parent: Element | null = element; parent && view; parent = parent.parentElement) {
    const style = view.getComputedStyle(parent)
    if (parent.hasAttribute('hidden') || parent.hasAttribute('inert') || parent.getAttribute('aria-hidden') === 'true'
      || style.display === 'none' || style.visibility === 'hidden' || style.visibility === 'collapse' || Number(style.opacity) === 0) visible = false
    if (parent.getAttribute('aria-disabled') === 'true' || parent.hasAttribute('inert')) enabled = false
    if (parent !== element) {
      const bounds = parent.getBoundingClientRect()
      if (/(hidden|clip|scroll|auto)/.test(style.overflowX)) { left = Math.max(left, bounds.left); right = Math.min(right, bounds.right) }
      if (/(hidden|clip|scroll|auto)/.test(style.overflowY)) { top = Math.max(top, bounds.top); bottom = Math.min(bottom, bounds.bottom) }
    }
  }
  visible &&= right > left && bottom > top
  // A partially clipped or covered control must not be announced as actionable.
  visible &&= left <= rect.left + 1 && top <= rect.top + 1 && right >= rect.right - 1 && bottom >= rect.bottom - 1
  if (visible) {
    let x = (left + right) / 2, y = (top + bottom) / 2
    const path = element.querySelector('path.react-flow__edge-interaction')
    if (path instanceof SVGPathElement) {
      const matrix = path.getScreenCTM()
      if (matrix) { const point = path.getPointAtLength(path.getTotalLength() / 2).matrixTransform(matrix); x = point.x; y = point.y }
    }
    const hit = element.ownerDocument.elementFromPoint(x, y)
    visible = Boolean(hit && (hit === element || element.contains(hit)))
  }
  return { mounted, visible, enabled, permitted }
}
