import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import test from 'node:test'
import {
  AgentConversationStore,
  formatMessageAuthorAndTime,
  hasExplicitAgentMention,
  insertAgentMention,
  proposalStateBadge,
} from '../src/features/agent/services/agentConversationStore.ts'
import { isConversationCollaborationEvent } from '../src/features/collaboration/types/collaboration.types.ts'

function makeConversation(id, overrides = {}) {
  return {
    id,
    projectId: overrides.projectId ?? 101,
    titulo: overrides.titulo ?? `Conversación ${id}`,
    estado: overrides.estado ?? 'ACTIVE',
    creadoPorId: overrides.creadoPorId ?? 1,
    creadoPorNombre: overrides.creadoPorNombre ?? 'Gabriel',
    fechaCreacion: overrides.fechaCreacion ?? '2026-10-28T10:00:00',
    fechaActualizacion: overrides.fechaActualizacion ?? '2026-10-28T10:00:00',
  }
}

function makeMessage(id, conversationId, overrides = {}) {
  return {
    id,
    conversationId,
    projectId: overrides.projectId ?? 101,
    autorTipo: overrides.autorTipo ?? 'USER',
    autorUsuarioId: overrides.autorUsuarioId ?? 1,
    autorNombre: overrides.autorNombre ?? 'Gabriel',
    solicitadoPorId: overrides.solicitadoPorId ?? null,
    solicitadoPorNombre: overrides.solicitadoPorNombre ?? null,
    contenido: overrides.contenido ?? `Mensaje ${id}`,
    estado: overrides.estado ?? 'SENT',
    clientMessageId: overrides.clientMessageId ?? `cid-${id}`,
    mensajeOrigenId: overrides.mensajeOrigenId ?? null,
    fechaCreacion: overrides.fechaCreacion ?? '2026-10-28T10:30:00',
    proposal: overrides.proposal ?? null,
  }
}

function makeProposal(id, conversationId, messageId, overrides = {}) {
  return {
    id,
    conversationId,
    messageId,
    projectId: overrides.projectId ?? 101,
    solicitadoPorId: overrides.solicitadoPorId ?? 1,
    solicitadoPorNombre: overrides.solicitadoPorNombre ?? 'Gabriel',
    estado: overrides.estado ?? 'PENDING',
    baseRevision: overrides.baseRevision ?? 3,
    aplicadaRevision: overrides.aplicadaRevision ?? null,
    operations: overrides.operations ?? [
      {
        type: 'ADD_RELATIONSHIP',
        relationship: {
          sourceEntity: 'Venta',
          targetEntity: 'Producto',
          sourceCardinality: 'ONE_ONE',
          targetCardinality: 'ZERO_MANY',
          name: 'items',
        },
      },
    ],
    affectedElementIds: overrides.affectedElementIds ?? ['node-venta', 'node-producto'],
    resumen: overrides.resumen ?? 'Relacionar Venta con Producto',
    decididoPorId: overrides.decididoPorId ?? null,
    decididoPorNombre: overrides.decididoPorNombre ?? null,
    fechaDecision: overrides.fechaDecision ?? null,
    resultadoAplicacion: overrides.resultadoAplicacion ?? null,
    fechaCreacion: overrides.fechaCreacion ?? '2026-10-28T10:33:00',
  }
}

function makeStompEvent(type, payload, overrides = {}) {
  return {
    eventId: overrides.eventId ?? crypto.randomUUID(),
    type,
    userId: overrides.userId ?? 1,
    name: overrides.name ?? 'Gabriel',
    projectId: overrides.projectId ?? 101,
    clientId: overrides.clientId ?? 'tab-remote',
    timestamp: '2026-10-28T10:33:00Z',
    payload,
    message: null,
  }
}

