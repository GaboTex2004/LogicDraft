export type ConversationCollaborationEventType =
  | 'CONVERSATION_CREATED'
  | 'CONVERSATION_UPDATED'
  | 'CONVERSATION_MESSAGE_CREATED'
  | 'CONVERSATION_AI_STATUS'
  | 'CONVERSATION_PROPOSAL_UPDATED'

export type CollaborationEventType =
  | 'USER_JOINED'
  | 'USER_LEFT'
  | 'PING'
  | 'CURSOR_MOVED'
  | 'CURSOR_LEFT'
  | 'LOCK_SESSION_BIND'
  | 'ELEMENT_LOCKED'
  | 'ELEMENT_UNLOCKED'
  | 'NODE_CREATED'
  | 'NODE_MOVED'
  | 'NODE_UPDATED'
  | 'NODE_DELETED'
  | 'EDGE_CREATED'
  | 'EDGE_UPDATED'
  | 'EDGE_DELETED'
  | 'DIAGRAM_BATCH_APPLIED'
  | 'DIAGRAM_SAVED'
  | 'DIAGRAM_COMMITTED'
  | ConversationCollaborationEventType

export function isConversationCollaborationEvent(
  type: CollaborationEventType,
): type is ConversationCollaborationEventType {
  return (
    type === 'CONVERSATION_CREATED' ||
    type === 'CONVERSATION_UPDATED' ||
    type === 'CONVERSATION_MESSAGE_CREATED' ||
    type === 'CONVERSATION_AI_STATUS' ||
    type === 'CONVERSATION_PROPOSAL_UPDATED'
  )
}

export type LiveDiagramEventType =
  | 'NODE_CREATED'
  | 'NODE_MOVED'
  | 'NODE_UPDATED'
  | 'NODE_DELETED'
  | 'EDGE_CREATED'
  | 'EDGE_UPDATED'
  | 'EDGE_DELETED'
  | 'DIAGRAM_BATCH_APPLIED'

export type EphemeralCollaborationEventType =
  | LiveDiagramEventType
  | 'CURSOR_MOVED'
  | 'CURSOR_LEFT'
  | 'LOCK_SESSION_BIND'

export type DiagramCollaborationEventType = Exclude<
  CollaborationEventType,
  | 'USER_JOINED'
  | 'USER_LEFT'
  | 'PING'
  | 'CURSOR_MOVED'
  | 'CURSOR_LEFT'
  | 'LOCK_SESSION_BIND'
  | 'ELEMENT_LOCKED'
  | 'ELEMENT_UNLOCKED'
  | ConversationCollaborationEventType
>

export interface ElementLock {
  projectId: number
  elementId: string
  userId: number
  userName: string
  clientId: string
  sessionId?: string
  acquiredAt: string
  expiresAt: string
}

export interface ResolvedElementLock extends ElementLock {
  label: string
  color: string
  ownedBySelf: boolean
}

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
