from typing import Annotated, Literal
from pydantic import BaseModel, ConfigDict, Field, field_validator, model_validator
from app.schemas.data_types import normalize_data_type


class Contract(BaseModel):
    model_config = ConfigDict(extra="forbid", strict=True)


Name = Annotated[str, Field(min_length=1, max_length=100, pattern=r"^[^\s].*[^\s]$|^[^\s]$")]
DataType = Literal["String", "Long", "Integer", "Double", "Boolean", "Date", "DateTime"]


class InterpretRequest(Contract):
    prompt: str = Field(min_length=1, max_length=10000)
    diagram: "DiagramContext | None" = None

    @field_validator("prompt")
    @classmethod
    def nonblank(cls, value: str) -> str:
        if not value.strip():
            raise ValueError("prompt must not be blank")
        return value.strip()


class AttributeDefinition(Contract):
    name: Name
    dataType: DataType
    primaryKey: bool = False
    nullable: bool = True

    @field_validator("dataType", mode="before")
    @classmethod
    def normalize(cls, value: object) -> object:
        return normalize_data_type(value)


class EntityDefinition(Contract):
    name: Name
    attributes: list[AttributeDefinition] = Field(max_length=100)


DiagramCardinality = Literal['ZERO_ONE', 'ONE_ONE', 'ZERO_MANY', 'ONE_MANY']


class RelationshipDefinition(Contract):
    sourceEntity: Name
    targetEntity: Name
    sourceCardinality: DiagramCardinality
    targetCardinality: DiagramCardinality


class AddEntity(Contract):
    type: Literal["ADD_ENTITY"]
    entity: EntityDefinition


class AddAttribute(Contract):
    type: Literal["ADD_ATTRIBUTE"]
    entityName: Name
    attribute: AttributeDefinition


class AddRelationship(Contract):
    type: Literal["ADD_RELATIONSHIP"]
    relationship: RelationshipDefinition


Operation = Annotated[AddEntity | AddAttribute | AddRelationship, Field(discriminator="type")]


class InterpretResponse(Contract):
    operations: list[Operation] = Field(max_length=50)


class ContextRelationship(RelationshipDefinition):
    @model_validator(mode='before')
    @classmethod
    def legacy_context(cls, value: object) -> object:
        if not isinstance(value, dict) or 'sourceCardinality' in value or 'targetCardinality' in value:
            return value
        legacy = {'ONE_TO_ONE': ('ONE_ONE', 'ONE_ONE'), 'ONE_TO_MANY': ('ONE_ONE', 'ZERO_MANY'),
                  'MANY_TO_ONE': ('ZERO_MANY', 'ONE_ONE'), 'MANY_TO_MANY': ('ZERO_MANY', 'ZERO_MANY'),
                  None: ('ONE_ONE', 'ONE_ONE')}
        kind = value.get('relationshipType')
        if not (kind is None or isinstance(kind, str)) or kind not in legacy:
            return value
        source, target = legacy[kind]
        result = {k: v for k, v in value.items() if k != 'relationshipType'}
        return {**result, 'sourceCardinality': source, 'targetCardinality': target}


class DiagramContext(Contract):
    entities: list[EntityDefinition]
    relationships: list[ContextRelationship]


InterpretRequest.model_rebuild()
