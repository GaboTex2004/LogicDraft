import { api } from '../../../shared/api/api'
import type { AgentAskRequest } from '../../agent/types/agent.types'

/** Dedicated route: shared interpretation, no shared conversation side effects. */
export async function assistEditorDiagram(projectId: number, request: AgentAskRequest, signal?: AbortSignal): Promise<unknown> {
  const response = await api.post<unknown>(`/proyectos/${projectId}/ai/diagram/assist`, request, { signal, timeout: 600_000 })
  return response.data
}
