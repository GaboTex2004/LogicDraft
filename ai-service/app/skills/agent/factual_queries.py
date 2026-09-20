import re
import unicodedata

from app.schemas.agent import AgentContext, AgentEntity, AgentRelationship


CARDINALITY_LABELS = {
    "ZERO_ONE": "0..1", "ONE_ONE": "1..1", "ZERO_MANY": "0..N", "ONE_MANY": "1..N",
}


def _plain(value: str) -> str:
    normalized = unicodedata.normalize("NFD", value.casefold())
    return " ".join("".join(char for char in normalized if unicodedata.category(char) != "Mn").split())


def _mentioned_entity(question: str, context: AgentContext) -> AgentEntity | None:
    plain_question = _plain(question)
    matches = [entity for entity in context.entities if _plain(entity.name) in plain_question]
    if matches:
        return max(matches, key=lambda entity: len(entity.name))
    if re.search(r"\b(esta|seleccionada|seleccionado|actual)\b", plain_question):
        return next((entity for entity in context.entities if entity.id == context.selectedNodeId), None)
    return None


def _relationship_description(entity: AgentEntity, relation: AgentRelationship) -> str:
    if relation.sourceNodeId == entity.id:
        other, own_card, other_card = relation.targetEntity, relation.sourceCardinality, relation.targetCardinality
    else:
        other, own_card, other_card = relation.sourceEntity, relation.targetCardinality, relation.sourceCardinality
    return f"{other} ({CARDINALITY_LABELS[own_card]} ↔ {CARDINALITY_LABELS[other_card]})"


def answer_factual_query(question: str, context: AgentContext) -> str | None:
    """Answer supported factual questions exclusively from the authorized context."""
    plain = _plain(question)
    entity = _mentioned_entity(question, context)

    if re.search(r"\b(cuantas|cantidad|numero)\b.*\b(entidades|clases)\b", plain) or re.search(
            r"\b(entidades|clases)\b.*\b(cuantas|cantidad|numero)\b", plain):
        count = len(context.entities)
        return f"Actualmente tienes {count} entidad{'es' if count != 1 else ''}."

    if re.search(r"\b(que|cuales|nombres? de (?:las )?)\b.*\b(entidades|clases)\b", plain):
        if not context.entities:
            return "Actualmente no tienes entidades en el diagrama."
        return "Actualmente tienes estas entidades: " + ", ".join(item.name for item in context.entities) + "."

    if re.search(r"\b(cuantas|cantidad|numero)\b.*\brelaciones\b", plain) or re.search(
            r"\brelaciones\b.*\b(cuantas|cantidad|numero)\b", plain):
        count = len(context.relationships)
        return f"Actualmente tienes {count} relación{'es' if count != 1 else ''}."

    if "entidad seleccionada" in plain or "clase seleccionada" in plain:
        selected = next((item for item in context.entities if item.id == context.selectedNodeId), None)
        return (f"La entidad seleccionada es {selected.name}." if selected
                else "No hay una entidad seleccionada en el contexto actual.")

    if entity and re.search(r"\b(primary key|clave primaria|llave primaria|pk)\b", plain):
        keys = [attribute.name for attribute in entity.attributes if attribute.primaryKey]
        return (f"La primary key de {entity.name} es {keys[0]}." if len(keys) == 1
                else f"Las primary keys de {entity.name} son: {', '.join(keys)}." if keys
                else f"{entity.name} no tiene una primary key definida.")

    if entity and re.search(r"\b(cuantos|cantidad|numero)\b.*\batributos\b", plain):
        count = len(entity.attributes)
        return f"{entity.name} tiene {count} atributo{'s' if count != 1 else ''}."

    if re.search(r"\b(cuantos|cantidad|numero)\b.*\batributos\b", plain):
        count = sum(len(item.attributes) for item in context.entities)
        return f"El diagrama tiene {count} atributo{'s' if count != 1 else ''} en total."

    if entity and re.search(r"\b(atributos|campos|propiedades)\b", plain):
        if not entity.attributes:
            return f"{entity.name} no tiene atributos definidos."
        details = ", ".join(
            f"{attribute.name} ({attribute.dataType}{', PK' if attribute.primaryKey else ''})"
            for attribute in entity.attributes
        )
        return f"{entity.name} tiene estos atributos: {details}."

    if entity and (re.search(r"\b(relacionada|relacionado|relaciones|conecta|conectada|conectado)\b", plain)
                   or "cardinalidad" in plain):
        relations = [relation for relation in context.relationships
                     if relation.sourceNodeId == entity.id or relation.targetNodeId == entity.id]
        if not relations:
            return f"{entity.name} no tiene relaciones definidas."
        descriptions = [_relationship_description(entity, relation) for relation in relations]
        return f"{entity.name} está relacionada con: {', '.join(descriptions)}."

    return None
