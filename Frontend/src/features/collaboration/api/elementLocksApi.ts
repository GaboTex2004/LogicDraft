import { api } from '../../../shared/api/api'
import type { ElementLock } from '../types/collaboration.types'
import {
  extractElementLockedRejection,
  parseElementLockList,
  type ElementLockedRejection,
} from '../services/elementLocks'

export { extractElementLockedRejection, type ElementLockedRejection }

export type AcquireElementLocksResponse =
  | { granted: true; locks: ElementLock[] }
  | ({ granted: false } & ElementLockedRejection)

export async function listarElementLocks(projectId: number): Promise<ElementLock[]> {
  const response = await api.get<{ locks?: unknown }>(`/proyectos/${projectId}/locks`)
  return parseElementLockList(response.data?.locks)
}

export async function adquirirElementLocks(
  projectId: number,
  clientId: string,
  elementIds: readonly string[],
): Promise<AcquireElementLocksResponse> {
  try {
    const response = await api.post<{ granted?: boolean; locks?: unknown }>(
      `/proyectos/${projectId}/locks/acquire`,
      { clientId, elementIds: [...new Set(elementIds)].sort() },
    )
    return {
      granted: true,
      locks: parseElementLockList(response.data?.locks),
    }
  } catch (error: unknown) {
    const rejection = extractElementLockedRejection(error)
    if (rejection) {
      return { granted: false, ...rejection }
    }
    throw error
  }
}

export async function renovarElementLocks(
  projectId: number,
  clientId: string,
  elementIds?: readonly string[],
): Promise<ElementLock[]> {
  const response = await api.post<{ locks?: unknown }>(
    `/proyectos/${projectId}/locks/renew`,
    {
      clientId,
      ...(elementIds && elementIds.length > 0
        ? { elementIds: [...new Set(elementIds)].sort() }
        : {}),
    },
  )
  return parseElementLockList(response.data?.locks)
}

export async function liberarElementLocks(
  projectId: number,
  clientId: string,
  elementIds?: readonly string[],
): Promise<string[]> {
  const response = await api.post<{ releasedElementIds?: unknown }>(
    `/proyectos/${projectId}/locks/release`,
    {
      clientId,
      ...(elementIds && elementIds.length > 0
        ? { elementIds: [...new Set(elementIds)].sort() }
        : {}),
    },
  )
  const raw = response.data?.releasedElementIds
  return Array.isArray(raw) ? raw.filter((item): item is string => typeof item === 'string') : []
}
