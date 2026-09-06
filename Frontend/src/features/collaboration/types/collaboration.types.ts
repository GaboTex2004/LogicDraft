export type CollaborationEventType =
  | 'USER_JOINED'
  | 'USER_LEFT'
  | 'PING'
  | 'NODE_CREATED'
  | 'NODE_MOVED'
  | 'NODE_UPDATED'
  | 'NODE_DELETED'
  | 'EDGE_CREATED'
  | 'EDGE_UPDATED'
  | 'EDGE_DELETED'
  | 'DIAGRAM_SAVED'

export type DiagramCollaborationEventType = Exclude<
  CollaborationEventType,
  'USER_JOINED' | 'USER_LEFT' | 'PING'
>

export interface CollaborationEvent {
  eventId: string
  type: CollaborationEventType
  userId: number
  name: string
  projectId: number
  clientId: string | null
  timestamp: string
  payload: unknown
  message: string | null
}

export interface Collaborator {
  userId: number
  name: string
}

export type CollaborationStatus = 'connecting' | 'connected' | 'disconnected' | 'error'

export type WorkspaceRole = 'OWNER' | 'EDITOR' | 'VIEWER'

export interface WorkspaceMember {
  userId: number
  nombre: string
  email: string
  rol: WorkspaceRole
}

export interface AddWorkspaceMemberRequest {
  email: string
  rol: Exclude<WorkspaceRole, 'OWNER'>
}
