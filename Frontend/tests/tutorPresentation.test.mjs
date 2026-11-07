import test from 'node:test'
import assert from 'node:assert/strict'
import { clampPosition, defaultPosition, dragPosition, panelPlacement } from '../src/features/tutor/services/bubblePosition.ts'
import { parsePosition } from '../src/features/tutor/services/tutorPreferences.ts'

const desktop = { width: 1280, height: 720, left: 0, top: 0 }

test('la posición recuperada queda visible después de reducir la ventana', () => {
  const position = clampPosition({ x: 1900, y: 1000 }, desktop)
  assert.deepEqual(position, { x: 1212, y: 652 })
  assert.deepEqual(clampPosition({ x: -100, y: -100 }, desktop), { x: 12, y: 12 })
})

test('el desplazamiento del viewport visual se respeta', () => {
  const viewport = { width: 390, height: 400, left: 20, top: 100 }
  assert.deepEqual(clampPosition({ x: 0, y: 0 }, viewport), { x: 32, y: 112 })
  const position = defaultPosition(viewport)
  assert.ok(position.x + 56 <= 410 && position.y + 56 <= 500)
})

test('un movimiento pequeño es clic y el arrastre queda limitado a pantalla', () => {
  const origin = { x: 100, y: 100 }
  assert.equal(dragPosition(origin, origin, { x: 102, y: 103 }, desktop).moved, false)
  const dragged = dragPosition(origin, origin, { x: 2000, y: -300 }, desktop)
  assert.equal(dragged.moved, true)
  assert.deepEqual(dragged.position, { x: 1212, y: 12 })
})

test('el panel permanece dentro del viewport en escritorio y móvil', () => {
  for (const viewport of [desktop, { width: 390, height: 844, left: 0, top: 0 }, { width: 320, height: 280, left: 40, top: 60 }]) {
    for (const point of [{ x: 0, y: 0 }, defaultPosition(viewport)]) {
      const panel = panelPlacement(point, viewport)
      assert.ok(panel.left >= viewport.left && panel.top >= viewport.top)
      assert.ok(panel.left + panel.width <= viewport.left + viewport.width)
      assert.ok(panel.top + panel.height <= viewport.top + viewport.height)
    }
  }
})

test('preferencias ausentes, corruptas o incompatibles no rompen el inicio', () => {
  for (const raw of [null, '', '{', 'null', '[]', '{"version":2,"x":1,"y":2}', '{"version":1,"x":"1","y":2}', '{"version":1,"x":1e999,"y":2}']) {
    assert.equal(parsePosition(raw), null)
  }
  assert.deepEqual(parsePosition('{"version":1,"x":80,"y":100}'), { x: 80, y: 100 })
})
