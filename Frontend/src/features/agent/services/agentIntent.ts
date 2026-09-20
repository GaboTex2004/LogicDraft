export type AgentIntent = 'INFORMATIONAL' | 'MODIFICATION' | 'AMBIGUOUS'

function plain(value: string) {
  return value.toLocaleLowerCase('es').normalize('NFD').replace(/[\u0300-\u036f]/g, '')
    .replace(/[¿¡]/g, ' ').trim()
}

/** An authorization decision derived from the user's words, never from model operations. */
export function classifyAgentIntent(message: string): AgentIntent {
  const normalized = plain(message)
  if (message.includes('?') || /^(que|cual|cuales|como|por que|explica(?:me)?|describe|dime|muestra|opinas)\b/.test(normalized)) {
    return 'INFORMATIONAL'
  }
  if (/^(podriamos|podria(?:mos)?|tal vez|quizas|quiza)\b|\b(que tal si|seria bueno|convendria)\b/.test(normalized)) {
    return 'AMBIGUOUS'
  }
  if (/^(?:(?:por favor)\s+)?(agrega|anade|crea|conecta|relaciona|convierte|transforma)\b/.test(normalized)
    || /^(quiero|necesito)\s+que\s+(agregues|anadas|crees|conectes|relaciones|conviertas|transformes)\b/.test(normalized)) {
    return 'MODIFICATION'
  }
  return 'AMBIGUOUS'
}

export function containsTechnicalOperation(answer: string) {
  return /\b(ADD_ENTITY|ADD_ATTRIBUTE|ADD_RELATIONSHIP|CONVERT_MANY_TO_MANY_ASSOCIATION)\b/.test(answer)
}
