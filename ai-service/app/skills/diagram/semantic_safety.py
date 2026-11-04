"""Fail-closed destructive authorization, independent of the LLM.

Bounded command grammar, not a general Spanish parser. Unknown/conditional
targets require clarification; never sanitize an unsafe batch into a partial
proposal. Keep in sync with Java's SemanticOperationValidator.
"""
import re
import unicodedata

from app.schemas.diagram import DiagramContext, InterpretResponse
from app.services.providers.base import ProviderResponseError
from app.skills.agent.intent import AgentIntent, classify_agent_intent


class SemanticSafetyError(ProviderResponseError):
    """Not repairable: never feed a rejected destructive plan back to the model."""


DELETE = r"(?:elimina|eliminar|elimine|elimines|borra|borrar|borre|borres|quita|quitar|quite|quites|suprime|suprimir|suprimas|remueve|remover|remueva)"
CREATE = r"(?:crea|crear|creame|cree|agrega|agregar|agregame|anade|anadir|genera|generar|incluye|incluir|define|definir)"
UPDATE = r"(?:renombra|renombrar|cambia|cambiar|actualiza|actualizar|modifica|modificar|edita|editar|relaciona|relacionar|conecta|conectar|marca|marcar|establece|establecer|asigna|asignar|convierte|convertir|transforma|transformar|implementa|implementar|hereda|heredar|extiende|extender)"
ACTION = rf"(?:{DELETE}|{CREATE}|{UPDATE})"
CONVERT = r"(?:convierte|convertir|convierta|transforma|transformar|transforme)"
PREFIX = r"(?:(?:por favor)\s+|(?:quiero|necesito)\s+que\s+)?"
CLASSIFIER = r"(?:(?:la|el|una|un)\s+)?(?:(?:entidad|clase(?:\s+uml)?|interfaz|interface|enumeracion|enumeration|enum)\s+)?"
ENTITY = r"(?:(?:la|el|una|un)\s+)?(?:(?:entidad|clase(?:\s+uml|\s+asociativa)?|asociacion)\s+)?"
ATTRIBUTE = r"(?:(?:el|un)\s+)?(?:atributo|campo|propiedad)\s+"
METHOD = r"(?:(?:el|un)\s+)?(?:metodo|operacion|funcion)\s+"
PARAMETER = r"(?:(?:el|un)\s+)?parametro\s+"
LITERAL = r"(?:(?:el|un)\s+)?(?:literal|valor)\s+"


def plain(value: str) -> str:
    value = unicodedata.normalize("NFD", value.lower())
    return " ".join("".join(c for c in value if unicodedata.category(c) != "Mn").split())


def named(value: str) -> str:
    escaped = re.escape(plain(value))
    return rf'(?:{escaped}|"{escaped}"|\'{escaped}\')'


def statements(message: str) -> list[str]:
    message = re.sub(r"^\s*@logicdraft\b\s*", "", message, flags=re.I)
    text = plain(message)
    quoted = set()
    for match in re.finditer(r"\"[^\"]*\"|'[^']*'", text):
        quoted.update(range(match.start(), match.end()))
    parts, start = [], 0
    for match in re.finditer(rf"[;]+|\s*,?\s+\by\s+(?={PREFIX}{ACTION}\b)|,\s*(?={PREFIX}{ACTION}\b)", text):
        if match.start() not in quoted:
            parts.append(text[start:match.start()].strip(" .!"))
            start = match.end()
    return parts + [text[start:].strip(" .!")]


