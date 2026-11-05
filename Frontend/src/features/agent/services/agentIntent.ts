export type AgentIntent = 'INFORMATIONAL' | 'MODIFICATION' | 'AMBIGUOUS'

function plain(value: string) {
  return value.toLocaleLowerCase('es').normalize('NFD').replace(/[\u0300-\u036f]/g, '')
    .replace(/[¿¡]/g, ' ').replace(/^\s*@logicdraft\b\s*/i, '').trim()
}

/** An authorization decision derived from the user's words, never from model operations. */
export function classifyAgentIntent(message: string): AgentIntent {
  message = String(message).replace(/^\s*(?:\\?\*){1,2}\s*(@logicdraft)\s*(?:\\?\*){1,2}/i, '$1')
  const normalized = plain(String(message))
  if (String(message).replace(/^\s*@logicdraft\b\s*/i, '').trimStart().startsWith('¿') || /^(que|cual|cuales|cuanto|cuantos|cuanta|cuantas|como|por que|que pasaria si|explica(?:me)?|describe|dime|muestra(?:me)?|opinas|lee|leer|lista|listar|enumera|sugiere|sugerir|recomienda|recomendar|analiza|analizar|revisa|revisar|evalua|evaluar)\b/.test(normalized)) {
    return 'INFORMATIONAL'
  }
  if (/^(no|nunca|jamas|evita|evitar)\b|^(no quiero|no necesito)\s+que\b/.test(normalized)) {
    return 'AMBIGUOUS'
  }
  if (/^(podriamos|podria(?:mos)?|tal vez|quizas|quiza)\b|\b(que tal si|seria bueno|convendria)\b/.test(normalized)) {
    return 'AMBIGUOUS'
  }
  if (/^(?:(?:por favor)\s+)?(agrega|agregar|agregame|agregue|anade|anadir|anademe|anada|crea|crear|creame|cree|genera|generar|generame|genere|incluye|incluir|incluya|define|definir|defina|establece|establecer|establezca|marca|marcar|marque|asigna|asignar|asigne|implementa|implementar|implemente|hereda|heredar|herede|extiende|extender|extienda|conecta|conectar|conecte|relaciona|relacionar|relacione|une|unir|convierte|convertir|convierta|transforma|transformar|transforme|edita|editar|edite|modifica|modificar|modifique|actualiza|actualizar|actualice|cambia|cambiar|cambie|renombra|renombrar|renombre|elimina|eliminar|elimine|borra|borrar|borre|quita|quitar|quite|suprime|suprimir|remueve|remover|remueva)\b/.test(normalized)
    || /^(quiero|necesito)\s+que\s+(agregues|anadas|crees|generes|incluyas|definas|establezcas|marques|asignes|implementes|heredes|extiendas|conectes|relaciones|unas|conviertas|transformes|edites|modifiques|actualices|cambies|renombres|elimines|borres|quites|suprimas|remuevas)\b/.test(normalized)) {
    return 'MODIFICATION'
  }
  return String(message).includes('?') ? 'INFORMATIONAL' : 'AMBIGUOUS'
}

export function containsTechnicalOperation(answer: string) {
  return /\b(ADD_ENTITY|DELETE_ENTITY|RENAME_ENTITY|UPDATE_CLASSIFIER|ADD_ATTRIBUTE|DELETE_ATTRIBUTE|RENAME_ATTRIBUTE|CHANGE_ATTRIBUTE_TYPE|SET_ATTRIBUTE_PRIMARY_KEY|SET_ATTRIBUTE_NULLABLE|SET_ATTRIBUTE_VISIBILITY|SET_ATTRIBUTE_STATIC|ADD_METHOD|UPDATE_METHOD|DELETE_METHOD|ADD_METHOD_PARAMETER|UPDATE_METHOD_PARAMETER|DELETE_METHOD_PARAMETER|ADD_ENUM_LITERAL|RENAME_ENUM_LITERAL|DELETE_ENUM_LITERAL|ADD_RELATIONSHIP|DELETE_RELATIONSHIP|UPDATE_RELATIONSHIP|CREATE_ASSOCIATION|DELETE_ASSOCIATION|CONVERT_MANY_TO_MANY_ASSOCIATION)\b/.test(answer)
}
