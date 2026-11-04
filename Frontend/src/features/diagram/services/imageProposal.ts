/** Image proposals are server-owned additive plans, never general text-agent operations. */
export interface ImageProposal {
  proposalId: string
  baseRevision: number | null
  matched: string[]
  additions: string[]
  conflicts: string[]
  warnings: string[]
  canApply: boolean
}

export function parseImageProposal(raw: unknown): ImageProposal {
  if (!raw || typeof raw !== 'object' || Array.isArray(raw)) throw new Error('Propuesta de imagen invalida.')
  const value = raw as Record<string, unknown>
  const fields = ['proposalId', 'baseRevision', 'matched', 'additions', 'conflicts', 'warnings', 'canApply']
  if (Object.keys(value).some(key => !fields.includes(key)) || fields.some(key => !(key in value))
    || typeof value.proposalId !== 'string' || !/^[a-f0-9-]{36}$/.test(value.proposalId)
    || (value.baseRevision !== null && (!Number.isSafeInteger(value.baseRevision) || Number(value.baseRevision) < 0))
    || typeof value.canApply !== 'boolean') throw new Error('Contrato de propuesta de imagen invalido.')
  for (const field of ['matched', 'additions', 'conflicts', 'warnings']) {
    if (!Array.isArray(value[field]) || value[field].some(item => typeof item !== 'string'))
      throw new Error('Resumen de imagen invalido.')
  }
  const proposal = value as unknown as ImageProposal
  if (proposal.canApply !== (proposal.conflicts.length === 0 && proposal.additions.length > 0))
    throw new Error('Estado de propuesta de imagen inconsistente.')
  return proposal
}

export function imageProposalAcceptanceError(proposal: ImageProposal, serverRevision: number | null,
  localRevision: number, proposedLocalRevision: number, persistedRevision: number, blocked: boolean): string | null {
  if (blocked) return 'No se puede confirmar mientras hay un guardado, conflicto o falta de permisos.'
  if (proposal.baseRevision !== serverRevision || localRevision !== proposedLocalRevision || localRevision !== persistedRevision)
    return 'El diagrama cambio. Cancela esta propuesta y vuelve a analizar la imagen.'
  if (!proposal.canApply || proposal.conflicts.length || !proposal.additions.length)
    return 'La propuesta contiene conflictos o no tiene elementos nuevos.'
  return null
}

function asRecord(value: unknown): Record<string, unknown> | null {
  return value && typeof value === 'object' && !Array.isArray(value)
    ? (value as Record<string, unknown>)
    : null
}

export function resolveAiPromptErrorMessage(error: unknown, isImage = false): string {
  const errObj = asRecord(error)
  const response = asRecord(errObj?.response)
  const status = typeof response?.status === 'number' ? response.status : undefined
  const errorCode = typeof errObj?.code === 'string' ? errObj.code : undefined
  const data = asRecord(response?.data)
  const rawMessage = typeof data?.mensaje === 'string'
    ? data.mensaje.trim()
    : typeof data?.detail === 'string'
      ? data.detail.trim()
      : ''

  if (status === 400) {
    return rawMessage || (isImage ? 'Selecciona una imagen válida.' : 'La instrucción no es válida.')
  }
  if (status === 409) {
    return rawMessage || 'El diagrama cambió o la operación entra en conflicto.'
  }
  if (status === 413) {
    return rawMessage || 'La imagen supera el límite de 5 MB.'
  }
  if (status === 415) {
    return rawMessage || 'Formato de imagen no permitido. Utiliza PNG, JPG o WEBP.'
  }
  if (status === 422) {
    return rawMessage || (isImage
      ? 'No se pudo interpretar la imagen.'
      : 'La IA devolvió un lote incompleto; no se aplicaron cambios.')
  }
  if (status === 429) {
    return rawMessage || 'Gemini alcanzó su límite de solicitudes o cuota. Inténtalo más tarde.'
  }
  if (status === 502) {
    return rawMessage || (isImage
      ? 'No se pudo interpretar la imagen.'
      : 'No se pudo interpretar la instrucción.')
  }
  if (status === 503) {
    return rawMessage || (isImage
      ? 'Gemini no está disponible temporalmente o alcanzó su cuota. Inténtalo de nuevo.'
      : 'El servicio IA no pudo completar la solicitud. Inténtalo de nuevo.')
  }
  if (status === 504 || errorCode === 'ECONNABORTED') {
    return rawMessage || (isImage
      ? 'La interpretación de la imagen excedió el tiempo de espera.'
      : 'El servicio IA no pudo completar la solicitud. Inténtalo de nuevo.')
  }
  if (rawMessage) return rawMessage

  const isAxiosLike = Boolean(errObj?.isAxiosError || response || errObj?.request)
  if (!isAxiosLike && error instanceof Error && error.message.trim()) {
    return error.message.trim()
  }
  return isImage
    ? 'No se pudo interpretar la imagen solicitada.'
    : 'No se pudo interpretar el cambio solicitado.'
}
