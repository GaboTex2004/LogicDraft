from app.schemas.agent import AgentAskRequest, AgentAskResponse
from app.schemas.diagram import ContextRelationship, DiagramContext, EntityDefinition, InterpretResponse
from app.services.ai_service import AIService
from app.services.providers.base import ProviderResponseError
from app.skills.agent.intent import AgentIntent, classify_agent_intent
from app.skills.agent.prompt import build_agent_prompt, build_informational_prompt
from app.skills.agent.suggestions import contains_technical_operation
from app.services.llm_json_parser import parse_llm_json_response
from app.skills.diagram.completeness import validate_completeness


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
        raw = await self.ai.generate(build_agent_prompt(request), AgentAskResponse.model_json_schema())
        try:
            response = AgentAskResponse.model_validate(parse_llm_json_response(raw))
        except (ValueError, TypeError):
            raise ProviderResponseError("El modelo devolvio una respuesta textual fuera de contrato.")
        diagram = DiagramContext(
            entities=[EntityDefinition(name=entity.name, attributes=entity.attributes)
                      for entity in request.context.entities],
            relationships=[ContextRelationship(
                id=relationship.id,
                sourceEntity=relationship.sourceEntity,
                targetEntity=relationship.targetEntity,
                sourceCardinality=relationship.sourceCardinality,
                targetCardinality=relationship.targetCardinality,
                name=relationship.name,
                joinTableName=relationship.joinTableName,
            ) for relationship in request.context.relationships],
            associations=request.context.associations,
        )
        validated = validate_completeness(
            request.message, diagram, InterpretResponse(operations=response.operations),
        )
        answer = response.answer
        if validated.operations:
            count = len(validated.operations)
            answer = (f"Preparé una propuesta con {count} cambio"
                      f"{'s' if count != 1 else ''}. Revísala antes de aplicar.")
        return response.model_copy(update={"answer": answer, "operations": validated.operations})