test('gestiona múltiples conversaciones por proyecto, cambio de hilo, renombrado, archivado sin pérdida y cierre/apertura del panel', () => {
  const store = new AgentConversationStore(101)
  const ventas = makeConversation(10, {
    titulo: 'Diseño del sistema de ventas',
    fechaActualizacion: '2026-10-28T10:10:00',
  })
  const relaciones = makeConversation(11, {
    titulo: 'Revisión de relaciones UML',
    creadoPorId: 2,
    creadoPorNombre: 'Carlos',
    fechaActualizacion: '2026-10-28T10:20:00',
  })

  store.setConversationsList({
    projectId: 101,
    canEdit: true,
    conversations: [ventas, relaciones],
  })

  // Auto-selecciona la conversación activa más reciente
  assert.equal(store.getSnapshot().conversations.length, 2)
  assert.equal(store.getSnapshot().activeConversationId, 11)

  // Aislamiento por proyecto: eventos o listas de otro proyecto se ignoran
  store.setConversationsList({
    projectId: 999,
    canEdit: false,
    conversations: [makeConversation(88, { projectId: 999 })],
  })
  assert.equal(store.getSnapshot().activeConversationId, 11)
  assert.equal(store.getSnapshot().canEdit, true)

  // Escribir borrador y cambiar entre conversaciones conserva estado al cerrar y reabrir el panel
  store.setPanelOpen(true)
  store.setDraft(11, 'Revisando multiplicidades…')
  store.replaceInitialMessagesPage({
    conversationId: 11,
    hasMore: false,
    nextBeforeId: null,
    messages: [makeMessage(100, 11, { autorNombre: 'Carlos', contenido: 'Hola equipo' })],
  })

  store.selectConversation(10)
  store.setDraft(10, '@LogicDraft analiza Venta')
  store.setPanelOpen(false)
  store.setPanelOpen(true)

  assert.equal(store.getSnapshot().activeConversationId, 10)
  assert.equal(store.getSnapshot().activeDraft, '@LogicDraft analiza Venta')

  store.selectConversation(11)
  assert.equal(store.getSnapshot().activeDraft, 'Revisando multiplicidades…')
  assert.equal(store.getSnapshot().activeTimeline?.messages.length, 1)

  // Renombrar y archivar vía evento STOMP sin perder su historial de mensajes
  store.applyCollaborationEvent(
    makeStompEvent('CONVERSATION_UPDATED', {
      conversation: {
        ...relaciones,
        titulo: 'Revisión UML completada',
        estado: 'ARCHIVED',
      },
    }),
    'tab-local',
  )

  // Por defecto la vista activa oculta archivadas y salta a la conversación activa restante (10)
  assert.equal(store.getSnapshot().conversations.length, 1)
  assert.equal(store.getSnapshot().activeConversationId, 10)

  // Al activar "Ver archivadas", reaparece con su nuevo título y su historial intacto
  store.setIncludeArchived(true)
  assert.equal(store.getSnapshot().conversations.length, 2)
  store.selectConversation(11)
  assert.equal(store.getSnapshot().activeConversation?.titulo, 'Revisión UML completada')
  assert.equal(store.getSnapshot().activeTimeline?.messages[0].contenido, 'Hola equipo')
})

