import { api } from '../../../shared/api/api'
import type { AgentAskRequest, AgentAskResponse } from '../types/agent.types'

export async function askProjectAgent(projectId: number, request: AgentAskRequest): Promise<AgentAskResponse> {
  const response = await api.post<AgentAskResponse>(`/proyectos/${projectId}/agent/ask`, request)
  return response.data
}

export async function authorizeProjectAgentEdit(projectId: number): Promise<void> {
  await api.post(`/proyectos/${projectId}/agent/authorize-edit`)
}
