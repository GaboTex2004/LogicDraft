import { collaboratorColor } from '../../collaboration/services/remoteCursors.ts'
import type { CollaborationEvent } from '../../collaboration/types/collaboration.types.ts'
import type {
  AgentConversationsSnapshot,
  AgentProposalState,
  AiTypingStatus,
  ConversationMessage,
  ConversationMessageStatus,
  ConversationMessagesPageResponse,
  ConversationState,
  ConversationSummary,
  ConversationTimelineSnapshot,
  MessageAuthorType,
  PendingOptimisticMessage,
  ProjectConversationsListResponse,
  SendMessageResultResponse,
  SharedProposal,
} from '../types/agentConversation.types.ts'

const EXPLICIT_MENTION_REGEX = /(?:^|[^\p{L}\p{N}_])@logicdraft(?=$|[^\p{L}\p{N}_])/iu

const PROPOSAL_STATES = new Set<AgentProposalState>([
  'PENDING',
  'ACCEPTED',
  'REJECTED',
  'STALE',
  'INVALID',
])

const AUTHOR_TYPES = new Set<MessageAuthorType>(['USER', 'AGENT', 'SYSTEM'])
const MESSAGE_STATUSES = new Set<ConversationMessageStatus>(['SENT', 'PROCESSING', 'FAILED'])
const CONVERSATION_STATES = new Set<ConversationState>(['ACTIVE', 'ARCHIVED'])

function asRecord(value: unknown): Record<string, unknown> | null {
  return typeof value === 'object' && value !== null ? (value as Record<string, unknown>) : null
}

function readStoredConversationId(projectId: number): number | null {
  try {
    if (typeof globalThis.sessionStorage === 'undefined') return null
    const raw = globalThis.sessionStorage.getItem(`logicdraft:active-conv:${projectId}`)
    if (!raw) return null
    const parsed = Number(raw)
    return Number.isSafeInteger(parsed) && parsed > 0 ? parsed : null
  } catch {
    return null
  }
}

function writeStoredConversationId(projectId: number, conversationId: number | null): void {
  try {
    if (typeof globalThis.sessionStorage === 'undefined') return
    const key = `logicdraft:active-conv:${projectId}`
    if (conversationId === null) {
      globalThis.sessionStorage.removeItem(key)
    } else {
      globalThis.sessionStorage.setItem(key, String(conversationId))
    }
  } catch {
    // Ignore storage quota or private mode restrictions
  }
}

export function hasExplicitAgentMention(text: string): boolean {
  if (!text) return false
  return EXPLICIT_MENTION_REGEX.test(text)
}

export function insertAgentMention(draft: string): string {
  if (hasExplicitAgentMention(draft)) return draft
  const trimmedStart = draft.trimStart()
  if (!trimmedStart) return '@LogicDraft '
  return `@LogicDraft ${trimmedStart}`
}

export function formatMessageAuthorAndTime(
  message: Pick<ConversationMessage, 'autorTipo' | 'autorNombre' | 'fechaCreacion'>,
): { authorLabel: string; timeLabel: string } {
  const cleanName = message.autorNombre?.trim()
  const authorLabel =
    message.autorTipo === 'AGENT'
      ? cleanName || 'LogicDraft IA'
      : message.autorTipo === 'SYSTEM'
        ? cleanName || 'Sistema'
        : cleanName || 'Colaborador'

  const timeLabel = formatServerTime(message.fechaCreacion)
  return { authorLabel, timeLabel }
}

export function formatServerTime(isoLike: string | null | undefined): string {
  if (!isoLike) return ''
  // Fast deterministic HH:mm extraction for LocalDateTime ("2026-10-28T10:30:15") or ISO strings
  const match = /T(\d{2}:\d{2})/.exec(isoLike)
  if (match) {
    // If timezone offset or Z is present, format in local time when valid Date
    if (/[Zz]|[+-]\d{2}:?\d{2}$/.test(isoLike)) {
      const parsed = new Date(isoLike)
      if (!Number.isNaN(parsed.getTime())) {
        return parsed.toLocaleTimeString('es-BO', {
          hour: '2-digit',
          minute: '2-digit',
          hour12: false,
        })
      }
    }
    return match[1]
  }
  return ''
}

