import type {
  CollaborationEvent,
  ElementLock,
  ResolvedElementLock,
} from '../types/collaboration.types.ts'
import type { DiagramEdge, EntityFlowNode } from '../../diagram/types/diagram.types.ts'
import { COLLABORATOR_COLORS, collaboratorColorIndex } from './remoteCursors.ts'

export const LOCK_HEARTBEAT_INTERVAL_MS = 10_000
export const LOCK_IDLE_RELEASE_MS = 12_000

function asRecord(value: unknown): Record<string, unknown> | null {
  return typeof value === 'object' && value !== null ? (value as Record<string, unknown>) : null
}

export interface ElementLockedRejection {
  ownerName: string
  ownerUserId?: number
  ownerClientId?: string
  elementId?: string
  message: string
}

export function formatLockedByMessage(ownerName: string): string {
  const cleanName = ownerName.trim() || 'otro usuario'
  return `Está siendo usado por ${cleanName}`
}

export function extractElementLockedRejection(error: unknown): ElementLockedRejection | null {
  const errRecord = asRecord(error)
  if (!errRecord || errRecord.isAxiosError !== true) {
    return null
  }
  const response = asRecord(errRecord.response)
  if (!response || response.status !== 409) {
    return null
  }
  const data = asRecord(response.data)
  if (data?.code !== 'ELEMENT_LOCKED') {
    return null
  }
  const ownerName =
    typeof data.lockedByName === 'string' && data.lockedByName.trim().length > 0
      ? data.lockedByName.trim()
      : 'otro usuario'
  return {
    ownerName,
    ...(typeof data.lockedByUserId === 'number' ? { ownerUserId: data.lockedByUserId } : {}),
    ...(typeof data.lockedByClientId === 'string' ? { ownerClientId: data.lockedByClientId } : {}),
    ...(typeof data.elementId === 'string' ? { elementId: data.elementId } : {}),
    message: formatLockedByMessage(ownerName),
  }
}

export function parseElementLock(value: unknown): ElementLock | null {
  const item = asRecord(value)
  if (
    !item ||
    typeof item.projectId !== 'number' ||
    typeof item.elementId !== 'string' ||
    item.elementId.length === 0 ||
    typeof item.userId !== 'number' ||
    typeof item.userName !== 'string' ||
    item.userName.length === 0 ||
    typeof item.clientId !== 'string' ||
    item.clientId.length === 0 ||
    typeof item.acquiredAt !== 'string' ||
    typeof item.expiresAt !== 'string'
  ) {
    return null
  }
  return {
    projectId: item.projectId,
    elementId: item.elementId,
    userId: item.userId,
    userName: item.userName,
    clientId: item.clientId,
    ...(typeof item.sessionId === 'string' && item.sessionId.length > 0
      ? { sessionId: item.sessionId }
      : {}),
    acquiredAt: item.acquiredAt,
    expiresAt: item.expiresAt,
  }
}

export function parseElementLockList(value: unknown): ElementLock[] {
  if (!Array.isArray(value)) return []
  const result: ElementLock[] = []
  for (const item of value) {
    const parsed = parseElementLock(item)
    if (parsed) result.push(parsed)
  }
  return result
}

function isLockActive(lock: ElementLock, now: number): boolean {
  const expiresMs = Date.parse(lock.expiresAt)
  return Number.isNaN(expiresMs) ? true : expiresMs > now
}

/**
 * External store for per-element locks so lock changes re-render only the
 * affected UML node/edge and properties panel instead of the whole canvas.
 */
export class ElementLockStore {
  private readonly entries = new Map<string, ElementLock>()
  private readonly listeners = new Set<() => void>()
  private snapshot: ReadonlyMap<string, ResolvedElementLock> = new Map()
  private listSnapshot: readonly ResolvedElementLock[] = []
  private ownClientId = ''

  subscribe = (listener: () => void): (() => void) => {
    this.listeners.add(listener)
    return () => {
      this.listeners.delete(listener)
    }
  }

  getMapSnapshot = (): ReadonlyMap<string, ResolvedElementLock> => this.snapshot

  getSnapshot = (): readonly ResolvedElementLock[] => this.listSnapshot

  getLock(elementId: string | null | undefined): ResolvedElementLock | null {
    if (!elementId) return null
    return this.snapshot.get(elementId) ?? null
  }

  findForeignLock(elementIds: Iterable<string>): ResolvedElementLock | null {
    for (const id of elementIds) {
      const lock = this.snapshot.get(id)
      if (lock && !lock.ownedBySelf) {
        return lock
      }
    }
    return null
  }

  areAllOwnedBySelf(elementIds: Iterable<string>): boolean {
    for (const id of elementIds) {
      const lock = this.snapshot.get(id)
      if (!lock || !lock.ownedBySelf) {
        return false
      }
    }
    return true
  }

  ownedElementIds(): string[] {
    const ids: string[] = []
    for (const lock of this.listSnapshot) {
      if (lock.ownedBySelf) ids.push(lock.elementId)
    }
    return ids
  }

  replaceAll(locks: readonly ElementLock[], ownClientId: string, now = Date.now()): void {
    this.ownClientId = ownClientId
    this.entries.clear()
    for (const lock of locks) {
      if (isLockActive(lock, now)) {
        this.entries.set(lock.elementId, lock)
      }
    }
    this.rebuildSnapshot()
  }

  upsertLocks(locks: readonly ElementLock[], ownClientId: string, now = Date.now()): boolean {
    this.ownClientId = ownClientId
    let changed = false
    for (const lock of locks) {
      if (!isLockActive(lock, now)) continue
      this.entries.set(lock.elementId, lock)
      changed = true
    }
    if (changed) this.rebuildSnapshot()
    return changed
  }

