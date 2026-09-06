import { api } from '../../../shared/api/api'

// Keep untrusted network data unknown until the operation applicator validates it.
export async function interpretDiagramWithAi(projectId: number, prompt: string, signal?: AbortSignal): Promise<unknown> {
  const response = await api.post<unknown>(`/proyectos/${projectId}/ai/diagram/interpret`, { prompt }, { signal, timeout: 100_000 })
  return response.data
}