export function messageAuthorColor(
  autorTipo: MessageAuthorType,
  autorUsuarioId: number | null,
  autorNombre: string,
): string {
  if (autorTipo === 'AGENT') return '#c4b5fd'
  if (autorTipo === 'SYSTEM') return '#94a3b8'
  return collaboratorColor(autorUsuarioId ?? (autorNombre || 'user'))
}

export function proposalStateBadge(proposal: Pick<SharedProposal, 'estado' | 'decididoPorNombre'>): {
  label: string
  tone: 'pending' | 'accepted' | 'rejected' | 'stale' | 'invalid'
} {
  const actor = proposal.decididoPorNombre?.trim()
  switch (proposal.estado) {
    case 'PENDING':
      return { label: 'Pendiente de revisión', tone: 'pending' }
    case 'ACCEPTED':
      return { label: actor ? `Aceptada por ${actor}` : 'Aceptada', tone: 'accepted' }
    case 'REJECTED':
      return { label: actor ? `Rechazada por ${actor}` : 'Rechazada', tone: 'rejected' }
    case 'STALE':
      return { label: 'Obsoleta (el diagrama cambió)', tone: 'stale' }
    case 'INVALID':
      return { label: 'No aplicable', tone: 'invalid' }
  }
}

function sortConversations(items: readonly ConversationSummary[]): ConversationSummary[] {
  return [...items].sort((left, right) => {
    const timeCmp = (right.fechaActualizacion || '').localeCompare(left.fechaActualizacion || '')
    if (timeCmp !== 0) return timeCmp
    return right.id - left.id
  })
}

function mergeConfirmedMessages(
  existing: readonly ConversationMessage[],
  incoming: readonly ConversationMessage[],
): ConversationMessage[] {
  const byId = new Map<number, ConversationMessage>()
  const clientKeyToId = new Map<string, number>()

  for (const msg of existing) {
    byId.set(msg.id, msg)
    if (msg.clientMessageId) {
      clientKeyToId.set(msg.clientMessageId, msg.id)
    }
  }

  for (const msg of incoming) {
    if (msg.clientMessageId) {
      const priorId = clientKeyToId.get(msg.clientMessageId)
      if (priorId !== undefined && priorId !== msg.id) {
        byId.delete(priorId)
      }
      clientKeyToId.set(msg.clientMessageId, msg.id)
    }
    const previous = byId.get(msg.id)
    if (previous?.proposal && !msg.proposal) {
      byId.set(msg.id, { ...msg, proposal: previous.proposal })
    } else {
      byId.set(msg.id, msg)
    }
  }

  return [...byId.values()].sort((a, b) => a.id - b.id)
}

function parseSummaryPayload(raw: unknown): ConversationSummary | null {
  const obj = asRecord(raw)
  if (!obj) return null
  if (
    typeof obj.id !== 'number' ||
    typeof obj.projectId !== 'number' ||
    typeof obj.titulo !== 'string' ||
    typeof obj.estado !== 'string' ||
    !CONVERSATION_STATES.has(obj.estado as ConversationState)
  ) {
    return null
  }
  return {
    id: obj.id,
    projectId: obj.projectId,
    titulo: obj.titulo,
    estado: obj.estado as ConversationState,
    creadoPorId: typeof obj.creadoPorId === 'number' ? obj.creadoPorId : 0,
    creadoPorNombre: typeof obj.creadoPorNombre === 'string' ? obj.creadoPorNombre : '',
    fechaCreacion: typeof obj.fechaCreacion === 'string' ? obj.fechaCreacion : '',
    fechaActualizacion: typeof obj.fechaActualizacion === 'string' ? obj.fechaActualizacion : '',
  }
}