  removeElements(elementIds: Iterable<string>, expectedClientId?: string): boolean {
    let removed = false
    for (const id of elementIds) {
      const current = this.entries.get(id)
      if (!current) continue
      if (expectedClientId && current.clientId !== expectedClientId) continue
      this.entries.delete(id)
      removed = true
    }
    if (removed) this.rebuildSnapshot()
    return removed
  }

  applyEvent(event: CollaborationEvent, ownClientId: string, now = Date.now()): boolean {
    this.ownClientId = ownClientId
    const payload = asRecord(event.payload)

    if (event.type === 'ELEMENT_LOCKED') {
      const locks = parseElementLockList(payload?.locks)
      return this.upsertLocks(locks, ownClientId, now)
    }

    if (event.type === 'ELEMENT_UNLOCKED') {
      const rawIds = payload?.elementIds
      if (!Array.isArray(rawIds)) return false
      const ids = rawIds.filter((item): item is string => typeof item === 'string')
      return this.removeElements(ids, event.clientId ?? undefined)
    }

    if (event.type === 'USER_LEFT') {
      let removed = false
      for (const [key, lock] of this.entries) {
        if (lock.userId === event.userId) {
          this.entries.delete(key)
          removed = true
        }
      }
      if (removed) this.rebuildSnapshot()
      return removed
    }

    return false
  }

  pruneExpired(now = Date.now()): boolean {
    let removed = false
    for (const [key, lock] of this.entries) {
      if (!isLockActive(lock, now)) {
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
    const items = [...this.entries.values()].sort((a, b) =>
      a.elementId.localeCompare(b.elementId),
    )

    // Disambiguate multiple simultaneous sessions of the same user consistently with remoteCursors.ts
    const clientsPerUser = new Map<number, Set<string>>()
    for (const item of items) {
      const set = clientsPerUser.get(item.userId) ?? new Set<string>()
      set.add(item.clientId)
      clientsPerUser.set(item.userId, set)
    }

    const clientOrderPerUser = new Map<number, string[]>()
    const colorPerUserClient = new Map<string, string>()

    for (const [userId, clientSet] of clientsPerUser) {
      const sortedClients = [...clientSet].sort()
      clientOrderPerUser.set(userId, sortedClients)
      const usedSlots = new Set<number>()
      for (const clientId of sortedClients) {
        let slot = collaboratorColorIndex(
          sortedClients.length > 1 ? `${userId}:client:${userId}:${clientId}` : userId,
        )
        while (usedSlots.has(slot) && usedSlots.size < COLLABORATOR_COLORS.length) {
          slot = (slot + 1) % COLLABORATOR_COLORS.length
        }
        usedSlots.add(slot)
        colorPerUserClient.set(`${userId}:${clientId}`, COLLABORATOR_COLORS[slot])
      }
    }

    const nextMap = new Map<string, ResolvedElementLock>()
    const nextList: ResolvedElementLock[] = []

    for (const item of items) {
      const clients = clientOrderPerUser.get(item.userId) ?? [item.clientId]
      const sessionIndex = clients.indexOf(item.clientId) + 1
      const label = clients.length > 1 ? `${item.userName} (${sessionIndex})` : item.userName
      const color =
        colorPerUserClient.get(`${item.userId}:${item.clientId}`) ??
        COLLABORATOR_COLORS[collaboratorColorIndex(item.userId)]
      const resolved: ResolvedElementLock = {
        ...item,
        label,
        color,
        ownedBySelf: this.ownClientId.length > 0 && item.clientId === this.ownClientId,
      }
      nextMap.set(item.elementId, resolved)
      nextList.push(resolved)
    }

    this.snapshot = nextMap
    this.listSnapshot = nextList
    for (const listener of this.listeners) listener()
  }
}

export function requiredLockIdsForNodeEdit(
  nodeId: string,
  nodes: readonly EntityFlowNode[],
): string[] {
  const ids = new Set<string>([nodeId])
  const target = nodes.find((node) => node.id === nodeId)
  if (target?.data.association) {
    for (const endpoint of target.data.association.endpoints) {
      ids.add(endpoint.relationshipId)
    }
  }
  return [...ids].sort()
}

export function requiredLockIdsForNodeDelete(
  nodeId: string,
  nodes: readonly EntityFlowNode[],
  edges: readonly DiagramEdge[],
): string[] {
  const ids = new Set<string>(requiredLockIdsForNodeEdit(nodeId, nodes))
  for (const edge of edges) {
    if (edge.source === nodeId || edge.target === nodeId) {
      ids.add(edge.id)
    }
  }
  return [...ids].sort()
}

export function requiredLockIdsForEdgeEdit(
  edgeId: string,
  nodes: readonly EntityFlowNode[],
): string[] {
  const ids = new Set<string>([edgeId])
  for (const node of nodes) {
    if (
      node.data.association?.endpoints.some((endpoint) => endpoint.relationshipId === edgeId)
    ) {
      ids.add(node.id)
      for (const endpoint of node.data.association.endpoints) {
        ids.add(endpoint.relationshipId)
      }
    }
  }
  return [...ids].sort()
}

export function requiredLockIdsForConnection(sourceId: string, targetId: string): string[] {
  return [...new Set([sourceId, targetId])].sort()
}

export function requiredLockIdsForAssociationConversion(edge: DiagramEdge): string[] {
  return [...new Set([edge.id, edge.source, edge.target])].sort()
}
