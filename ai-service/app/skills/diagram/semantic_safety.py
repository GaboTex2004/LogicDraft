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


DELETE = r"(?:elimina|eliminar|elimine|elimines|borra|borrar|borre|borres|quita|quitar|quite|quites|suprime|suprimir|suprimas)"
CREATE = r"(?:crea|crear|creame|cree|agrega|agregar|anade|genera)"
ACTION = rf"(?:{DELETE}|{CREATE}|renombra|cambia|actualiza|modifica|relaciona|conecta)"
CONVERT = r"(?:convierte|convertir|convierta|transforma|transformar|transforme)"
PREFIX = r"(?:(?:por favor)\s+|(?:quiero|necesito)\s+que\s+)?"
ENTITY = r"(?:(?:la|el|una|un)\s+)?(?:(?:entidad|clase(?:\s+uml)?)\s+)?"
ATTRIBUTE = r"(?:(?:el|un)\s+)?(?:atributo|campo)\s+"


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
    for match in re.finditer(rf"[;]+|\s*,?\s+\by\s+(?={ACTION}\b)|,\s*(?={ACTION}\b)", text):
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
            authorized = len(matches) == 1 and not is_association and allows(ENTITY + named(op.entityName))
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
        elif op.type == "DELETE_RELATIONSHIP":
            rel = op.relationship
            pair = {plain(rel.sourceEntity), plain(rel.targetEntity)}
            matches = [r for r in (diagram.relationships if diagram else [])
                       if {plain(r.sourceEntity), plain(r.targetEntity)} == pair
                       and (rel.name is None or (r.name and plain(r.name) == plain(rel.name)))]
            relation_name = (named(rel.name) + r"\s+") if rel.name else ""
            body = r"(?:(?:la|una)\s+)?relacion\s+" + relation_name + r"entre\s+"
            authorized = len(matches) == 1 and any(
                allows(body + named(a) + r"\s+y\s+" + named(b))
                for a, b in [(rel.sourceEntity, rel.targetEntity), (rel.targetEntity, rel.sourceEntity)])
        elif op.type in {"CREATE_ASSOCIATION", "CONVERT_MANY_TO_MANY_ASSOCIATION"}:
            # Both can consume an existing N:M edge in the server applier.
            ref = op.association if op.type == "CREATE_ASSOCIATION" else op.conversion
            pair = rf"entre\s+{named(ref.sourceEntity)}\s+y\s+{named(ref.targetEntity)}"
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
            # Until kind is supported end-to-end, never downgrade explicit UML
            # types to ENTITY. Legacy generic 'clase' remains compatible.
            unsupported = PREFIX + CREATE + r"\s+(?:(?:una?|la)\s+)?(?:clase\s+uml|class|interfaz|interface|enumeracion|enumeration|clase\s+asociativa)\b"
            if any(re.match(unsupported, clause) for clause in clauses):
                raise SemanticSafetyError("La creación de ese tipo UML aún no está soportada por el contrato del agente; usa el editor.")
        if not authorized:
            raise SemanticSafetyError(
                "Propuesta bloqueada: la eliminación no coincide con un objetivo explícito y único de tu solicitud. "
                "Aclara la clase, atributo o relación que deseas eliminar.")