function parseProposalPayload(raw: unknown): SharedProposal | null {
  const obj = asRecord(raw)
  if (!obj) return null
  if (
    typeof obj.id !== 'number' ||
    typeof obj.conversationId !== 'number' ||
    typeof obj.messageId !== 'number' ||
    typeof obj.projectId !== 'number' ||
    typeof obj.estado !== 'string' ||
    !PROPOSAL_STATES.has(obj.estado as AgentProposalState)
  ) {
    return null
  }
  return {
    id: obj.id,
    conversationId: obj.conversationId,
    messageId: obj.messageId,
    projectId: obj.projectId,
    solicitadoPorId: typeof obj.solicitadoPorId === 'number' ? obj.solicitadoPorId : 0,
    solicitadoPorNombre: typeof obj.solicitadoPorNombre === 'string' ? obj.solicitadoPorNombre : '',
    estado: obj.estado as AgentProposalState,
    baseRevision: typeof obj.baseRevision === 'number' ? obj.baseRevision : 0,
    aplicadaRevision: typeof obj.aplicadaRevision === 'number' ? obj.aplicadaRevision : null,
    operations: Array.isArray(obj.operations) ? (obj.operations as SharedProposal['operations']) : [],
    affectedElementIds: Array.isArray(obj.affectedElementIds)
      ? obj.affectedElementIds.filter((item): item is string => typeof item === 'string')
      : [],
    resumen: typeof obj.resumen === 'string' ? obj.resumen : null,
    decididoPorId: typeof obj.decididoPorId === 'number' ? obj.decididoPorId : null,
    decididoPorNombre: typeof obj.decididoPorNombre === 'string' ? obj.decididoPorNombre : null,
    fechaDecision: typeof obj.fechaDecision === 'string' ? obj.fechaDecision : null,
    resultadoAplicacion: typeof obj.resultadoAplicacion === 'string' ? obj.resultadoAplicacion : null,
    fechaCreacion: typeof obj.fechaCreacion === 'string' ? obj.fechaCreacion : '',
  }
}

function parseMessagePayload(raw: unknown): ConversationMessage | null {
  const obj = asRecord(raw)
  if (!obj) return null
  if (
    typeof obj.id !== 'number' ||
    typeof obj.conversationId !== 'number' ||
    typeof obj.projectId !== 'number' ||
    typeof obj.autorTipo !== 'string' ||
    !AUTHOR_TYPES.has(obj.autorTipo as MessageAuthorType) ||
    typeof obj.contenido !== 'string'
  ) {
    return null
  }
  const estado =
    typeof obj.estado === 'string' && MESSAGE_STATUSES.has(obj.estado as ConversationMessageStatus)
      ? (obj.estado as ConversationMessageStatus)
      : 'SENT'
  return {
    id: obj.id,
    conversationId: obj.conversationId,
    projectId: obj.projectId,
    autorTipo: obj.autorTipo as MessageAuthorType,
    autorUsuarioId: typeof obj.autorUsuarioId === 'number' ? obj.autorUsuarioId : null,
    autorNombre: typeof obj.autorNombre === 'string' ? obj.autorNombre : '',
    solicitadoPorId: typeof obj.solicitadoPorId === 'number' ? obj.solicitadoPorId : null,
    solicitadoPorNombre:
      typeof obj.solicitadoPorNombre === 'string' ? obj.solicitadoPorNombre : null,
    contenido: obj.contenido,
    estado,
    clientMessageId: typeof obj.clientMessageId === 'string' ? obj.clientMessageId : null,
    mensajeOrigenId: typeof obj.mensajeOrigenId === 'number' ? obj.mensajeOrigenId : null,
    fechaCreacion: typeof obj.fechaCreacion === 'string' ? obj.fechaCreacion : '',
    proposal: obj.proposal ? parseProposalPayload(obj.proposal) : null,
  }
}

