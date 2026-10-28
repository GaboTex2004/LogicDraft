import type { DiagramAiOperation } from '../../diagram/types/diagramAi.types.ts'
import type { AgentEvent } from './agent.types.ts'

export type ConversationState = 'ACTIVE' | 'ARCHIVED'
export type MessageAuthorType = 'USER' | 'AGENT' | 'SYSTEM'
export type ConversationMessageStatus = 'SENT' | 'PROCESSING' | 'FAILED'
export type AgentProposalState = 'PENDING' | 'ACCEPTED' | 'REJECTED' | 'STALE' | 'INVALID'

export interface ConversationSummary {
  id: number
  projectId: number
  titulo: string
  estado: ConversationState
  creadoPorId: number
  creadoPorNombre: string
  fechaCreacion: string
  fechaActualizacion: string
}

export interface ProjectConversationsListResponse {
  projectId: number
  canEdit: boolean
  conversations: ConversationSummary[]
}

export interface SharedProposal {
  id: number
  conversationId: number
  messageId: number
  projectId: number
  solicitadoPorId: number
  solicitadoPorNombre: string
  estado: AgentProposalState
  baseRevision: number
  aplicadaRevision: number | null
  operations: DiagramAiOperation[]
  affectedElementIds: string[]
  resumen: string | null
  decididoPorId: number | null
  decididoPorNombre: string | null
  fechaDecision: string | null
  resultadoAplicacion: string | null
  fechaCreacion: string
}

export interface ConversationMessage {
  id: number
  conversationId: number
  projectId: number
  autorTipo: MessageAuthorType
  autorUsuarioId: number | null
  autorNombre: string
  solicitadoPorId: number | null
  solicitadoPorNombre: string | null
  contenido: string
  estado: ConversationMessageStatus
  clientMessageId: string | null
  mensajeOrigenId: number | null
  fechaCreacion: string
  proposal: SharedProposal | null
}

export interface ConversationMessagesPageResponse {
  conversationId: number
  hasMore: boolean
  nextBeforeId: number | null
  messages: ConversationMessage[]
}

export interface SendMessageResultResponse {
  userMessage: ConversationMessage
  agentMessage: ConversationMessage | null
  agentError: string | null
}

export interface SendConversationMessagePayload {
  contenido: string
  clientMessageId: string
  invokeAgent: boolean
  selectedNodeId: string | null
  selectedEdgeId: string | null
  recentEvents: AgentEvent[]
  clientId: string
}

export interface AiTypingStatus {
  conversationId: number
  active: boolean
  requestedByUserId: number | null
  requestedByName: string | null
  error: string | null
}

export interface PendingOptimisticMessage {
  clientMessageId: string
  conversationId: number
  projectId: number
  autorNombre: string
  contenido: string
  invokeAgent: boolean
  createdAt: string
  status: 'sending' | 'failed'
  errorMessage: string | null
}

export interface ConversationTimelineSnapshot {
  conversationId: number
  messages: ConversationMessage[]
  optimisticMessages: PendingOptimisticMessage[]
  hasMore: boolean
  nextBeforeId: number | null
  loadedInitial: boolean
}

export interface AgentConversationsSnapshot {
  projectId: number
  canEdit: boolean
  loadedList: boolean
  includeArchived: boolean
  panelOpen: boolean
  conversations: ConversationSummary[]
  activeConversationId: number | null
  activeConversation: ConversationSummary | null
  activeTimeline: ConversationTimelineSnapshot | null
  activeAiStatus: AiTypingStatus | null
  activeDraft: string
  totalUnread: number
  unreadByConversation: Readonly<Record<number, number>>
}
