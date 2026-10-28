import axios from 'axios'
import { api } from '../../../shared/api/api'
import {
  isForbiddenError,
  isUnauthorizedError,
} from '../../../shared/api/apiError'
import { extractElementLockedRejection } from '../../collaboration/services/elementLocks'
import type {
  ConversationMessagesPageResponse,
  ConversationState,
  ConversationSummary,
  ProjectConversationsListResponse,
  SendConversationMessagePayload,
  SendMessageResultResponse,
  SharedProposal,
} from '../types/agentConversation.types'

export interface ConversationApiErrorInfo {
  status: number | null
  code: string | null
  message: string
  unauthorized: boolean
  forbidden: boolean
  lockedByOwnerName: string | null
}

function asRecord(value: unknown): Record<string, unknown> | null {
  return typeof value === 'object' && value !== null ? (value as Record<string, unknown>) : null
}

export function extractConversationApiError(
  error: unknown,
  fallbackMessage = 'No se pudo completar la operación en la conversación.',
): ConversationApiErrorInfo {
  if (isUnauthorizedError(error)) {
    return {
      status: 401,
      code: 'UNAUTHORIZED',
      message: 'Tu sesión expiró. Inicia sesión nuevamente.',
      unauthorized: true,
      forbidden: false,
      lockedByOwnerName: null,
    }
  }

  const lockRejection = extractElementLockedRejection(error)
  if (lockRejection) {
    return {
      status: 409,
      code: 'ELEMENT_LOCKED',
      message: lockRejection.message,
      unauthorized: false,
      forbidden: false,
      lockedByOwnerName: lockRejection.ownerName,
    }
  }

  if (isForbiddenError(error)) {
    return {
      status: 403,
      code: 'FORBIDDEN',
      message: 'Modo solo lectura (VIEWER): no tienes permisos para modificar conversaciones o propuestas.',
      unauthorized: false,
      forbidden: true,
      lockedByOwnerName: null,
    }
  }

  if (axios.isAxiosError(error)) {
    const status = error.response?.status ?? null
    const body = asRecord(error.response?.data)
    const code = typeof body?.code === 'string' ? body.code : null
    const serverMessage = typeof body?.mensaje === 'string' ? body.mensaje.trim() : ''

    if (status === 503) {
      return {
        status,
        code,
        message: serverMessage || 'El proveedor de IA no está disponible en este momento.',
        unauthorized: false,
        forbidden: false,
        lockedByOwnerName: null,
      }
    }
    if (status === 504) {
      return {
        status,
        code,
        message: serverMessage || 'LogicDraft IA tardó demasiado en responder. Inténtalo de nuevo.',
        unauthorized: false,
        forbidden: false,
        lockedByOwnerName: null,
      }
    }
    if (status === 502) {
      return {
        status,
        code,
        message: serverMessage || 'LogicDraft IA devolvió una respuesta no válida.',
        unauthorized: false,
        forbidden: false,
        lockedByOwnerName: null,
      }
    }

    return {
      status,
      code,
      message: serverMessage || fallbackMessage,
      unauthorized: false,
      forbidden: false,
      lockedByOwnerName: null,
    }
  }

  return {
    status: null,
    code: null,
    message: error instanceof Error && error.message ? error.message : fallbackMessage,
    unauthorized: false,
    forbidden: false,
    lockedByOwnerName: null,
  }
}

export async function listarConversacionesProyecto(
  projectId: number,
  includeArchived = false,
): Promise<ProjectConversationsListResponse> {
  const response = await api.get<ProjectConversationsListResponse>(
    `/proyectos/${projectId}/conversaciones`,
    { params: { includeArchived } },
  )
  return response.data
}

export async function crearConversacionProyecto(
  projectId: number,
  payload: { titulo: string; clientId: string },
): Promise<ConversationSummary> {
  const response = await api.post<ConversationSummary>(
    `/proyectos/${projectId}/conversaciones`,
    payload,
  )
  return response.data
}

export async function actualizarConversacionProyecto(
  projectId: number,
  conversationId: number,
  payload: { titulo?: string; estado?: ConversationState; clientId: string },
): Promise<ConversationSummary> {
  const response = await api.patch<ConversationSummary>(
    `/proyectos/${projectId}/conversaciones/${conversationId}`,
    payload,
  )
  return response.data
}

export async function listarMensajesConversacion(
  projectId: number,
  conversationId: number,
  params?: { beforeId?: number | null; limit?: number },
): Promise<ConversationMessagesPageResponse> {
  const query: Record<string, number> = {}
  if (typeof params?.beforeId === 'number' && params.beforeId > 0) {
    query.beforeId = params.beforeId
  }
  if (typeof params?.limit === 'number' && params.limit > 0) {
    query.limit = params.limit
  }
  const response = await api.get<ConversationMessagesPageResponse>(
    `/proyectos/${projectId}/conversaciones/${conversationId}/mensajes`,
    { params: query },
  )
  return response.data
}

export async function enviarMensajeConversacion(
  projectId: number,
  conversationId: number,
  payload: SendConversationMessagePayload,
): Promise<SendMessageResultResponse> {
  const response = await api.post<SendMessageResultResponse>(
    `/proyectos/${projectId}/conversaciones/${conversationId}/mensajes`,
    payload,
    { timeout: 600_000 },
  )
  return response.data
}

export async function aceptarPropuestaConversacion(
  projectId: number,
  conversationId: number,
  proposalId: number,
  payload: { clientId: string; comentario?: string },
): Promise<SharedProposal> {
  const response = await api.post<SharedProposal>(
    `/proyectos/${projectId}/conversaciones/${conversationId}/propuestas/${proposalId}/aceptar`,
    payload,
  )
  return response.data
}

export async function rechazarPropuestaConversacion(
  projectId: number,
  conversationId: number,
  proposalId: number,
  payload: { clientId: string; comentario?: string },
): Promise<SharedProposal> {
  const response = await api.post<SharedProposal>(
    `/proyectos/${projectId}/conversaciones/${conversationId}/propuestas/${proposalId}/rechazar`,
    payload,
  )
  return response.data
}