interface InternalTimeline {
  conversationId: number
  messages: ConversationMessage[]
  optimisticMessages: PendingOptimisticMessage[]
  hasMore: boolean
  nextBeforeId: number | null
  loadedInitial: boolean
}

export class AgentConversationStore {
  readonly projectId: number
  private canEditValue = true
  private loadedListValue = false
  private includeArchivedValue = false
  private panelOpenValue = false
  private conversationsValue: ConversationSummary[] = []
  private activeConversationIdValue: number | null
  private timelines = new Map<number, InternalTimeline>()
  private aiStatusMap = new Map<number, AiTypingStatus>()
  private drafts = new Map<number, string>()
  private unreadMap = new Map<number, number>()
  private listeners = new Set<() => void>()
  private snapshotCache: AgentConversationsSnapshot

  constructor(projectId: number) {
    this.projectId = projectId
    this.activeConversationIdValue = readStoredConversationId(projectId)
    this.snapshotCache = this.computeSnapshot()
  }

  subscribe = (listener: () => void): (() => void) => {
    this.listeners.add(listener)
    return () => {
      this.listeners.delete(listener)
    }
  }

  getSnapshot = (): AgentConversationsSnapshot => this.snapshotCache

  private emit(): void {
    this.snapshotCache = this.computeSnapshot()
    for (const listener of this.listeners) {
      listener()
    }
  }

  private ensureTimeline(conversationId: number): InternalTimeline {
    let timeline = this.timelines.get(conversationId)
    if (!timeline) {
      timeline = {
        conversationId,
        messages: [],
        optimisticMessages: [],
        hasMore: false,
        nextBeforeId: null,
        loadedInitial: false,
      }
      this.timelines.set(conversationId, timeline)
    }
    return timeline
  }

  private visibleConversations(): ConversationSummary[] {
    if (this.includeArchivedValue) return this.conversationsValue
    return this.conversationsValue.filter((conv) => conv.estado === 'ACTIVE')
  }

  private computeSnapshot(): AgentConversationsSnapshot {
    const visible = this.visibleConversations()
    const activeConversation =
      (this.activeConversationIdValue !== null
        ? this.conversationsValue.find((item) => item.id === this.activeConversationIdValue)
        : null) ?? null

    const timeline =
      activeConversation !== null ? this.timelines.get(activeConversation.id) ?? null : null
    const timelineSnapshot: ConversationTimelineSnapshot | null = timeline
      ? {
          conversationId: timeline.conversationId,
          messages: timeline.messages,
          optimisticMessages: timeline.optimisticMessages,
          hasMore: timeline.hasMore,
          nextBeforeId: timeline.nextBeforeId,
          loadedInitial: timeline.loadedInitial,
        }
      : null

    const activeAiStatus =
      activeConversation !== null ? this.aiStatusMap.get(activeConversation.id) ?? null : null

    const unreadRecord: Record<number, number> = {}
    let totalUnread = 0
    for (const [convId, count] of this.unreadMap.entries()) {
      if (count > 0) {
        unreadRecord[convId] = count
        totalUnread += count
      }
    }

    return {
      projectId: this.projectId,
      canEdit: this.canEditValue,
      loadedList: this.loadedListValue,
      includeArchived: this.includeArchivedValue,
      panelOpen: this.panelOpenValue,
      conversations: visible,
      activeConversationId: activeConversation?.id ?? null,
      activeConversation,
      activeTimeline: timelineSnapshot,
      activeAiStatus,
      activeDraft:
        activeConversation !== null ? this.drafts.get(activeConversation.id) ?? '' : '',
      totalUnread,
      unreadByConversation: unreadRecord,
    }
  }

  setPanelOpen(open: boolean): void {
    if (this.panelOpenValue === open) return
    this.panelOpenValue = open
    if (open && this.activeConversationIdValue !== null) {
      this.unreadMap.delete(this.activeConversationIdValue)
    }
    this.emit()
  }

