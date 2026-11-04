import re

from app.schemas.agent import AgentAskRequest, AgentAskResponse
from app.schemas.data_types import normalize_type_aliases
from app.schemas.diagram import ContextRelationship, DiagramContext, EntityDefinition, InterpretResponse
from app.services.ai_service import AIService
from app.services.diagram_ai_service import _merge_previous_and_repaired
from app.services.llm_json_parser import parse_llm_json_response
from app.services.providers.base import ProviderResponseError
from app.skills.agent.intent import AgentIntent, classify_agent_intent
from app.skills.agent.prompt import build_agent_prompt, build_informational_prompt
from app.skills.agent.suggestions import contains_technical_operation
from app.skills.diagram.completeness import DiagramIncompleteError, _key, validate_completeness
from app.skills.diagram.repair import build_repair_prompt


def _build_agent_diagram_context(request: AgentAskRequest) -> DiagramContext:
    entities = [
        EntityDefinition(name=entity.name, attributes=entity.attributes)
        for entity in request.context.entities
    ]
    existing_names = {_key(entity.name) for entity in entities}
    relationships = [
        ContextRelationship(
            id=relationship.id,
            sourceEntity=relationship.sourceEntity,
            targetEntity=relationship.targetEntity,
            sourceCardinality=relationship.sourceCardinality,
            targetCardinality=relationship.targetCardinality,
            name=relationship.name,
            joinTableName=relationship.joinTableName,
        )
        for relationship in request.context.relationships
    ]
    description = request.context.projectDescription or ""
    ro_match = re.search(r"Elementos UML de solo consulta:\s*([^|]+)", description)
    if ro_match:
        for item in re.finditer(
            r"([A-Za-z0-9_ÁÉÍÓÚÑáéíóúñ -]+?)\s*\((INTERFACE|ENUMERATION|CLASS|ENTITY|ASSOCIATION_CLASS)\)",
            ro_match.group(1),
        ):
            name = item.group(1).strip(" ,;")
            kind = item.group(2)
            if name and _key(name) not in existing_names:
                existing_names.add(_key(name))
                entities.append(EntityDefinition(name=name, kind=kind, attributes=[]))
    rel_match = re.search(r"Relaciones UML estructurales:\s*([^|]+)", description)
    if rel_match:
        for item in re.finditer(
            r"(GENERALIZATION|REALIZATION|DEPENDENCY|AGGREGATION|COMPOSITION|ASSOCIATION)\s+"
            r"([A-Za-z0-9_ÁÉÍÓÚÑáéíóúñ -]+?)\s*->\s*([A-Za-z0-9_ÁÉÍÓÚÑáéíóúñ -]+)",
            rel_match.group(1),
        ):
            kind = item.group(1)
            src = item.group(2).strip(" ,;")
            tgt = item.group(3).strip(" ,;")
            if src and tgt:
                relationships.append(ContextRelationship(
                    sourceEntity=src,
                    targetEntity=tgt,
                    kind=kind,
                ))
    return DiagramContext(
        entities=entities,
        relationships=relationships,
        associations=request.context.associations,
    )


class AgentService:
    def __init__(self, ai: AIService):
        self.ai = ai

    async def ask(self, request: AgentAskRequest) -> AgentAskResponse:
        intent = classify_agent_intent(request.message)
        if intent is not AgentIntent.MODIFICATION:
            answer = (await self.ai.generate(build_informational_prompt(request))).strip()
            if not answer:
                raise ProviderResponseError("El modelo devolvio una respuesta conversacional vacia.")
            if contains_technical_operation(answer):
                raise ProviderResponseError("El modelo devolvio identificadores internos en una consulta.")
            return AgentAskResponse(answer=answer, operations=[])
        prompt = build_agent_prompt(request)
        raw = await self.ai.generate(prompt, AgentAskResponse.model_json_schema())
        try:
            parsed = normalize_type_aliases(parse_llm_json_response(raw))
            response = AgentAskResponse.model_validate(parsed)
        except (ValueError, TypeError):
            raise ProviderResponseError("El modelo devolvio una respuesta textual fuera de contrato.")
        diagram = _build_agent_diagram_context(request)
        previous = InterpretResponse(operations=response.operations)
        try:
            validated = validate_completeness(request.message, diagram, previous)
        except DiagramIncompleteError as exc:
            repair_prompt = build_repair_prompt(request.message, exc.reason, previous, diagram)
            repaired_raw = await self.ai.generate(repair_prompt, InterpretResponse.model_json_schema())
            try:
                repaired_parsed = normalize_type_aliases(parse_llm_json_response(repaired_raw))
                repaired = InterpretResponse.model_validate(repaired_parsed)
            except (ValueError, TypeError):
                raise ProviderResponseError("El modelo devolvio una respuesta textual fuera de contrato.")
            try:
                validated = validate_completeness(request.message, diagram, repaired)
            except DiagramIncompleteError:
                merged = _merge_previous_and_repaired(previous, repaired, request.message, diagram)
                validated = validate_completeness(request.message, diagram, merged)
        answer = response.answer
        if validated.operations:
            count = len(validated.operations)
            answer = (f"Preparé una propuesta con {count} cambio"
                      f"{'s' if count != 1 else ''}. Revísala antes de aplicar.")
        return response.model_copy(update={"answer": answer, "operations": validated.operations})
