import { api } from '../../../shared/api/api'
import type { Workspace } from '../types/workspace.types'

export async function obtenerMisWorkspaces(): Promise<Workspace[]> {
  const response = await api.get<Workspace[]>('/workspaces')
  return response.data
}
