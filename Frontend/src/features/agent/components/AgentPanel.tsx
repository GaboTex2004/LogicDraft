import {
  useCallback,
  useEffect,
  useMemo,
  useRef,
  useState,
  useSyncExternalStore,
  type FormEvent,
  type KeyboardEvent,
} from 'react'
import type { CollaborationStatus } from '../../collaboration/types/collaboration.types'
import type { SaveStatus } from '../../diagram/components/DiagramToolbar'
import {
  destructivePlanImpact,
  diagramOperationLabel,
} from '../../diagram/services/diagramActionPresentation'
import type { DiagramEdge, EntityFlowNode } from '../../diagram/types/diagram.types'
import {
  aceptarPropuestaConversacion,
  actualizarConversacionProyecto,
  crearConversacionProyecto,
  enviarMensajeConversacion,
  extractConversationApiError,
  listarConversacionesProyecto,
  listarMensajesConversacion,
  rechazarPropuestaConversacion,
} from '../api/agentConversationsApi'
import {
  AgentConversationStore,
  formatMessageAuthorAndTime,
  formatServerTime,
  hasExplicitAgentMention,
  insertAgentMention,
  messageAuthorColor,
  proposalStateBadge,
} from '../services/agentConversationStore'
import type {
  AgentAskResponse,
  AgentConversationMessage,
  AgentEvent,
} from '../types/agent.types'
import type {
  ConversationMessage,
  PendingOptimisticMessage,
  SharedProposal,
} from '../types/agentConversation.types'

const PAGE_LIMIT = 40

export interface AgentPanelProps {
  projectId: number
  store: AgentConversationStore
  clientId: string
  collaborationStatus: CollaborationStatus
  nodes: readonly EntityFlowNode[]
  edges: readonly DiagramEdge[]
  selectedNodeId: string | null
  selectedEdgeId: string | null
  getRecentEvents: () => AgentEvent[]
  saveStatus: SaveStatus
  disabledReason?: string
  onBeforeAcceptProposal?: () => Promise<boolean>
  onProposalApplied?: (proposal: SharedProposal) => void
  onLockConflict?: (message: string) => void
  onLegacyAsk?: (
    message: string,
    conversation: AgentConversationMessage[],
  ) => Promise<AgentAskResponse>
}

function renderHighlightedContent(text: string) {
  const parts = text.split(/(@logicdraft)/gi)
  return parts.map((segment, index) =>
    /^@logicdraft$/i.test(segment) ? (
      <mark key={index} className="agent-mention-highlight">
        {segment}
      </mark>
    ) : (
      <span key={index}>{segment}</span>
    ),
  )
}

