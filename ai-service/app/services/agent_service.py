import logging

from app.schemas.agent import AgentAskRequest, AgentAskResponse
from app.schemas.data_types import normalize_type_aliases
from app.schemas.diagram import DiagramContext, InterpretResponse
from app.services.ai_service import AIService
from app.services.llm_json_parser import parse_llm_json_response
from app.services.providers.base import ProviderResponseError
from app.skills.agent.context import build_diagram_context
from app.skills.agent.execution_plan import action_coverage_error, matches_plan, plan_execution
from app.skills.agent.intent import AgentIntent, classify_agent_intent
from app.skills.agent.prompt import build_agent_prompt, build_informational_prompt
from app.skills.agent.suggestions import contains_technical_operation
from app.skills.diagram.completeness import DiagramIncompleteError, validate_completeness
from app.skills.diagram.semantic_safety import validate_semantic_safety

logger = logging.getLogger(__name__)


def _build_agent_diagram_context(request: AgentAskRequest) -> DiagramContext:
    return build_diagram_context(request)


def _clarification(reason: str, attempts: int = 0) -> AgentAskResponse:
    return AgentAskResponse(answer="No se preparo ningun cambio. " + reason, operations=[],
                           intent="NEEDS_CLARIFICATION", outcome="NEEDS_CLARIFICATION", repairAttempts=attempts)


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
            wire_intent = "INFORMATIONAL" if intent is AgentIntent.INFORMATIONAL else "NEEDS_CLARIFICATION"
            return AgentAskResponse(answer=answer, operations=[], intent=wire_intent, outcome=wire_intent)
        diagram = _build_agent_diagram_context(request)
        plan = plan_execution(request.message, diagram)
        if plan is not None and plan.reason:
            return _clarification(plan.reason)
        if plan is not None and not plan.operations:
            return AgentAskResponse(answer="La accion ya esta satisfecha en el diagrama; no se requieren cambios.",
                                    operations=[], intent="MUTATION", outcome="ALREADY_SATISFIED")
        
        # Si el planificador ya resolvio la orden, no pedirle
        # a Ollama que vuelva a generar las mismas operaciones.
        if plan is not None:
            resolved = InterpretResponse.model_validate({
                "operations": list(plan.operations)
            })

            # Mantener la autorizacion semantica, especialmente
            # para eliminaciones y operaciones destructivas.
            validate_semantic_safety(request.message, diagram, resolved)

            # Evitar propuestas incompletas.
            missing = action_coverage_error(request.message, resolved)
            if missing:
                return _clarification(missing)

            count = len(resolved.operations)

            logger.info(
                "Agent execution planner=deterministic outcome=PROPOSAL operations=%d",
                count,
            )

            return AgentAskResponse(
                answer=(
                    f"Propuesta de modificaciones: {count} operaciones. "
                    "Revisa cada cambio y confirma para aplicarlo."
                ),
                operations=resolved.operations,
                intent="MUTATION",
                outcome="PROPOSAL",
                repairAttempts=0,
            )

        prompt = build_agent_prompt(request, list(plan.operations) if plan is not None else None)
        schema = AgentAskResponse.model_json_schema()
        if plan is not None:
            # Reuse existing schemas, but expose only operation families required by the
            # human's deterministic plan (e.g. DELETE_ATTRIBUTE, never DELETE_ENTITY).
            kinds = {operation["type"] for operation in plan.operations}
            definitions = schema.get("$defs", {})
            allowed = [{"$ref": f"#/$defs/{name}"} for name, definition in definitions.items()
                       if definition.get("properties", {}).get("type", {}).get("const") in kinds]
            schema["properties"]["operations"]["items"] = {"oneOf": allowed}
            schema["properties"]["operations"]["maxItems"] = len(plan.operations)
        reason = "Especifica los elementos y la modificacion requerida."
        for attempt in range(2):
            raw = await self.ai.generate(prompt, schema)
            try:
                parsed = normalize_type_aliases(parse_llm_json_response(raw))
                # Repair may use the existing operations-only schema; all other fields stay strict.
                response = (InterpretResponse.model_validate(parsed) if attempt and set(parsed) == {"operations"}
                            else InterpretResponse(operations=AgentAskResponse.model_validate(parsed).operations))
            except (ValueError, TypeError):
                reason = "El proveedor no produjo un lote JSON valido."
            else:
                # Unsafe destructive output is NEVER repaired or silently sanitized.
                validate_semantic_safety(request.message, diagram, response)
                try:
                    if not response.operations:
                        raise DiagramIncompleteError("La orden solicita cambios, pero el proveedor no propuso ninguna operacion.", response)
                    if plan is not None:
                        if not matches_plan(response, plan):
                            raise DiagramIncompleteError("El lote no coincide con todas las acciones, nombres o cardinalidades solicitadas.", response)
                        validated = response
                    else:
                        validated = validate_completeness(request.message, diagram, response)
                        if validated.model_dump() != response.model_dump():
                            raise DiagramIncompleteError("El lote contenia cambios no solicitados; se rechazo completo.", response)
                        missing = action_coverage_error(request.message, validated)
                        if missing: raise DiagramIncompleteError(missing, response)
                    if not validated.operations:
                        raise DiagramIncompleteError("No se obtuvo una propuesta completa y verificable.", response)
                except DiagramIncompleteError as error:
                    reason = error.reason
                else:
                    count = len(validated.operations)
                    logger.info("Agent execution outcome=PROPOSAL operations=%d repairAttempts=%d", count, attempt)
                    return AgentAskResponse(answer=f"Propuesta de modificaciones: {count} operaciones. Revisa cada cambio y confirma para aplicarlo.",
                                            operations=validated.operations, intent="MUTATION", outcome="PROPOSAL", repairAttempts=attempt)
            logger.info("Agent execution validation failed attempt=%d", attempt)
            if attempt == 0:
                prompt += ("\nREPARACION 1/1: el lote anterior fue rechazado. " + reason
                           + "\nDevuelve el lote COMPLETO con answer y operations, no explicaciones manuales. "
                           "Si falta informacion, no inventes nombres, elementos ni cardinalidades.")
        logger.info("Agent execution outcome=NEEDS_CLARIFICATION repairAttempts=1")
        return _clarification(reason, 1)
