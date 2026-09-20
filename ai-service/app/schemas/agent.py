from datetime import datetime
from typing import Literal
from pydantic import BaseModel, ConfigDict, Field, field_validator
from app.schemas.diagram import AssociationContext, AttributeDefinition, DiagramCardinality, Name, Operation


class AgentContract(BaseModel):
    model_config = ConfigDict(extra="forbid", strict=True)


class AgentEvent(AgentContract):
    type: Literal[
        "PROJECT_OPENED", "DIAGRAM_OPENED", "NODE_CREATED", "NODE_SELECTED",
        "NODE_UPDATED", "NODE_DELETED", "EDGE_CREATED", "EDGE_UPDATED",
        "EDGE_DELETED", "DIAGRAM_SAVED", "SAVE_FAILED", "AI_REQUESTED",
    ]
    nodeId: str | None = Field(default=None, max_length=100)
    edgeId: str | None = Field(default=None, max_length=100)
    timestamp: datetime

    @field_validator("timestamp", mode="before")
    @classmethod
    def parse_wire_timestamp(cls, value: object) -> object:
        if not isinstance(value, str):
            return value
        parsed = datetime.fromisoformat(value.replace("Z", "+00:00"))
        if parsed.tzinfo is None:
            raise ValueError("timestamp must include timezone")
        return parsed


class AgentEntity(AgentContract):
    id: str = Field(min_length=1, max_length=100)
    name: Name
    attributes: list[AttributeDefinition] = Field(max_length=100)


class AgentRelationship(AgentContract):
    id: str | None = Field(default=None, max_length=100)
    sourceNodeId: str = Field(min_length=1, max_length=100)
    targetNodeId: str = Field(min_length=1, max_length=100)
    sourceEntity: Name
    targetEntity: Name
    sourceCardinality: DiagramCardinality
    targetCardinality: DiagramCardinality
    name: Name | None = None
    joinTableName: Name | None = None


class AgentContext(AgentContract):
    projectId: int = Field(gt=0)
    projectName: Name
    projectDescription: str | None = Field(default=None, max_length=500)
    diagramId: int | None = Field(default=None, gt=0)
    selectedNodeId: str | None = Field(default=None, max_length=100)
    selectedEdgeId: str | None = Field(default=None, max_length=100)
    entities: list[AgentEntity] = Field(max_length=500)
    relationships: list[AgentRelationship] = Field(max_length=1000)
    associations: list[AssociationContext] = Field(default_factory=list, max_length=500)
    recentEvents: list[AgentEvent] = Field(max_length=25)


class AgentConversationMessage(AgentContract):
    role: Literal["user", "agent"]
    text: str = Field(min_length=1, max_length=4000)


class AgentAskRequest(AgentContract):
    message: str = Field(min_length=1, max_length=4000)
    context: AgentContext
    conversation: list[AgentConversationMessage] = Field(default_factory=list, max_length=10)

    @field_validator("message")
    @classmethod
    def nonblank(cls, value: str) -> str:
        if not value.strip():
            raise ValueError("message must not be blank")
        return value.strip()


class AgentAskResponse(AgentContract):
    answer: str = Field(min_length=1, max_length=20000)
    operations: list[Operation] = Field(default_factory=list, max_length=50)
