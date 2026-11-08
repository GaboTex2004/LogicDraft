import { prepareAgentResponse } from '../../agent/services/agentProposal.ts'
import type { AgentAskResponse } from '../../agent/types/agent.types'
import type { DiagramEdge, EntityFlowNode } from '../types/diagram.types'

export function prepareEditorBarResponse(prompt: string, value: unknown, nodes: EntityFlowNode[], edges: DiagramEdge[]) {
  if (!value || typeof value !== 'object' || !('answer' in value) || typeof value.answer !== 'string' || !value.answer.trim()
    || !('operations' in value) || !Array.isArray(value.operations)) {
    throw new Error('El agente devolvió una respuesta incompleta. No se aplicaron cambios.')
  }
  return prepareAgentResponse(prompt, value as AgentAskResponse, nodes, edges)
}
