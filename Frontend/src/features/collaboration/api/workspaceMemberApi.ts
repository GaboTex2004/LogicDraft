import { api } from '../../../shared/api/api'
import type { AddWorkspaceMemberRequest, WorkspaceMember, WorkspaceRole } from '../types/collaboration.types'

export async function getWorkspaceMembers(workspaceId: number): Promise<WorkspaceMember[]> {
  const response = await api.get<WorkspaceMember[]>(`/workspaces/${workspaceId}/miembros`)
  return response.data
}

export async function addWorkspaceMember(
  workspaceId: number,
  request: AddWorkspaceMemberRequest,
): Promise<WorkspaceMember> {
  const response = await api.post<WorkspaceMember>(`/workspaces/${workspaceId}/miembros`, request)
  return response.data
}

export async function changeWorkspaceMemberRole(
  workspaceId: number,
  userId: number,
  rol: WorkspaceRole,
): Promise<WorkspaceMember> {
  const response = await api.put<WorkspaceMember>(
    `/workspaces/${workspaceId}/miembros/${userId}`,
    { rol },
  )
  return response.data
}

export async function removeWorkspaceMember(workspaceId: number, userId: number): Promise<void> {
  await api.delete(`/workspaces/${workspaceId}/miembros/${userId}`)
}