test('atribuye siempre el nombre real del colaborador y detecta menciones explícitas a @LogicDraft', () => {
  const msgGabriel = makeMessage(1, 10, {
    autorTipo: 'USER',
    autorUsuarioId: 1,
    autorNombre: 'Gabriel',
    contenido: 'He creado la entidad Producto.',
    fechaCreacion: '2026-10-28T10:30:12',
  })
  const msgCarlos = makeMessage(2, 10, {
    autorTipo: 'USER',
    autorUsuarioId: 2,
    autorNombre: 'Carlos',
    contenido: 'Voy a revisar sus atributos.',
    fechaCreacion: '2026-10-28T10:31:45',
  })
  const msgAgent = makeMessage(3, 10, {
    autorTipo: 'AGENT',
    autorUsuarioId: null,
    autorNombre: 'LogicDraft IA',
    solicitadoPorNombre: 'Gabriel',
    contenido: 'Recomiendo relacionar Venta con Producto.',
    fechaCreacion: '2026-10-28T10:33:04',
  })

  const fmtG = formatMessageAuthorAndTime(msgGabriel)
  const fmtC = formatMessageAuthorAndTime(msgCarlos)
  const fmtA = formatMessageAuthorAndTime(msgAgent)

  assert.equal(fmtG.authorLabel, 'Gabriel')
  assert.notEqual(fmtG.authorLabel, 'Tú')
  assert.equal(fmtG.timeLabel, '10:30')

  assert.equal(fmtC.authorLabel, 'Carlos')
  assert.equal(fmtC.timeLabel, '10:31')

  assert.equal(fmtA.authorLabel, 'LogicDraft IA')
  assert.equal(fmtA.timeLabel, '10:33')

  // Mensajes normales NO mencionan al agente; solo @LogicDraft explícito activa la detección
  assert.equal(hasExplicitAgentMention('He creado la entidad Producto.'), false)
  assert.equal(hasExplicitAgentMention('correo@logicdraft.com no es mención'), false)
  assert.equal(hasExplicitAgentMention('@LogicDraft, ¿falta alguna relación?'), true)
  assert.equal(hasExplicitAgentMention('Hola @logicdraft revisa Venta'), true)
  assert.equal(insertAgentMention('agrega atributo total a Venta'), '@LogicDraft agrega atributo total a Venta')
  assert.equal(insertAgentMention('@LogicDraft ya presente'), '@LogicDraft ya presente')
})

test('soporta paginación de más de 10 mensajes, deduplicación optimista/STOMP/REST en reconexión y mensajes simultáneos', () => {
  const store = new AgentConversationStore(101)
  store.setConversationsList({
    projectId: 101,
    canEdit: true,
    conversations: [makeConversation(10)],
  })
  store.setPanelOpen(true)

  // Página más reciente: mensajes 16..25 (10 mensajes, hasMore=true, nextBeforeId=16)
  const newestTen = Array.from({ length: 10 }, (_, idx) => {
    const id = 16 + idx
    return makeMessage(id, 10, {
      autorUsuarioId: id % 2 === 0 ? 1 : 2,
      autorNombre: id % 2 === 0 ? 'Gabriel' : 'Carlos',
      contenido: `Mensaje #${id}`,
    })
  })
  store.replaceInitialMessagesPage({
    conversationId: 10,
    hasMore: true,
    nextBeforeId: 16,
    messages: newestTen,
  })
  assert.equal(store.getSnapshot().activeTimeline?.messages.length, 10)
  assert.equal(store.getSnapshot().activeTimeline?.hasMore, true)

  // Paginación hacia atrás: mensajes 1..15 -> total 25 mensajes (> límite antiguo de 10)
  const olderFifteen = Array.from({ length: 15 }, (_, idx) => {
    const id = 1 + idx
    return makeMessage(id, 10, { contenido: `Mensaje #${id}` })
  })
  store.prependOlderMessagesPage({
    conversationId: 10,
    hasMore: false,
    nextBeforeId: null,
    messages: olderFifteen,
  })
  assert.equal(store.getSnapshot().activeTimeline?.messages.length, 25)
  assert.equal(store.getSnapshot().activeTimeline?.messages[0].id, 1)
  assert.equal(store.getSnapshot().activeTimeline?.messages[24].id, 25)
  assert.equal(store.getSnapshot().activeTimeline?.hasMore, false)

  // Envío optimista de Gabriel + mensaje simultáneo de Carlos por STOMP + reconexión REST sin duplicar
  store.addOptimisticMessage({
    clientMessageId: 'opt-gabriel-26',
    conversationId: 10,
    projectId: 101,
    autorNombre: 'Enviando…',
    contenido: 'Mensaje #26 de Gabriel',
    invokeAgent: false,
    createdAt: '2026-10-28T10:40:00Z',
    status: 'sending',
    errorMessage: null,
  })
  assert.equal(store.getSnapshot().activeTimeline?.optimisticMessages.length, 1)

  const confirmed26 = makeMessage(26, 10, {
    autorUsuarioId: 1,
    autorNombre: 'Gabriel',
    contenido: 'Mensaje #26 de Gabriel',
    clientMessageId: 'opt-gabriel-26',
  })
  const confirmed27 = makeMessage(27, 10, {
    autorUsuarioId: 2,
    autorNombre: 'Carlos',
    contenido: 'Mensaje #27 simultáneo de Carlos',
    clientMessageId: 'opt-carlos-27',
  })

  // Llega primero por STOMP (dos veces para simular reintento) -> retira burbuja optimista y no duplica
  store.applyCollaborationEvent(
    makeStompEvent('CONVERSATION_MESSAGE_CREATED', { conversationId: 10, message: confirmed26 }, { clientId: 'tab-g' }),
    'tab-g',
  )
  store.applyCollaborationEvent(
    makeStompEvent('CONVERSATION_MESSAGE_CREATED', { conversationId: 10, message: confirmed26 }, { clientId: 'tab-g' }),
    'tab-g',
  )
  store.applyCollaborationEvent(
    makeStompEvent('CONVERSATION_MESSAGE_CREATED', { conversationId: 10, message: confirmed27 }, { clientId: 'tab-c' }),
    'tab-g',
  )

  // Luego llega respuesta REST y además reconexión REST devolviendo los últimos mensajes
  store.reconcileSendResult({ userMessage: confirmed26, agentMessage: null, agentError: null })
  store.replaceInitialMessagesPage({
    conversationId: 10,
    hasMore: true,
    nextBeforeId: 18,
    messages: [...newestTen.slice(2), confirmed26, confirmed27],
  })

  const finalMessages = store.getSnapshot().activeTimeline?.messages ?? []
  assert.equal(store.getSnapshot().activeTimeline?.optimisticMessages.length, 0)
  assert.equal(finalMessages.length, 27)
  assert.equal(finalMessages[25].autorNombre, 'Gabriel')
  assert.equal(finalMessages[26].autorNombre, 'Carlos')
})

