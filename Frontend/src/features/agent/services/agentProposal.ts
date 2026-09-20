import type { DiagramEdge, EntityFlowNode } from '../../diagram/types/diagram.types.ts'
import { prepareDiagramAiProposal } from '../../diagram/services/applyDiagramOperations.ts'
import type { AgentAskResponse } from '../types/agent.types.ts'
import { classifyAgentIntent, containsTechnicalOperation } from './agentIntent.ts'

export function prepareAgentResponse(
  message: string,
  response: AgentAskResponse,
  nodes: EntityFlowNode[],
  edges: DiagramEdge[],
) {
  const intent = classifyAgentIntent(message)
  if (intent !== 'MODIFICATION') {
    return {
      response: {
        answer: containsTechnicalOperation(response.answer)
          ? 'Puedo darte sugerencias en lenguaje natural, pero necesito más contexto de los requisitos del proyecto.'
          : response.answer,
        operations: [],
      } satisfies AgentAskResponse,
      proposal: null,
    }
  }
  if (!response.operations.length) return { response, proposal: null }
  return { response, proposal: prepareDiagramAiProposal(response, nodes, edges) }
}

export function proposalAcceptanceError(
  proposalRevision: number,
  currentRevision: number,
  canEdit: boolean,
): string | null {
  if (!canEdit) return 'No tienes permisos para modificar este proyecto.'
  if (proposalRevision !== currentRevision) {
    return 'El diagrama cambio desde que se genero la propuesta. Solicitala nuevamente.'
  }
  return null
}