export function AgentPanel({
  projectId,
  store,
  clientId,
  collaborationStatus,
  nodes,
  edges,
  selectedNodeId,
  selectedEdgeId,
  getRecentEvents,
  saveStatus,
  disabledReason,
  onBeforeAcceptProposal,
  onProposalApplied,
  onLockConflict,
}: AgentPanelProps) {
  const snapshot = useSyncExternalStore(
    store.subscribe,
    store.getSnapshot,
    store.getSnapshot,
  )

  const [sidebarDrawerOpen, setSidebarDrawerOpen] = useState(true)
  const [creatingNew, setCreatingNew] = useState(false)
  const [newTitle, setNewTitle] = useState('')
  const [creatingBusy, setCreatingBusy] = useState(false)

  const [renamingId, setRenamingId] = useState<number | null>(null)
  const [renameTitle, setRenameTitle] = useState('')
  const [conversationActionBusy, setConversationActionBusy] = useState(false)

  const [loadingOlder, setLoadingOlder] = useState(false)
  const [sendingMessage, setSendingMessage] = useState(false)
  const [decidingProposalId, setDecidingProposalId] = useState<number | null>(null)
  const [actionError, setActionError] = useState('')
  const [actionInfo, setActionInfo] = useState('')

  const messagesContainerRef = useRef<HTMLDivElement | null>(null)
  const textareaRef = useRef<HTMLTextAreaElement | null>(null)
  const lastTailMessageKeyRef = useRef<string>('')

  const handleUnauthorizedIfNeeded = useCallback((unauthorized: boolean) => {
    if (unauthorized) {
      localStorage.removeItem('token')
      window.location.assign('/login')
    }
  }, [])

  const loadLatestMessages = useCallback(
    async (conversationId: number) => {
      try {
        const page = await listarMensajesConversacion(projectId, conversationId, {
          limit: PAGE_LIMIT,
        })
        store.replaceInitialMessagesPage(page)
      } catch (error: unknown) {
        const parsed = extractConversationApiError(
          error,
          'No se pudo cargar el historial de mensajes.',
        )
        handleUnauthorizedIfNeeded(parsed.unauthorized)
        setActionError(parsed.message)
      }
    },
    [handleUnauthorizedIfNeeded, projectId, store],
  )

  // Initial load of conversation list for this project
  useEffect(() => {
    let active = true
    listarConversacionesProyecto(projectId, snapshot.includeArchived)
      .then((list) => {
        if (active) store.setConversationsList(list)
      })
      .catch((error: unknown) => {
        if (!active) return
        const parsed = extractConversationApiError(
          error,
          'No se pudieron cargar las conversaciones del proyecto.',
        )
        handleUnauthorizedIfNeeded(parsed.unauthorized)
        setActionError(parsed.message)
      })
    return () => {
      active = false
    }
  }, [handleUnauthorizedIfNeeded, projectId, snapshot.includeArchived, store])

  // Load initial messages whenever a conversation is selected and not yet loaded
  const activeConversationId = snapshot.activeConversationId
  const activeLoadedInitial = snapshot.activeTimeline?.loadedInitial ?? false
  const loadingList = !snapshot.loadedList
  const loadingMessages = activeConversationId !== null && !activeLoadedInitial
  useEffect(() => {
    if (activeConversationId === null || activeLoadedInitial) return
    let active = true
    listarMensajesConversacion(projectId, activeConversationId, {
      limit: PAGE_LIMIT,
    })
      .then((page) => {
        if (active) store.replaceInitialMessagesPage(page)
      })
      .catch((error: unknown) => {
        if (!active) return
        const parsed = extractConversationApiError(
          error,
          'No se pudo cargar el historial de mensajes.',
        )
        handleUnauthorizedIfNeeded(parsed.unauthorized)
        setActionError(parsed.message)
      })
    return () => {
      active = false
    }
  }, [activeConversationId, activeLoadedInitial, handleUnauthorizedIfNeeded, projectId, store])

  // Auto-scroll to bottom when new tail messages or AI typing indicator arrives
  useEffect(() => {
    if (!snapshot.panelOpen) return
    const timeline = snapshot.activeTimeline
    const lastConfirmedId = timeline?.messages.at(-1)?.id ?? 0
    const lastOptKey = timeline?.optimisticMessages.at(-1)?.clientMessageId ?? ''
    const typingFlag = snapshot.activeAiStatus?.active ? 'typing' : 'idle'
    const tailKey = `${snapshot.activeConversationId ?? 'none'}:${lastConfirmedId}:${lastOptKey}:${typingFlag}`

    if (tailKey !== lastTailMessageKeyRef.current) {
      lastTailMessageKeyRef.current = tailKey
      const container = messagesContainerRef.current
      if (container) {
        requestAnimationFrame(() => {
          container.scrollTop = container.scrollHeight
        })
      }
    }
  }, [
    snapshot.activeAiStatus?.active,
    snapshot.activeConversationId,
    snapshot.activeTimeline,
    snapshot.panelOpen,
  ])

  const activeConversation = snapshot.activeConversation
  const activeTimeline = snapshot.activeTimeline
  const activeAiStatus = snapshot.activeAiStatus
  const draft = snapshot.activeDraft
  const mentionsAgent = useMemo(() => hasExplicitAgentMention(draft), [draft])

  async function handleCreateConversation(event: FormEvent) {
    event.preventDefault()
    const titulo = newTitle.trim()
    if (!titulo || creatingBusy || !snapshot.canEdit) return
    setCreatingBusy(true)
    setActionError('')
    setActionInfo('')
    try {
      const created = await crearConversacionProyecto(projectId, { titulo, clientId })
      store.upsertConversation(created, true)
      setNewTitle('')
      setCreatingNew(false)
      setSidebarDrawerOpen(false)
      await loadLatestMessages(created.id)
    } catch (error: unknown) {
      const parsed = extractConversationApiError(
        error,
        'No se pudo crear la conversación.',
      )
      handleUnauthorizedIfNeeded(parsed.unauthorized)
      setActionError(parsed.message)
    } finally {
      setCreatingBusy(false)
    }
  }

  async function handleRenameSubmit(event: FormEvent) {
    event.preventDefault()
    if (!activeConversation || renamingId !== activeConversation.id || conversationActionBusy) {
      return
    }
    const nextTitle = renameTitle.trim()
    if (!nextTitle || nextTitle === activeConversation.titulo) {
      setRenamingId(null)
      return
    }
    setConversationActionBusy(true)
    setActionError('')
    try {
      const updated = await actualizarConversacionProyecto(projectId, activeConversation.id, {
        titulo: nextTitle,
        clientId,
      })
      store.upsertConversation(updated, false)
      setRenamingId(null)
    } catch (error: unknown) {
      const parsed = extractConversationApiError(
        error,
        'No se pudo renombrar la conversación.',
      )
      handleUnauthorizedIfNeeded(parsed.unauthorized)
      setActionError(parsed.message)
    } finally {
      setConversationActionBusy(false)
    }
  }

  async function handleToggleArchive() {
    if (!activeConversation || conversationActionBusy || !snapshot.canEdit) return
    const nextState = activeConversation.estado === 'ACTIVE' ? 'ARCHIVED' : 'ACTIVE'
    setConversationActionBusy(true)
    setActionError('')
    setActionInfo('')
    try {
      const updated = await actualizarConversacionProyecto(projectId, activeConversation.id, {
        estado: nextState,
        clientId,
      })
      store.upsertConversation(updated, nextState === 'ACTIVE')
      setActionInfo(
        nextState === 'ARCHIVED'
          ? `Conversación "${updated.titulo}" archivada.`
          : `Conversación "${updated.titulo}" reactivada.`,
      )
    } catch (error: unknown) {
      const parsed = extractConversationApiError(
        error,
        'No se pudo actualizar el estado de la conversación.',
      )
      handleUnauthorizedIfNeeded(parsed.unauthorized)
      setActionError(parsed.message)
    } finally {
      setConversationActionBusy(false)
    }
  }

  async function handleLoadOlderMessages() {
    if (
      !activeConversation ||
      !activeTimeline?.hasMore ||
      !activeTimeline.nextBeforeId ||
      loadingOlder
    ) {
      return
    }
    const container = messagesContainerRef.current
    const prevScrollHeight = container?.scrollHeight ?? 0
    const prevScrollTop = container?.scrollTop ?? 0

    setLoadingOlder(true)
    setActionError('')
    try {
      const page = await listarMensajesConversacion(projectId, activeConversation.id, {
        beforeId: activeTimeline.nextBeforeId,
        limit: PAGE_LIMIT,
      })
      store.prependOlderMessagesPage(page)
      if (container) {
        requestAnimationFrame(() => {
          const delta = container.scrollHeight - prevScrollHeight
          container.scrollTop = prevScrollTop + Math.max(0, delta)
        })
      }
    } catch (error: unknown) {
      const parsed = extractConversationApiError(
        error,
        'No se pudieron cargar los mensajes anteriores.',
      )
      handleUnauthorizedIfNeeded(parsed.unauthorized)
      setActionError(parsed.message)
    } finally {
      setLoadingOlder(false)
    }
  }

  async function dispatchMessage(
    rawText: string,
    explicitInvokeAgent: boolean,
    reuseClientMessageId?: string,
  ) {
    if (!activeConversation || !snapshot.canEdit) return
    const contenido = rawText.trim()
    if (!contenido) return

    const willInvokeAi = explicitInvokeAgent || hasExplicitAgentMention(contenido)
    if (willInvokeAi && saveStatus === 'conflict') {
      setActionError(
        'Resuelve el conflicto del diagrama antes de solicitar una propuesta a LogicDraft IA.',
      )
      return
    }

    const targetConvId = activeConversation.id
    const clientMessageId = reuseClientMessageId ?? crypto.randomUUID()

    if (!reuseClientMessageId) {
      store.setDraft(targetConvId, '')
    }
    store.clearAiError(targetConvId)
    setActionError('')
    setActionInfo('')

    store.addOptimisticMessage({
      clientMessageId,
      conversationId: targetConvId,
      projectId,
      autorNombre: 'Enviando…',
      contenido,
      invokeAgent: explicitInvokeAgent,
      createdAt: new Date().toISOString(),
      status: 'sending',
      errorMessage: null,
    })

    setSendingMessage(true)
    try {
      const result = await enviarMensajeConversacion(projectId, targetConvId, {
        contenido,
        clientMessageId,
        invokeAgent: explicitInvokeAgent,
        selectedNodeId,
        selectedEdgeId,
        recentEvents: getRecentEvents(),
        clientId,
      })
      store.reconcileSendResult(result)
    } catch (error: unknown) {
      const parsed = extractConversationApiError(
        error,
        'No se pudo enviar el mensaje.',
      )
      handleUnauthorizedIfNeeded(parsed.unauthorized)
      // Refresh latest messages in case the user message was already committed in PostgreSQL before an AI timeout/502/503/504!
      if (willInvokeAi && (parsed.status === 502 || parsed.status === 503 || parsed.status === 504)) {
        await loadLatestMessages(targetConvId)
      }
      store.markOptimisticFailed(targetConvId, clientMessageId, parsed.message)
      setActionError(parsed.message)
    } finally {
      setSendingMessage(false)
    }
  }

  function handleComposerSubmit(event: FormEvent) {
    event.preventDefault()
    if (sendingMessage || !draft.trim()) return
    void dispatchMessage(draft, false)
  }

  function handleComposerKeyDown(event: KeyboardEvent<HTMLTextAreaElement>) {
    if (event.key === 'Enter' && !event.shiftKey) {
      event.preventDefault()
      if (!sendingMessage && draft.trim()) {
        void dispatchMessage(draft, false)
      }
    }
  }

  async function handleAcceptProposal(proposal: SharedProposal) {
    if (!activeConversation || !snapshot.canEdit || decidingProposalId !== null) return
    setActionError('')
    setActionInfo('')
    setDecidingProposalId(proposal.id)
    try {
      if (onBeforeAcceptProposal) {
        const ready = await onBeforeAcceptProposal()
        if (!ready) {
          setActionError(
            'Guarda o sincroniza tus cambios pendientes antes de aplicar la propuesta compartida.',
          )
          return
        }
      }
      const updated = await aceptarPropuestaConversacion(
        projectId,
        activeConversation.id,
        proposal.id,
        { clientId },
      )
      store.upsertProposal(updated)
      setActionInfo(
        updated.aplicadaRevision !== null
          ? `Propuesta aplicada en la revisión #${updated.aplicadaRevision}.`
          : 'Propuesta aplicada al diagrama.',
      )
      onProposalApplied?.(updated)
    } catch (error: unknown) {
      const parsed = extractConversationApiError(
        error,
        'No se pudo aplicar la propuesta UML.',
      )
      handleUnauthorizedIfNeeded(parsed.unauthorized)
      if (parsed.code === 'ELEMENT_LOCKED') {
        onLockConflict?.(parsed.message)
      }
      if (
        parsed.code === 'PROPOSAL_STALE' ||
        parsed.code === 'PROPOSAL_ALREADY_DECIDED' ||
        parsed.code === 'PROPOSAL_INVALID'
      ) {
        await loadLatestMessages(activeConversation.id)
      }
      setActionError(parsed.message)
    } finally {
      setDecidingProposalId(null)
    }
  }

  async function handleRejectProposal(proposal: SharedProposal) {
    if (!activeConversation || !snapshot.canEdit || decidingProposalId !== null) return
    setActionError('')
    setActionInfo('')
    setDecidingProposalId(proposal.id)
    try {
      const updated = await rechazarPropuestaConversacion(
        projectId,
        activeConversation.id,
        proposal.id,
        { clientId },
      )
      store.upsertProposal(updated)
      setActionInfo('Propuesta rechazada.')
    } catch (error: unknown) {
      const parsed = extractConversationApiError(
        error,
        'No se pudo rechazar la propuesta.',
      )
      handleUnauthorizedIfNeeded(parsed.unauthorized)
      if (parsed.code === 'PROPOSAL_ALREADY_DECIDED') {
        await loadLatestMessages(activeConversation.id)
      }
      setActionError(parsed.message)
    } finally {
      setDecidingProposalId(null)
    }
  }

  function handleInsertMention() {
    if (!activeConversation || !snapshot.canEdit) return
    const nextDraft = insertAgentMention(draft)
    store.setDraft(activeConversation.id, nextDraft)
    requestAnimationFrame(() => {
      const el = textareaRef.current
      if (el) {
        el.focus()
        el.setSelectionRange(nextDraft.length, nextDraft.length)
      }
    })
  }

  const connectionBadge =
    collaborationStatus === 'connected'
      ? { text: 'En vivo', className: 'is-online' }
      : collaborationStatus === 'connecting'
        ? { text: 'Conectando…', className: 'is-connecting' }
        : { text: 'Reconectando…', className: 'is-offline' }

  return (
    <section className={`agent-panel ${snapshot.panelOpen ? 'is-open' : ''}`}>
      <button
        className="agent-toggle"
        type="button"
        onClick={() => store.setPanelOpen(!snapshot.panelOpen)}
        aria-expanded={snapshot.panelOpen}
        aria-controls="agent-conversation"
      >
        <span className="agent-toggle-icon" aria-hidden="true">
          💬
        </span>
        <span>Conversaciones e IA</span>
        {snapshot.activeAiStatus?.active && (
          <span
            className="agent-toggle-pulse"
            title="LogicDraft está respondiendo"
            aria-label="LogicDraft está respondiendo"
          />
        )}
        {snapshot.totalUnread > 0 && (
          <span className="agent-unread-badge" aria-label={`${snapshot.totalUnread} sin leer`}>
            {snapshot.totalUnread}
          </span>
        )}
      </button>

      {snapshot.panelOpen && (
        <div
          id="agent-conversation"
          className={`agent-conversation agent-whatsapp-shell ${
            sidebarDrawerOpen ? 'is-drawer-open' : ''
          }`}
        >
          {/* PANEL IZQUIERDO: LISTA DE CONVERSACIONES */}
          <aside className="agent-conv-sidebar" aria-label="Conversaciones del proyecto">
            <div className="agent-conv-sidebar-header">
              <strong>CONVERSACIONES</strong>
              {snapshot.canEdit && (
                <button
                  type="button"
                  className="agent-btn-new-conv"
                  onClick={() => {
                    setCreatingNew((value) => !value)
                    setActionError('')
                  }}
                >
                  {creatingNew ? 'Cerrar' : '+ Nueva'}
                </button>
              )}
            </div>

            <div className="agent-conv-filter-bar">
              <label className="agent-archive-toggle">
                <input
                  type="checkbox"
                  checked={snapshot.includeArchived}
                  onChange={(event) => store.setIncludeArchived(event.target.checked)}
                />
                <span>Ver archivadas</span>
              </label>
              {loadingList && <small className="agent-muted">Sync…</small>}
            </div>

            {creatingNew && snapshot.canEdit && (
              <form className="agent-new-conv-form" onSubmit={handleCreateConversation}>
                <input
                  type="text"
                  maxLength={140}
                  value={newTitle}
                  onChange={(event) => setNewTitle(event.target.value)}
                  placeholder="Ej. Arquitectura de ventas"
                  aria-label="Título de la nueva conversación"
                  autoFocus
                />
                <div className="agent-new-conv-actions">
                  <button
                    type="submit"
                    className="primary"
                    disabled={creatingBusy || !newTitle.trim()}
                  >
                    {creatingBusy ? 'Creando…' : 'Crear'}
                  </button>
                  <button
                    type="button"
                    onClick={() => {
                      setCreatingNew(false)
                      setNewTitle('')
                    }}
                  >
                    Cancelar
                  </button>
                </div>
              </form>
            )}

            <div className="agent-conv-list" role="list">
              {snapshot.conversations.length === 0 && !loadingList && (
                <p className="agent-conv-empty">
                  {snapshot.canEdit
                    ? 'Aún no hay conversaciones activas. Crea una para colaborar con el equipo y LogicDraft IA.'
                    : 'No hay conversaciones activas en este proyecto.'}
                </p>
              )}

              {snapshot.conversations.map((conv) => {
                const isActive = conv.id === snapshot.activeConversationId
                const unread = snapshot.unreadByConversation[conv.id] ?? 0
                return (
                  <button
                    key={conv.id}
                    type="button"
                    role="listitem"
                    className={`agent-conv-item ${isActive ? 'is-active' : ''} ${
                      conv.estado === 'ARCHIVED' ? 'is-archived' : ''
                    }`}
                    onClick={() => {
                      store.selectConversation(conv.id)
                      setRenamingId(null)
                      setSidebarDrawerOpen(false)
                      setActionError('')
                      setActionInfo('')
                    }}
                  >
                    <div className="agent-conv-item-top">
                      <span className="agent-conv-item-title">{conv.titulo}</span>
                      {unread > 0 && <span className="agent-unread-badge">{unread}</span>}
                    </div>
                    <div className="agent-conv-item-meta">
                      <small>{conv.creadoPorNombre || 'Equipo'}</small>
                      <small>
                        {conv.estado === 'ARCHIVED'
                          ? 'Archivada'
                          : formatServerTime(conv.fechaActualizacion)}
                      </small>
                    </div>
                  </button>
                )
              })}
            </div>
          </aside>

          {/* PANEL DERECHO: CONVERSACIÓN ACTIVA */}
          <div className="agent-chat-main">
            <header className="agent-chat-header">
              <div className="agent-chat-header-left">
                <button
                  type="button"
                  className="agent-drawer-btn"
                  onClick={() => setSidebarDrawerOpen((value) => !value)}
                  aria-label="Mostrar u ocultar lista de conversaciones"
                  title="Conversaciones"
                >
                  ☰ Hilos ({snapshot.conversations.length})
                </button>

                {activeConversation ? (
                  renamingId === activeConversation.id ? (
                    <form className="agent-rename-form" onSubmit={handleRenameSubmit}>
                      <input
                        type="text"
                        maxLength={140}
                        value={renameTitle}
                        onChange={(event) => setRenameTitle(event.target.value)}
                        aria-label="Nuevo título de conversación"
                        autoFocus
                      />
                      <button
                        type="submit"
                        disabled={conversationActionBusy || !renameTitle.trim()}
                      >
                        Guardar
                      </button>
                      <button type="button" onClick={() => setRenamingId(null)}>
                        ✕
                      </button>
                    </form>
                  ) : (
                    <div className="agent-chat-title-group">
                      <strong>{activeConversation.titulo}</strong>
                      <div className="agent-chat-badges">
                        <span className={`agent-conn-pill ${connectionBadge.className}`}>
                          {connectionBadge.text}
                        </span>
                        {!snapshot.canEdit && (
                          <span className="agent-role-pill">Solo lectura</span>
                        )}
                        {activeConversation.estado === 'ARCHIVED' && (
                          <span className="agent-archived-pill">Archivada</span>
                        )}
                      </div>
                    </div>
                  )
                ) : (
                  <div className="agent-chat-title-group">
                    <strong>Conversaciones del proyecto</strong>
                    <span className={`agent-conn-pill ${connectionBadge.className}`}>
                      {connectionBadge.text}
                    </span>
                  </div>
                )}
              </div>

              {activeConversation && snapshot.canEdit && renamingId !== activeConversation.id && (
                <div className="agent-chat-header-actions">
                  <button
                    type="button"
                    onClick={() => {
                      setRenamingId(activeConversation.id)
                      setRenameTitle(activeConversation.titulo)
                    }}
                    disabled={conversationActionBusy}
                    title="Renombrar conversación"
                  >
                    Renombrar
                  </button>
                  <button
                    type="button"
                    onClick={() => void handleToggleArchive()}
                    disabled={conversationActionBusy}
                    title={
                      activeConversation.estado === 'ACTIVE'
                        ? 'Archivar conversación sin borrar su historial'
                        : 'Reactivar conversación'
                    }
                  >
                    {activeConversation.estado === 'ACTIVE' ? 'Archivar' : 'Reactivar'}
                  </button>
                </div>
              )}
            </header>

            {/* HISTORIAL DE MENSAJES */}
            <div
              ref={messagesContainerRef}
              className="agent-messages"
              aria-live="polite"
            >
              {!activeConversation && (
                <div className="agent-empty-state">
                  <p>
                    Selecciona una conversación o crea una nueva para coordinar el diseño UML con
                    tus colaboradores y con <b>LogicDraft IA</b>.
                  </p>
                  {snapshot.canEdit && !creatingNew && (
                    <button
                      type="button"
                      className="agent-primary-cta"
                      onClick={() => {
                        setCreatingNew(true)
                        setSidebarDrawerOpen(true)
                      }}
                    >
                      + Nueva conversación
                    </button>
                  )}
                </div>
              )}

              {activeConversation && activeTimeline?.hasMore && (
                <div className="agent-pagination-bar">
                  <button
                    type="button"
                    onClick={() => void handleLoadOlderMessages()}
                    disabled={loadingOlder}
                  >
                    {loadingOlder ? 'Cargando historial…' : 'Cargar mensajes anteriores'}
                  </button>
                </div>
              )}

              {activeConversation && loadingMessages && !activeTimeline?.messages.length && (
                <p className="agent-muted-center">Cargando mensajes…</p>
              )}

              {activeConversation &&
                !loadingMessages &&
                (activeTimeline?.messages.length ?? 0) === 0 &&
                (activeTimeline?.optimisticMessages.length ?? 0) === 0 && (
                  <div className="agent-empty-state">
                    <p>
                      Conversación lista. Escribe libremente con tu equipo; la IA solo intervendrá
                      cuando menciones <code>@LogicDraft</code> o pulses{' '}
                      <b>Preguntar al agente</b>.
                    </p>
                  </div>
                )}

              {activeConversation &&
                activeTimeline?.messages.map((msg) => (
                  <MessageBubble
                    key={msg.id}
                    message={msg}
                    canEdit={snapshot.canEdit && activeConversation.estado === 'ACTIVE'}
                    nodes={nodes}
                    edges={edges}
                    decidingProposalId={decidingProposalId}
                    onAcceptProposal={handleAcceptProposal}
                    onRejectProposal={handleRejectProposal}
                  />
                ))}

              {activeConversation &&
                activeTimeline?.optimisticMessages.map((pending) => (
                  <OptimisticBubble
                    key={pending.clientMessageId}
                    pending={pending}
                    onRetry={() =>
                      void dispatchMessage(
                        pending.contenido,
                        pending.invokeAgent,
                        pending.clientMessageId,
                      )
                    }
                    onDiscard={() =>
                      store.removeOptimisticMessage(
                        pending.conversationId,
                        pending.clientMessageId,
                      )
                    }
                  />
                ))}

              {activeConversation && activeAiStatus?.active && (
                <div className="agent-ai-typing" role="status">
                  <span className="agent-typing-dots" aria-hidden="true">
                    <i />
                    <i />
                    <i />
                  </span>
                  <span>
                    <b>LogicDraft está respondiendo</b>
                    {activeAiStatus.requestedByName
                      ? ` · solicitado por ${activeAiStatus.requestedByName}`
                      : ''}
                  </span>
                </div>
              )}
            </div>

            {/* AVISOS Y ERRORES */}
            {(actionError || activeAiStatus?.error || actionInfo) && (
              <div
                className={`agent-notice-bar ${
                  actionError || activeAiStatus?.error ? 'is-error' : 'is-info'
                }`}
                role={actionError || activeAiStatus?.error ? 'alert' : 'status'}
              >
                <span>{actionError || activeAiStatus?.error || actionInfo}</span>
                <button
                  type="button"
                  aria-label="Cerrar aviso"
                  onClick={() => {
                    setActionError('')
                    setActionInfo('')
                    if (activeConversation) {
                      store.clearAiError(activeConversation.id)
                    }
                  }}
                >
                  ✕
                </button>
              </div>
            )}

            {/* COMPOSITOR DE MENSAJES */}
            {activeConversation && (
              <div className="agent-composer-wrap">
                {!snapshot.canEdit ? (
                  <div className="agent-readonly-banner">
                    Modo solo lectura (<code>VIEWER</code>): puedes seguir todas las conversaciones
                    y propuestas en vivo, pero no enviar mensajes ni aplicar cambios.
                  </div>
                ) : activeConversation.estado === 'ARCHIVED' ? (
                  <div className="agent-readonly-banner">
                    <span>Esta conversación está archivada.</span>
                    <button
                      type="button"
                      onClick={() => void handleToggleArchive()}
                      disabled={conversationActionBusy}
                    >
                      Reactivar para escribir
                    </button>
                  </div>
                ) : (
                  <form className="agent-composer-form" onSubmit={handleComposerSubmit}>
                    <div className="agent-composer-toolbar">
                      <button
                        type="button"
                        className={`agent-mention-chip ${mentionsAgent ? 'is-active' : ''}`}
                        onClick={handleInsertMention}
                        aria-pressed={mentionsAgent}
                        title="Mencionar a @LogicDraft en el mensaje"
                      >
                        @LogicDraft
                      </button>
                      <small className="agent-composer-mode">
                        {mentionsAgent
                          ? 'LogicDraft IA responderá en este hilo'
                          : 'Mensaje entre colaboradores (sin IA)'}
                      </small>
                    </div>

                    <textarea
                      ref={textareaRef}
                      maxLength={4000}
                      rows={2}
                      value={draft}
                      onChange={(event) =>
                        store.setDraft(activeConversation.id, event.target.value)
                      }
                      onKeyDown={handleComposerKeyDown}
                      placeholder="Escribe al equipo o usa @LogicDraft para pedir cambios o análisis UML…"
                      aria-label="Mensaje para la conversación compartida"
                    />

                    <div className="agent-composer-actions">
                      <button
                        type="submit"
                        className="agent-btn-send"
                        disabled={sendingMessage || !draft.trim()}
                      >
                        {sendingMessage && !mentionsAgent ? 'Enviando…' : 'Enviar mensaje'}
                      </button>
                      <button
                        type="button"
                        className="agent-btn-ask-ai"
                        disabled={
                          sendingMessage ||
                          !draft.trim() ||
                          saveStatus === 'conflict'
                        }
                        onClick={() => void dispatchMessage(draft, true)}
                        title="Enviar este mensaje e invocar explícitamente a LogicDraft IA"
                      >
                        {sendingMessage && mentionsAgent
                          ? 'Consultando IA…'
                          : 'Preguntar al agente'}
                      </button>
                    </div>
                    {disabledReason && (
                      <small className="agent-composer-hint">
                        Nota: hay cambios locales guardándose; LogicDraft IA evalúa la última
                        revisión confirmada del diagrama.
                      </small>
                    )}
                  </form>
                )}
              </div>
            )}
          </div>
        </div>
      )}
    </section>
  )
}