test('sincroniza indicador "LogicDraft está respondiendo", ciclo de vida de propuestas compartidas y modo VIEWER solo lectura', () => {
  const store = new AgentConversationStore(101)
  store.setConversationsList({
    projectId: 101,
    canEdit: false, // VIEWER
    conversations: [makeConversation(10)],
  })
  assert.equal(store.getSnapshot().canEdit, false)

  // Indicador en vivo de IA respondiendo
  store.applyCollaborationEvent(
    makeStompEvent('CONVERSATION_AI_STATUS', {
      conversationId: 10,
      active: true,
      requestedByUserId: 1,
      requestedByName: 'Gabriel',
    }),
    'tab-viewer',
  )
  assert.equal(store.getSnapshot().activeAiStatus?.active, true)
  assert.equal(store.getSnapshot().activeAiStatus?.requestedByName, 'Gabriel')

  // Llega respuesta de LogicDraft IA con propuesta PENDING
  const pendingProposal = makeProposal(501, 10, 300, { estado: 'PENDING', baseRevision: 4 })
  const aiMsg = makeMessage(300, 10, {
    autorTipo: 'AGENT',
    autorUsuarioId: null,
    autorNombre: 'LogicDraft IA',
    solicitadoPorNombre: 'Gabriel',
    contenido: 'Propongo crear la relación Venta -> Producto.',
    proposal: pendingProposal,
  })
  store.applyCollaborationEvent(
    makeStompEvent('CONVERSATION_AI_STATUS', {
      conversationId: 10,
      active: false,
      requestedByUserId: 1,
      requestedByName: 'Gabriel',
    }),
    'tab-viewer',
  )
  store.applyCollaborationEvent(
    makeStompEvent('CONVERSATION_MESSAGE_CREATED', { conversationId: 10, message: aiMsg }),
    'tab-viewer',
  )

  assert.equal(store.getSnapshot().activeAiStatus?.active, false)
  assert.equal(store.getSnapshot().totalUnread, 1) // Panel cerrado -> cuenta como no leído
  store.setPanelOpen(true)
  assert.equal(store.getSnapshot().totalUnread, 0)

  const currentProp = store.getSnapshot().activeTimeline?.messages[0].proposal
  assert.ok(currentProp)
  assert.equal(proposalStateBadge(currentProp).label, 'Pendiente de revisión')

  // Carlos (EDITOR) acepta la propuesta -> STOMP difunde ACCEPTED a todos los clientes conectados
  const acceptedProposal = {
    ...pendingProposal,
    estado: 'ACCEPTED',
    aplicadaRevision: 5,
    decididoPorId: 2,
    decididoPorNombre: 'Carlos',
    resultadoAplicacion: 'Aplicada en revisión #5',
  }
  store.applyCollaborationEvent(
    makeStompEvent('CONVERSATION_PROPOSAL_UPDATED', {
      conversationId: 10,
      messageId: 300,
      proposal: acceptedProposal,
    }),
    'tab-viewer',
  )
  const updatedProp = store.getSnapshot().activeTimeline?.messages[0].proposal
  assert.equal(updatedProp?.estado, 'ACCEPTED')
  assert.equal(proposalStateBadge(updatedProp).label, 'Aceptada por Carlos')

  // También verifica etiquetas para REJECTED y STALE
  assert.equal(
    proposalStateBadge({ estado: 'REJECTED', decididoPorNombre: 'Gabriel' }).label,
    'Rechazada por Gabriel',
  )
  assert.equal(
    proposalStateBadge({ estado: 'STALE', decididoPorNombre: null }).tone,
    'stale',
  )
})

