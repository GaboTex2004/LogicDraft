export const AGENT_EVENT_TYPES = [
  'PROJECT_OPENED', 'DIAGRAM_OPENED', 'NODE_CREATED', 'NODE_SELECTED',
  'NODE_UPDATED', 'NODE_DELETED', 'EDGE_CREATED', 'EDGE_UPDATED',
  'EDGE_DELETED', 'DIAGRAM_SAVED', 'SAVE_FAILED', 'AI_REQUESTED',
] as const

export type AgentEventType = (typeof AGENT_EVENT_TYPES)[number]
export interface AgentEvent {
  type: AgentEventType
  nodeId: string | null
  edgeId: string | null
  timestamp: string
}
export interface AgentAskRequest {
  message: string
  selectedNodeId: string | null
  selectedEdgeId: string | null
  recentEvents: AgentEvent[]
}
export interface AgentAskResponse { answer: string }
