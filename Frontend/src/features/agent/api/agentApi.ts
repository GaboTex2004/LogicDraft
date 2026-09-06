import { api } from '../../../shared/api/api'
import type { AgentAskRequest, AgentAskResponse } from '../types/agent.types'

export async function askProjectAgent(projectId: number, request: AgentAskRequest): Promise<AgentAskResponse> {
  const response = await api.post<AgentAskResponse>(`/proyectos/${projectId}/agent/ask`, request)
  return response.data
}