test('cableado STOMP y UI preservan cursores/bloqueos de 3.2A-3.2B e integran conversaciones compartidas', () => {
  for (const type of [
    'CONVERSATION_CREATED',
    'CONVERSATION_UPDATED',
    'CONVERSATION_MESSAGE_CREATED',
    'CONVERSATION_AI_STATUS',
    'CONVERSATION_PROPOSAL_UPDATED',
  ]) {
    assert.equal(isConversationCollaborationEvent(type), true, type)
  }
  assert.equal(isConversationCollaborationEvent('CURSOR_MOVED'), false)
  assert.equal(isConversationCollaborationEvent('ELEMENT_LOCKED'), false)
  assert.equal(isConversationCollaborationEvent('DIAGRAM_COMMITTED'), false)

  const collabService = readFileSync(
    new URL('../src/features/collaboration/services/collaborationService.ts', import.meta.url),
    'utf8',
  )
  const useCollab = readFileSync(
    new URL('../src/features/collaboration/hooks/useProjectCollaboration.ts', import.meta.url),
    'utf8',
  )
  const agentPanel = readFileSync(
    new URL('../src/features/agent/components/AgentPanel.tsx', import.meta.url),
    'utf8',
  )
  const editorPage = readFileSync(
    new URL('../src/features/diagram/pages/DiagramEditorPage.tsx', import.meta.url),
    'utf8',
  )

  assert.match(collabService, /CONVERSATION_MESSAGE_CREATED/)
  assert.match(collabService, /CONVERSATION_PROPOSAL_UPDATED/)
  assert.match(useCollab, /isConversationCollaborationEvent\(event\.type\)/)
  assert.match(agentPanel, /LogicDraft está respondiendo/)
  assert.match(agentPanel, /Aceptar propuesta/)
  assert.match(agentPanel, /Rechazar/)
  assert.match(agentPanel, /Cargar mensajes anteriores/)
  assert.match(agentPanel, /@LogicDraft/)
  assert.match(editorPage, /onLockConflict/)
  assert.match(editorPage, /onProposalApplied/)
})