def validate_semantic_safety(prompt: str, diagram: DiagramContext | None,
                             response: InterpretResponse) -> None:
    if not response.operations:
        return
    text = re.sub(r"^\s*@logicdraft\b\s*", "", prompt, flags=re.I)
    if classify_agent_intent(text) is AgentIntent.INFORMATIONAL:
        raise SemanticSafetyError("Una consulta no autoriza cambios. Aclara la modificación solicitada.")
    clauses = statements(text)

    def allows(body: str) -> bool:
        return any(re.fullmatch(PREFIX + DELETE + r"\s+" + body, clause) for clause in clauses)

    entities = diagram.entities if diagram else []
    associations = diagram.associations if diagram else []
    for op in response.operations:
        if (op.type.startswith("DELETE_") or op.type in {"CREATE_ASSOCIATION", "CONVERT_MANY_TO_MANY_ASSOCIATION"}) and (
                classify_agent_intent(text) is not AgentIntent.MODIFICATION
                or re.search(r"\b(?:si|cuando|salvo|excepto|a menos que|no|nunca|jamas|evita)\b", plain(text))):
            raise SemanticSafetyError("La eliminación es condicional o ambigua. Aclara el objetivo y la acción.")
        authorized = True
        if op.type == "DELETE_ENTITY":
            matches = [e for e in entities if plain(e.name) == plain(op.entityName)]
            is_association = any(plain(a.entityName) == plain(op.entityName) for a in associations)
            authorized = len(matches) == 1 and not is_association and allows(CLASSIFIER + named(op.entityName))
        elif op.type == "DELETE_ASSOCIATION":
            matches = [a for a in associations if plain(a.entityName) == plain(op.associationEntityName)]
            authorized = len(matches) == 1 and allows(
                r"(?:(?:la|una)\s+)?(?:asociacion|(?:clase|entidad)\s+asociativa)\s+"
                + named(op.associationEntityName))
        elif op.type == "DELETE_ATTRIBUTE":
            matches = [e for e in entities if plain(e.name) == plain(op.entityName)]
            attrs = [a for e in matches for a in e.attributes if plain(a.name) == plain(op.attributeName)]
            entity, attr = ENTITY + named(op.entityName), named(op.attributeName)
            authorized = len(matches) == 1 and len(attrs) == 1 and (
                allows(ATTRIBUTE + attr + r"\s+(?:de|en)\s+" + entity)
                or allows(r"de\s+" + entity + r"\s+" + ATTRIBUTE + attr))
        elif op.type == "DELETE_METHOD":
            matches = [e for e in entities if plain(e.name) == plain(op.entityName)]
            method_target = named(op.methodName) + r"(?:\s*\([^)]*\))?"
            target_classifier = CLASSIFIER + named(op.entityName)
            authorized = len(matches) == 1 and (
                allows(METHOD + method_target + r"\s+(?:de|en)\s+" + target_classifier)
                or allows(r"de\s+" + target_classifier + r"\s+" + METHOD + method_target))
        elif op.type == "DELETE_METHOD_PARAMETER":
            matches = [e for e in entities if plain(e.name) == plain(op.entityName)]
            param_target = named(op.parameterName)
            method_target = r"(?:(?:del|de\s+el|en\s+el)\s+)?(?:metodo\s+)?" + named(op.methodName) + r"(?:\s*\([^)]*\))?"
            target_classifier = rf"(?:\s+(?:de|en)\s+{CLASSIFIER}{named(op.entityName)})?"
            authorized = len(matches) == 1 and allows(PARAMETER + param_target + r"\s+" + method_target + target_classifier)
        elif op.type == "DELETE_ENUM_LITERAL":
            matches = [e for e in entities if plain(e.name) == plain(op.entityName)]
            lit_target = named(op.literal)
            target_classifier = CLASSIFIER + named(op.entityName)
            authorized = len(matches) == 1 and (
                allows(LITERAL + lit_target + r"\s+(?:de|en)\s+" + target_classifier)
                or allows(r"de\s+" + target_classifier + r"\s+" + LITERAL + lit_target))
        elif op.type == "DELETE_RELATIONSHIP":
            rel = op.relationship
            pair = {plain(rel.sourceEntity), plain(rel.targetEntity)}
            matches = [r for r in (diagram.relationships if diagram else [])
                       if {plain(r.sourceEntity), plain(r.targetEntity)} == pair
                       and (rel.name is None or (r.name and plain(r.name) == plain(rel.name)))
                       and (rel.kind is None or r.kind is None or plain(r.kind) == plain(rel.kind))]
            relation_name = (named(rel.name) + r"\s+") if rel.name else ""
            body = (
                r"(?:(?:la|una)\s+)?(?:relacion|asociacion|herencia|generalizacion|realizacion|"
                r"implementacion|agregacion|composicion|dependencia)"
                r"(?:\s+de\s+(?:herencia|generalizacion|realizacion|implementacion|agregacion|composicion|dependencia))?\s+"
                + relation_name + r"(?:entre|de)\s+"
            )
            sep = r"(?:\s+y\s+|\s+a\s+|\s+hacia\s+|\s+con\s+)"
            authorized = len(matches) == 1 and any(
                allows(body + CLASSIFIER + named(a) + sep + CLASSIFIER + named(b))
                for a, b in [(rel.sourceEntity, rel.targetEntity), (rel.targetEntity, rel.sourceEntity)])
        elif op.type in {"CREATE_ASSOCIATION", "CONVERT_MANY_TO_MANY_ASSOCIATION"}:
            # Both can consume an existing N:M edge in the server applier.
            ref = op.association if op.type == "CREATE_ASSOCIATION" else op.conversion
            pair = rf"entre\s+{CLASSIFIER}{named(ref.sourceEntity)}\s+y\s+{CLASSIFIER}{named(ref.targetEntity)}"
            association = r"(?:(?:una|la)\s+)?(?:(?:clase|entidad)\s+asociativa\s+)?(?:llamada\s+)?" + named(ref.associationEntityName)
            if op.type == "CREATE_ASSOCIATION":
                explicit_association = r"(?:(?:una|la)\s+)?(?:asociacion|(?:clase|entidad)\s+asociativa)\s+(?:llamada\s+)?" + named(ref.associationEntityName)
                command = PREFIX + CREATE + r"\s+" + explicit_association + r"\s+" + pair
            else:
                candidates = [r for r in (diagram.relationships if diagram else [])
                              if r.sourceCardinality in {"ZERO_MANY", "ONE_MANY"}
                              and r.targetCardinality in {"ZERO_MANY", "ONE_MANY"}]
                scoped = rf"(?:\s+{pair})?" if len(candidates) == 1 else rf"\s+{pair}"
                command = PREFIX + CONVERT + r"\s+(?:la\s+)?(?:relacion\s+)?n\s*:\s*m" + scoped + r"\s+en\s+" + association
            authorized = any(re.fullmatch(command + r"(?:,?\s+con\s+.+)?", c) for c in clauses)
        elif op.type == "ADD_ENTITY":
            actual_kind = op.entity.kind or "ENTITY"
            if actual_kind == "ENTITY":
                unsupported = PREFIX + CREATE + r"\s+(?:(?:una?|la)\s+)?(?:clase\s+uml|class|interfaz|interface|enumeracion|enumeration|enum|clase\s+asociativa)\b"
                if any(re.match(unsupported, clause) for clause in clauses):
                    raise SemanticSafetyError("La creación de ese tipo UML aún no está soportada por el contrato del agente; usa el editor.")
        if not authorized:
            raise SemanticSafetyError(
                "Propuesta bloqueada: la eliminación no coincide con un objetivo explícito y único de tu solicitud. "
                "Aclara la clase, atributo o relación que deseas eliminar.")