  setIncludeArchived(includeArchived: boolean): void {
    if (this.includeArchivedValue === includeArchived) return
    this.includeArchivedValue = includeArchived
    const visible = this.visibleConversations()
    if (
      this.activeConversationIdValue !== null &&
      !visible.some((item) => item.id === this.activeConversationIdValue)
    ) {
      const fallback = visible[0]?.id ?? null
      this.activeConversationIdValue = fallback
      writeStoredConversationId(this.projectId, fallback)
    }
    this.emit()
  }

  setDraft(conversationId: number, text: string): void {
    const current = this.drafts.get(conversationId) ?? ''
    if (current === text) return
    if (text.length === 0) {
      this.drafts.delete(conversationId)
    } else {
      this.drafts.set(conversationId, text)
    }
    this.emit()
  }

  getDraft(conversationId: number): string {
    return this.drafts.get(conversationId) ?? ''
  }

  hasLoadedTimeline(conversationId: number): boolean {
    return this.timelines.get(conversationId)?.loadedInitial === true
  }

  setConversationsList(response: ProjectConversationsListResponse): void {
    if (response.projectId !== this.projectId) return
    this.canEditValue = response.canEdit
    this.loadedListValue = true

    // Preserve any archived conversations in cache if response was active-only, or replace cleanly
    const byId = new Map<number, ConversationSummary>()
    if (!this.includeArchivedValue) {
      for (const prev of this.conversationsValue) {
        if (prev.estado === 'ARCHIVED') {
          byId.set(prev.id, prev)
        }
      }
    }
    for (const conv of response.conversations) {
      byId.set(conv.id, conv)
    }
    this.conversationsValue = sortConversations([...byId.values()])

    const visible = this.visibleConversations()
    const currentId = this.activeConversationIdValue
    const stillVisible =
      currentId !== null && visible.some((conv) => conv.id === currentId)

    if (!stillVisible) {
      const nextActive =
        visible.find((conv) => conv.estado === 'ACTIVE')?.id ?? visible[0]?.id ?? null
      this.activeConversationIdValue = nextActive
      writeStoredConversationId(this.projectId, nextActive)
    }
    if (this.panelOpenValue && this.activeConversationIdValue !== null) {
      this.unreadMap.delete(this.activeConversationIdValue)
    }
    this.emit()
  }

  upsertConversation(conversation: ConversationSummary, selectNow = false): void {
    if (conversation.projectId !== this.projectId) return
    const filtered = this.conversationsValue.filter((item) => item.id !== conversation.id)
    this.conversationsValue = sortConversations([...filtered, conversation])

    if (selectNow || this.activeConversationIdValue === null) {
      if (conversation.estado === 'ARCHIVED' && !this.includeArchivedValue && selectNow) {
        this.includeArchivedValue = true
      }
      this.activeConversationIdValue = conversation.id
      writeStoredConversationId(this.projectId, conversation.id)
      if (this.panelOpenValue) {
        this.unreadMap.delete(conversation.id)
      }
    } else if (
      this.activeConversationIdValue === conversation.id &&
      conversation.estado === 'ARCHIVED' &&
      !this.includeArchivedValue
    ) {
      const visible = this.visibleConversations()
      const nextId = visible[0]?.id ?? null
      this.activeConversationIdValue = nextId
      writeStoredConversationId(this.projectId, nextId)
    }
    this.emit()
  }

  selectConversation(conversationId: number | null): void {
    if (this.activeConversationIdValue === conversationId) return
    if (
      conversationId !== null &&
      !this.conversationsValue.some((item) => item.id === conversationId)
    ) {
      return
    }
    this.activeConversationIdValue = conversationId
    writeStoredConversationId(this.projectId, conversationId)
    if (conversationId !== null && this.panelOpenValue) {
      this.unreadMap.delete(conversationId)
    }
    this.emit()
  }

