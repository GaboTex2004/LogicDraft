import { AGENT_EVENT_TYPES, type AgentEvent, type AgentEventType } from '../types/agent.types.ts'

export const AGENT_EVENT_LIMIT = 25

export class AgentSession {
  readonly projectId: number
  diagramId: number | null
  private events: AgentEvent[] = []

  constructor(projectId: number, diagramId: number | null, now: () => string = () => new Date().toISOString()) {
    this.projectId = projectId
    this.diagramId = diagramId
    this.record('PROJECT_OPENED', {}, now)
    this.record('DIAGRAM_OPENED', {}, now)
  }

  setDiagramId(diagramId: number) { this.diagramId = diagramId }

  record(
    type: AgentEventType,
    refs: { nodeId?: string | null; edgeId?: string | null } = {},
    now: () => string = () => new Date().toISOString(),
  ): boolean {
    if (!(AGENT_EVENT_TYPES as readonly string[]).includes(type)) return false
    this.events.push({
      type,
      nodeId: refs.nodeId ?? null,
      edgeId: refs.edgeId ?? null,
      timestamp: now(),
    })
    if (this.events.length > AGENT_EVENT_LIMIT) this.events.splice(0, this.events.length - AGENT_EVENT_LIMIT)
    return true
  }

  snapshot(): AgentEvent[] { return this.events.map(event => ({ ...event })) }
}
