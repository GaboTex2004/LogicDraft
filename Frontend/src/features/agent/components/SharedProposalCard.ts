import { createElement as h } from 'react'
import { diagramOperationLabel } from '../../diagram/services/diagramActionPresentation.ts'
import { proposalStateBadge } from '../services/agentConversationStore.ts'
import type { SharedProposal } from '../types/agentConversation.types.ts'

/** Live proposal events can arrive without a replacement message body. */
export function proposalMessageText(proposal: SharedProposal): string {
  switch (proposal.estado) {
    case 'PENDING': return `Propuesta preparada con ${proposal.operations.length} operaciones. Revisa los cambios y acepta o rechaza; todavía no se aplicaron.`
    case 'INVALID': return `No se puede aplicar la propuesta. ${proposal.resultadoAplicacion ?? ''}`
    case 'STALE': return 'La propuesta está obsoleta: el diagrama cambió. Solicita una nueva propuesta.'
    case 'ACCEPTED': return `Propuesta aplicada en la revisión ${proposal.aplicadaRevision}.`
    case 'REJECTED': return 'Propuesta rechazada; no se aplicaron sus cambios.'
  }
}

/** Render exclusively from the server proposal, never from the model's prose. */
export function SharedProposalCard({ proposal, canEdit, decidingProposalId, impact, onAcceptProposal, onRejectProposal }: {
  proposal: SharedProposal; canEdit: boolean; decidingProposalId: number | null; impact: string[]
  onAcceptProposal: (proposal: SharedProposal) => Promise<void>
  onRejectProposal: (proposal: SharedProposal) => Promise<void>
}) {
  const badge = proposalStateBadge(proposal)
  const classes = proposal.operations.filter(op => op.type === 'ADD_ENTITY')
  const attributes = classes.reduce((count, op) => count + op.entity.attributes.length, 0)
  const relationships = proposal.operations.filter(op => op.type === 'ADD_RELATIONSHIP').length
  return h('div', { className: `agent-proposal-card tone-${badge.tone}` },
    h('div', { className: 'agent-proposal-header' },
      h('span', null, `Propuesta UML · rev. base #${proposal.baseRevision}`),
      h('span', { className: `agent-proposal-badge tone-${badge.tone}` }, badge.label)),
    classes.length > 0 && h('small', null, `${classes.length} clases · ${attributes} atributos · ${relationships} relaciones nuevas`),
    h('ul', { className: 'agent-proposal-ops' }, proposal.operations.map((op, index) =>
      h('li', { key: index }, diagramOperationLabel(op), op.type === 'ADD_ENTITY' && h('ul', null,
        op.entity.attributes.map(attr => h('li', { key: attr.name }, `${attr.name}: ${attr.dataType}${attr.primaryKey ? ' (PK)' : ''}`)))))),
    impact.length > 0 && h('div', { className: 'diagram-ai-impact agent-proposal-impact' },
      h('strong', null, 'Impacto adicional:'), h('ul', null, impact.map(item => h('li', { key: item }, item)))),
    proposal.resultadoAplicacion && h('small', { className: 'agent-proposal-result' }, proposal.resultadoAplicacion),
    proposal.estado === 'ACCEPTED' && h('small', null, `Aplicada en revisión #${proposal.aplicadaRevision}`),
    proposal.estado === 'PENDING' && h('div', { className: 'agent-proposal-actions' }, canEdit ? [
      h('button', { key: 'accept', type: 'button', className: 'accept', disabled: decidingProposalId !== null,
        onClick: () => void onAcceptProposal(proposal) }, decidingProposalId === proposal.id ? 'Aplicando…' : 'Aceptar propuesta'),
      h('button', { key: 'reject', type: 'button', className: 'reject', disabled: decidingProposalId !== null,
        onClick: () => void onRejectProposal(proposal) }, 'Rechazar'),
    ] : h('small', { className: 'agent-muted' }, 'Solo OWNER o EDITOR en una conversación activa pueden decidir esta propuesta.')))
}