  replaceInitialMessagesPage(page: ConversationMessagesPageResponse): void {
    const timeline = this.ensureTimeline(page.conversationId)
    const hadLoadedBefore = timeline.loadedInitial
    timeline.messages = mergeConfirmedMessages(timeline.messages, page.messages)

    // Remove any optimistic messages that are now confirmed
    const confirmedKeys = new Set(
      timeline.messages
        .map((msg) => msg.clientMessageId)
        .filter((key): key is string => Boolean(key)),
    )
    timeline.optimisticMessages = timeline.optimisticMessages.filter(
      (opt) => !confirmedKeys.has(opt.clientMessageId),
    )

    if (!hadLoadedBefore) {
      timeline.hasMore = page.hasMore
      timeline.nextBeforeId = page.nextBeforeId
    } else if (page.hasMore && !timeline.hasMore) {
      timeline.hasMore = page.hasMore
      timeline.nextBeforeId = page.nextBeforeId
    }
    timeline.loadedInitial = true
    this.emit()
  }

  prependOlderMessagesPage(page: ConversationMessagesPageResponse): void {
    const timeline = this.ensureTimeline(page.conversationId)
    timeline.messages = mergeConfirmedMessages(timeline.messages, page.messages)
    timeline.hasMore = page.hasMore
    timeline.nextBeforeId = page.nextBeforeId
    timeline.loadedInitial = true
    this.emit()
  }

  addOptimisticMessage(pending: PendingOptimisticMessage): void {
    if (pending.projectId !== this.projectId) return
    const timeline = this.ensureTimeline(pending.conversationId)
    // Skip if already confirmed by STOMP race
    if (timeline.messages.some((msg) => msg.clientMessageId === pending.clientMessageId)) {
      return
    }
    const withoutSame = timeline.optimisticMessages.filter(
      (item) => item.clientMessageId !== pending.clientMessageId,
    )
    timeline.optimisticMessages = [...withoutSame, pending]
    this.emit()
  }

  markOptimisticFailed(
    conversationId: number,
    clientMessageId: string,
    errorMessage: string,
  ): void {
    const timeline = this.timelines.get(conversationId)
    if (!timeline) return
    // If the user message WAS already persisted & broadcast via STOMP before AI failed,
    // do not keep a duplicate failed bubble; surface the AI error on aiStatus instead!
    if (timeline.messages.some((msg) => msg.clientMessageId === clientMessageId)) {
      timeline.optimisticMessages = timeline.optimisticMessages.filter(
        (item) => item.clientMessageId !== clientMessageId,
      )
      this.aiStatusMap.set(conversationId, {
        conversationId,
        active: false,
        requestedByUserId: null,
        requestedByName: null,
        error: errorMessage,
      })
      this.emit()
      return
    }
    timeline.optimisticMessages = timeline.optimisticMessages.map((item) =>
      item.clientMessageId === clientMessageId
        ? { ...item, status: 'failed', errorMessage }
        : item,
    )
    this.emit()
  }

  removeOptimisticMessage(conversationId: number, clientMessageId: string): void {
    const timeline = this.timelines.get(conversationId)
    if (!timeline) return
    const next = timeline.optimisticMessages.filter(
      (item) => item.clientMessageId !== clientMessageId,
    )
    if (next.length === timeline.optimisticMessages.length) return
    timeline.optimisticMessages = next
    this.emit()
  }

  private ingestConfirmedMessage(message: ConversationMessage, bumpTimestamp = true): void {
    const timeline = this.ensureTimeline(message.conversationId)
    timeline.messages = mergeConfirmedMessages(timeline.messages, [message])
    if (message.clientMessageId) {
      timeline.optimisticMessages = timeline.optimisticMessages.filter(
        (item) => item.clientMessageId !== message.clientMessageId,
      )
    }
    if (bumpTimestamp && message.fechaCreacion) {
      const target = this.conversationsValue.find((item) => item.id === message.conversationId)
      if (target && message.fechaCreacion > target.fechaActualizacion) {
        const updated: ConversationSummary = {
          ...target,
          fechaActualizacion: message.fechaCreacion,
        }
        this.conversationsValue = sortConversations([
          ...this.conversationsValue.filter((item) => item.id !== updated.id),
          updated,
        ])
      }
    }
  }

