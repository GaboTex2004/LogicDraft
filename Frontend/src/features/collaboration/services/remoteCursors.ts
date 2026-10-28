import type { CollaborationEvent } from '../types/collaboration.types.ts'

export const CURSOR_UPDATE_INTERVAL_MS = 50 // 20 updates/second (within 15-25 Hz budget)
export const CURSOR_TTL_MS = 15_000
export const CURSOR_KEEPALIVE_MS = 5_000
export const MAX_CURSOR_COORDINATE = 100_000
const MIN_CURSOR_DELTA = 0.5

export const COLLABORATOR_COLORS = [
  '#38bdf8', // sky
  '#34d399', // emerald
  '#fbbf24', // amber
  '#fb7185', // rose
  '#c084fc', // violet
  '#2dd4bf', // teal
  '#f97316', // orange
  '#a3e635', // lime
] as const

export interface FlowPoint {
  x: number
  y: number
}

export interface ViewportTransform {
  x: number
  y: number
  zoom: number
}

export interface RemoteCursor {
  sessionKey: string
  userId: number
  name: string
  label: string
  color: string
  x: number
  y: number
  clientId: string | null
  updatedAt: number
}

interface StoredCursor {
  sessionKey: string
  userId: number
  name: string
  x: number
  y: number
  clientId: string | null
  updatedAt: number
}

function hashSeed(seed: string): number {
  let hash = 2166136261
  for (let i = 0; i < seed.length; i++) {
    hash ^= seed.charCodeAt(i)
    hash = Math.imul(hash, 16777619)
  }
  return hash >>> 0
}

export function collaboratorColorIndex(seed: string | number): number {
  return hashSeed(String(seed)) % COLLABORATOR_COLORS.length
}

export function collaboratorColor(seed: string | number): string {
  return COLLABORATOR_COLORS[collaboratorColorIndex(seed)]
}

/** Projects canonical diagram coordinates into React Flow container pixel coordinates. */
export function projectFlowToViewport(point: FlowPoint, viewport: ViewportTransform): FlowPoint {
  return {
    x: point.x * viewport.zoom + viewport.x,
    y: point.y * viewport.zoom + viewport.y,
  }
}

function isValidCoordinate(value: unknown): value is number {
  return typeof value === 'number' && Number.isFinite(value)
    && value >= -MAX_CURSOR_COORDINATE && value <= MAX_CURSOR_COORDINATE
}

function asRecord(value: unknown): Record<string, unknown> | null {
  return typeof value === 'object' && value !== null ? (value as Record<string, unknown>) : null
}

function resolveSessionKey(event: CollaborationEvent, payload: Record<string, unknown> | null): string {
  if (typeof payload?.sessionId === 'string' && payload.sessionId.length > 0) {
    return payload.sessionId
  }
  if (typeof event.clientId === 'string' && event.clientId.length > 0) {
    return `client:${event.userId}:${event.clientId}`
  }
  return `user:${event.userId}`
}

/**
 * External store for remote cursors so 20 Hz cursor updates re-render only the
 * lightweight overlay instead of the entire React Flow editor tree.
 */
export class RemoteCursorStore {
  private readonly entries = new Map<string, StoredCursor>()
  private readonly listeners = new Set<() => void>()
  private snapshot: readonly RemoteCursor[] = []

  subscribe = (listener: () => void): (() => void) => {
    this.listeners.add(listener)
    return () => {
      this.listeners.delete(listener)
    }
  }

  getSnapshot = (): readonly RemoteCursor[] => this.snapshot

  applyEvent(event: CollaborationEvent, ownClientId: string, now = Date.now()): boolean {
    if (event.clientId && event.clientId === ownClientId) return false
    const payload = asRecord(event.payload)

    if (event.type === 'CURSOR_MOVED') {
      if (!payload || !isValidCoordinate(payload.x) || !isValidCoordinate(payload.y)) return false
      const sessionKey = resolveSessionKey(event, payload)
      this.entries.set(sessionKey, {
        sessionKey,
        userId: event.userId,
        name: event.name,
        x: payload.x,
        y: payload.y,
        clientId: event.clientId,
        updatedAt: now,
      })
      this.rebuildSnapshot()
      return true
    }

    if (event.type === 'CURSOR_LEFT') {
      const sessionKey = resolveSessionKey(event, payload)
      let removed = this.entries.delete(sessionKey)
      if (!removed && event.clientId) {
        for (const [key, cursor] of this.entries) {
          if (cursor.clientId === event.clientId && cursor.userId === event.userId) {
            this.entries.delete(key)
            removed = true
          }
        }
      }
      if (removed) this.rebuildSnapshot()
      return removed
    }

    if (event.type === 'USER_LEFT') {
      let removed = false
      for (const [key, cursor] of this.entries) {
        if (cursor.userId === event.userId) {
          this.entries.delete(key)
          removed = true
        }
      }
      if (removed) this.rebuildSnapshot()
      return removed
    }

    return false
  }

  pruneExpired(now = Date.now(), ttlMs = CURSOR_TTL_MS): boolean {
    let removed = false
    for (const [key, cursor] of this.entries) {
      if (now - cursor.updatedAt > ttlMs) {
        this.entries.delete(key)
        removed = true
      }
    }
    if (removed) this.rebuildSnapshot()
    return removed
  }

  clear(): void {
    if (this.entries.size === 0) return
    this.entries.clear()
    this.rebuildSnapshot()
  }

