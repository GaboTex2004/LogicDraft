import type { Point } from './bubblePosition.ts'

export const POSITION_KEY = 'logicdraft.tutor.position.v1'

export function parsePosition(raw: string | null): Point | null {
  if (!raw) return null
  try {
    const value: unknown = JSON.parse(raw)
    if (!value || typeof value !== 'object') return null
    const record = value as Record<string, unknown>
    if (record.version !== 1 || typeof record.x !== 'number' || typeof record.y !== 'number'
      || !Number.isFinite(record.x) || !Number.isFinite(record.y)) return null
    return { x: record.x, y: record.y }
  } catch { return null }
}

/** Presentation preference only. Never stores tokens, account IDs or lesson data. */
export function readPosition(): Point | null {
  try { return parsePosition(localStorage.getItem(POSITION_KEY)) } catch { return null }
}

export function savePosition(point: Point): void {
  try { localStorage.setItem(POSITION_KEY, JSON.stringify({ version: 1, ...point })) } catch { /* Storage is optional. */ }
}
