import { api } from '../../../shared/api/api'

export type DiagramAiSelection =
  | { kind: 'ENTITY'; entityName: string }
  | { kind: 'RELATIONSHIP'; sourceEntity: string; targetEntity: string; relationshipName?: string }

// Keep untrusted network data unknown until the operation applicator validates it.
export async function interpretDiagramWithAi(projectId: number, prompt: string,
  selection?: DiagramAiSelection, signal?: AbortSignal): Promise<unknown> {
  const response = await api.post<unknown>(`/proyectos/${projectId}/ai/diagram/interpret`,
    { prompt, ...(selection ? { selection } : {}) }, { signal, timeout: 100_000 })
  return response.data
}