  private rebuildSnapshot(): void {
    const sorted = [...this.entries.values()].sort((a, b) =>
      a.userId !== b.userId ? a.userId - b.userId : a.sessionKey.localeCompare(b.sessionKey))

    const sessionsPerUser = new Map<number, number>()
    for (const item of sorted) {
      sessionsPerUser.set(item.userId, (sessionsPerUser.get(item.userId) ?? 0) + 1)
    }

    const seenPerUser = new Map<number, number>()
    const usedSlotsPerUser = new Map<number, Set<number>>()

    this.snapshot = sorted.map((item) => {
      const totalForUser = sessionsPerUser.get(item.userId) ?? 1
      const indexForUser = (seenPerUser.get(item.userId) ?? 0) + 1
      seenPerUser.set(item.userId, indexForUser)

      let colorSlot = collaboratorColorIndex(
        totalForUser > 1 ? `${item.userId}:${item.sessionKey}` : item.userId,
      )
      const usedSlots = usedSlotsPerUser.get(item.userId) ?? new Set<number>()
      while (usedSlots.has(colorSlot) && usedSlots.size < COLLABORATOR_COLORS.length) {
        colorSlot = (colorSlot + 1) % COLLABORATOR_COLORS.length
      }
      usedSlots.add(colorSlot)
      usedSlotsPerUser.set(item.userId, usedSlots)

      return {
        sessionKey: item.sessionKey,
        userId: item.userId,
        name: item.name,
        label: totalForUser > 1 ? `${item.name} (${indexForUser})` : item.name,
        color: COLLABORATOR_COLORS[colorSlot],
        x: item.x,
        y: item.y,
        clientId: item.clientId,
        updatedAt: item.updatedAt,
      }
    })

    for (const listener of this.listeners) listener()
  }
}

export interface ThrottleClock {
  now?: () => number
  setTimer?: (callback: () => void, ms: number) => ReturnType<typeof setTimeout>
  clearTimer?: (handle: ReturnType<typeof setTimeout>) => void
}

/**
 * Leading + trailing rate limiter for outgoing cursor positions (~20 Hz).
 * Cancels pending trailing deliveries immediately on leave/dispose so a cursor
 * cannot reappear after leaving the canvas.
 */
export class CursorPublisherThrottle {
  private lastSentAt = -Infinity
  private lastPoint: FlowPoint | null = null
  private pendingPoint: FlowPoint | null = null
  private timer: ReturnType<typeof setTimeout> | null = null
  private active = false
  private readonly sendMove: (point: FlowPoint) => void
  private readonly sendLeave: () => void
  private readonly intervalMs: number
  private readonly now: () => number
  private readonly setTimer: (callback: () => void, ms: number) => ReturnType<typeof setTimeout>
  private readonly clearTimer: (handle: ReturnType<typeof setTimeout>) => void

  constructor(
    sendMove: (point: FlowPoint) => void,
    sendLeave: () => void,
    intervalMs = CURSOR_UPDATE_INTERVAL_MS,
    clock: ThrottleClock = {},
  ) {
    this.sendMove = sendMove
    this.sendLeave = sendLeave
    this.intervalMs = intervalMs
    this.now = clock.now ?? (() => Date.now())
    this.setTimer = clock.setTimer ?? ((cb, ms) => globalThis.setTimeout(cb, ms))
    this.clearTimer = clock.clearTimer ?? ((id) => globalThis.clearTimeout(id))
  }

  move(point: FlowPoint, force = false): void {
    if (!isValidCoordinate(point.x) || !isValidCoordinate(point.y)) return
    const rounded = {
      x: Math.round(point.x * 100) / 100,
      y: Math.round(point.y * 100) / 100,
    }
    if (
      !force &&
      this.active &&
      this.lastPoint &&
      Math.hypot(rounded.x - this.lastPoint.x, rounded.y - this.lastPoint.y) < MIN_CURSOR_DELTA
    ) {
      return
    }
    this.active = true
    const currentNow = this.now()
    const elapsed = currentNow - this.lastSentAt
    if (elapsed >= this.intervalMs) {
      this.cancelTimer()
      this.pendingPoint = null
      this.emit(rounded, currentNow)
      return
    }
    this.pendingPoint = rounded
    if (this.timer === null) {
      this.timer = this.setTimer(() => {
        this.timer = null
        if (!this.active || !this.pendingPoint) return
        const next = this.pendingPoint
        this.pendingPoint = null
        this.emit(next, this.now())
      }, Math.max(1, this.intervalMs - elapsed))
    }
  }

  keepAlive(): void {
    if (!this.active || !this.lastPoint) return
    if (this.now() - this.lastSentAt >= CURSOR_KEEPALIVE_MS) {
      this.move(this.lastPoint, true)
    }
  }

  leave(): void {
    const wasActive = this.active
    this.cancelTimer()
    this.pendingPoint = null
    this.lastPoint = null
    this.active = false
    if (wasActive) this.sendLeave()
  }

  dispose(): void {
    this.leave()
  }

  private emit(point: FlowPoint, timestamp: number): void {
    this.lastSentAt = timestamp
    this.lastPoint = point
    this.sendMove(point)
  }

  private cancelTimer(): void {
    if (this.timer !== null) {
      this.clearTimer(this.timer)
      this.timer = null
    }
  }
}