function MessageBubble({
  message,
  canEdit,
  nodes,
  edges,
  decidingProposalId,
  onAcceptProposal,
  onRejectProposal,
}: {
  message: ConversationMessage
  canEdit: boolean
  nodes: readonly EntityFlowNode[]
  edges: readonly DiagramEdge[]
  decidingProposalId: number | null
  onAcceptProposal: (proposal: SharedProposal) => Promise<void>
  onRejectProposal: (proposal: SharedProposal) => Promise<void>
}) {
  const { authorLabel, timeLabel } = formatMessageAuthorAndTime(message)
  const color = messageAuthorColor(
    message.autorTipo,
    message.autorUsuarioId,
    message.autorNombre,
  )
  const isAgent = message.autorTipo === 'AGENT'
  const isSystem = message.autorTipo === 'SYSTEM'

  const proposal = message.proposal
  const badge = proposal ? proposalStateBadge(proposal) : null
  const impact = useMemo(
    () =>
      proposal && proposal.estado === 'PENDING'
        ? destructivePlanImpact(proposal.operations, nodes, edges)
        : [],
    [edges, nodes, proposal],
  )

  return (
    <article
      className={`agent-msg-bubble ${
        isAgent ? 'is-agent' : isSystem ? 'is-system' : 'is-user'
      } ${message.estado === 'FAILED' ? 'is-failed' : ''}`}
    >
      <div className="agent-msg-meta">
        <strong className="agent-msg-author" style={{ color }}>
          {authorLabel}
        </strong>
        {isAgent && message.solicitadoPorNombre && (
          <small className="agent-msg-reply-to">
            en respuesta a {message.solicitadoPorNombre}
          </small>
        )}
        {timeLabel && <time className="agent-msg-time">{timeLabel}</time>}
      </div>

      <div className="agent-msg-text">{renderHighlightedContent(message.contenido)}</div>

      {proposal && badge && (
        <div className={`agent-proposal-card tone-${badge.tone}`}>
          <div className="agent-proposal-header">
            <span>Propuesta UML · rev. base #{proposal.baseRevision}</span>
            <span className={`agent-proposal-badge tone-${badge.tone}`}>{badge.label}</span>
          </div>

          <ul className="agent-proposal-ops">
            {proposal.operations.map((op, index) => (
              <li key={index}>{diagramOperationLabel(op)}</li>
            ))}
          </ul>

          {impact.length > 0 && (
            <div className="diagram-ai-impact agent-proposal-impact">
              <strong>Impacto adicional:</strong>
              <ul>
                {impact.map((item) => (
                  <li key={item}>{item}</li>
                ))}
              </ul>
            </div>
          )}

          {proposal.resultadoAplicacion && (
            <small className="agent-proposal-result">{proposal.resultadoAplicacion}</small>
          )}

          {proposal.estado === 'PENDING' && (
            <div className="agent-proposal-actions">
              {canEdit ? (
                <>
                  <button
                    type="button"
                    className="accept"
                    disabled={decidingProposalId !== null}
                    onClick={() => void onAcceptProposal(proposal)}
                  >
                    {decidingProposalId === proposal.id
                      ? 'Aplicando…'
                      : 'Aceptar propuesta'}
                  </button>
                  <button
                    type="button"
                    className="reject"
                    disabled={decidingProposalId !== null}
                    onClick={() => void onRejectProposal(proposal)}
                  >
                    Rechazar
                  </button>
                </>
              ) : (
                <small className="agent-muted">
                  Solo OWNER o EDITOR en una conversación activa pueden decidir esta propuesta.
                </small>
              )}
            </div>
          )}
        </div>
      )}
    </article>
  )
}

function OptimisticBubble({
  pending,
  onRetry,
  onDiscard,
}: {
  pending: PendingOptimisticMessage
  onRetry: () => void
  onDiscard: () => void
}) {
  return (
    <article
      className={`agent-msg-bubble is-user is-optimistic ${
        pending.status === 'failed' ? 'is-failed' : ''
      }`}
    >
      <div className="agent-msg-meta">
        <strong className="agent-msg-author">
          {pending.status === 'failed' ? 'No enviado' : pending.autorNombre}
        </strong>
        <small className="agent-msg-time">
          {pending.status === 'failed' ? 'Error' : 'Enviando…'}
        </small>
      </div>
      <div className="agent-msg-text">{renderHighlightedContent(pending.contenido)}</div>
      {pending.status === 'failed' && (
        <div className="agent-optimistic-error">
          {pending.errorMessage && <small>{pending.errorMessage}</small>}
          <div>
            <button type="button" onClick={onRetry}>
              Reintentar
            </button>
            <button type="button" onClick={onDiscard}>
              Descartar
            </button>
          </div>
        </div>
      )}
    </article>
  )
}
