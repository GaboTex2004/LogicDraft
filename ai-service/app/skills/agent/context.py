from typing import Literal

from app.schemas.agent import AgentAskRequest
from app.schemas.data_types import normalize_data_type
from app.schemas.diagram import (
    AssociationContext, AttributeDefinition, ContextRelationship, DiagramContext,
    EntityDefinition, MethodDefinition,
)


class ContextClassifier(EntityDefinition):
    # ASSOCIATION_CLASS is contextual, not an authorization to ADD_ENTITY of that kind.
    kind: Literal["ENTITY", "CLASS", "INTERFACE", "ENUMERATION", "ASSOCIATION_CLASS"] | None = None


class AgentSemanticContext(DiagramContext):
    entities: list[ContextClassifier]


def _context_data_type(value: str) -> str:
    # Same legacy SQL aliases accepted by DiagramContextMapper in Spring.
    aliases = {"CHARACTER": "String", "UUID": "String", "SMALLINT": "Integer", "SHORT": "Integer",
               "BIGDECIMAL": "Double", "LOCALDATE": "Date", "LOCALDATETIME": "DateTime"}
    return aliases.get(value.upper(), normalize_data_type(value))


def build_diagram_context(request: AgentAskRequest) -> DiagramContext:
    uml = request.context.uml
    if uml is None:
        # Legacy callers remain supported; never recover executable context from prose.
        return DiagramContext(
            entities=[EntityDefinition(name=e.name, attributes=e.attributes) for e in request.context.entities],
            relationships=[ContextRelationship(**{k: v for k, v in r.model_dump().items()
                if k not in {"sourceNodeId", "targetNodeId"} and v is not None}) for r in request.context.relationships],
            associations=request.context.associations,
        )
    names = {node.id: node.data.name for node in uml.nodes}
    entities = []
    associations = []
    for node in uml.nodes:
        data = node.data
        entities.append(ContextClassifier(
            name=data.name, kind=data.kind, abstract=data.abstract,
            attributes=[AttributeDefinition(name=a.name, dataType=_context_data_type(a.type), primaryKey=a.primaryKey,
                nullable=a.nullable if a.nullable is not None else not a.primaryKey,
                visibility=a.visibility, static=a.static) for a in data.attributes],
            methods=[MethodDefinition(**{**m.model_dump(exclude={"id"}, exclude_none=True),
                "parameters": [p.model_dump(exclude={"id"}) for p in m.parameters]}) for m in data.methods],
            literals=data.literals,
        ))
        if data.association:
            association = data.association
            associations.append(AssociationContext(entityName=data.name, tableName=association.tableName,
                endpointEntityNames=[names[e.entityId] for e in association.endpoints],
                structuralRelationshipIds=[e.relationshipId for e in association.endpoints]))
    relationships = []
    for edge in uml.edges:
        data = edge.data.model_dump(exclude_none=True)
        kind = data.pop("relationKind")
        if data.get("sourceCardinality") is not None or kind in {"GENERALIZATION", "REALIZATION", "DEPENDENCY"}:
            data.pop("relationshipType", None)
        relationships.append(ContextRelationship(sourceEntity=names[edge.source], targetEntity=names[edge.target],
                                                id=edge.id, kind=kind, **data))
    return AgentSemanticContext(entities=entities, relationships=relationships, associations=associations)