  reconcileSendResult(result: SendMessageResultResponse): void {
    const convId = result.userMessage.conversationId
    this.ingestConfirmedMessage(result.userMessage)
    if (result.agentMessage) {
      this.ingestConfirmedMessage(result.agentMessage)
    }
    if (result.agentError) {
      this.aiStatusMap.set(convId, {
        conversationId: convId,
        active: false,
        requestedByUserId: result.userMessage.autorUsuarioId,
        requestedByName: result.userMessage.autorNombre,
        error: result.agentError,
      })
    } else if (result.agentMessage) {
      const currentStatus = this.aiStatusMap.get(convId)
      if (currentStatus) {
        this.aiStatusMap.set(convId, { ...currentStatus, active: false, error: null })
      }
    }
    this.emit()
  }

  clearAiError(conversationId: number): void {
    const status = this.aiStatusMap.get(conversationId)
    if (!status || !status.error) return
    this.aiStatusMap.set(conversationId, { ...status, error: null })
    this.emit()
  }

  upsertProposal(proposal: SharedProposal): void {
    if (proposal.projectId !== this.projectId) return
    const timeline = this.timelines.get(proposal.conversationId)
    if (!timeline) return
    let touched = false
    timeline.messages = timeline.messages.map((message) => {
      if (message.id === proposal.messageId || message.proposal?.id === proposal.id) {
        touched = true
        return { ...message, proposal }
      }
      return message
    })
    if (touched) {
      this.emit()
    }
  }

  applyCollaborationEvent(event: CollaborationEvent, ownClientId?: string): boolean {
    if (event.projectId !== this.projectId) return false
    const payload = asRecord(event.payload)
    if (!payload) return false

    switch (event.type) {
      case 'CONVERSATION_CREATED': {
        const summary = parseSummaryPayload(payload.conversation)
        if (!summary) return false
        this.upsertConversation(summary, false)
        return true
      }
      case 'CONVERSATION_UPDATED': {
        const summary = parseSummaryPayload(payload.conversation)
        if (!summary) return false
        this.upsertConversation(summary, false)
        return true
      }
      case 'CONVERSATION_MESSAGE_CREATED': {
        const message = parseMessagePayload(payload.message)
        if (!message) return false
        const timeline = this.ensureTimeline(message.conversationId)
        const alreadyHadId = timeline.messages.some((item) => item.id === message.id)
        this.ingestConfirmedMessage(message)

        const fromOtherClient = !ownClientId || event.clientId !== ownClientId
        const isCurrentlyReading =
          this.panelOpenValue && this.activeConversationIdValue === message.conversationId
        if (!alreadyHadId && fromOtherClient && !isCurrentlyReading) {
          this.unreadMap.set(
            message.conversationId,
            (this.unreadMap.get(message.conversationId) ?? 0) + 1,
          )
        }
        this.emit()
        return true
      }
      case 'CONVERSATION_AI_STATUS': {
        if (typeof payload.conversationId !== 'number' || typeof payload.active !== 'boolean') {
          return false
        }
        const convId = payload.conversationId
        const errorText =
          typeof payload.error === 'string' && payload.error.trim().length > 0
            ? payload.error
            : null
        this.aiStatusMap.set(convId, {
          conversationId: convId,
          active: payload.active,
          requestedByUserId:
            typeof payload.requestedByUserId === 'number' ? payload.requestedByUserId : event.userId,
          requestedByName:
            typeof payload.requestedByName === 'string' ? payload.requestedByName : event.name,
          error: payload.active ? null : errorText,
        })
        this.emit()
        return true
      }
      case 'CONVERSATION_PROPOSAL_UPDATED': {
        const proposal = parseProposalPayload(payload.proposal)
        if (!proposal) return false
        this.upsertProposal(proposal)
        return true
      }
      default:
        return false
    }
  }
}
