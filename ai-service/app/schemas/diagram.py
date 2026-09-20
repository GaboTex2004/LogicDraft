from typing import Annotated, Literal
from pydantic import BaseModel, ConfigDict, Field, field_validator, model_serializer, model_validator
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
    name: Name | None = None
    joinTableName: Name | None = None

    @model_serializer(mode="wrap")
    def omit_absent_names(self, serializer):
        return {key: value for key, value in serializer(self).items() if value is not None}


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


class AssociationConversionDefinition(Contract):
    relationshipId: Name
    sourceEntity: Name
    targetEntity: Name
    associationEntityName: Name
    attributes: list[AttributeDefinition] = Field(max_length=100)

    @field_validator("attributes")
    @classmethod
    def no_primary_keys(cls, value: list[AttributeDefinition]) -> list[AttributeDefinition]:
        if any(attribute.primaryKey for attribute in value):
            raise ValueError("association attributes cannot replace the generated primary key")
        names = [attribute.name.casefold() for attribute in value]
        if len(names) != len(set(names)):
            raise ValueError("association attributes must have unique names")
        return value


class ConvertManyToManyAssociation(Contract):
    type: Literal["CONVERT_MANY_TO_MANY_ASSOCIATION"]
    conversion: AssociationConversionDefinition


Operation = Annotated[AddEntity | AddAttribute | AddRelationship | ConvertManyToManyAssociation,
                      Field(discriminator="type")]


class InterpretResponse(Contract):
    operations: list[Operation] = Field(max_length=50)


class ConversionInterpretResponse(Contract):
    """Narrow provider schema used only while interpreting an explicit conversion."""

    operations: list[ConvertManyToManyAssociation] = Field(max_length=1)


class ContextRelationship(RelationshipDefinition):
    id: Name | None = None
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
    associations: list["AssociationContext"] = Field(default_factory=list)


class AssociationContext(Contract):
    entityName: Name
    tableName: Name
    endpointEntityNames: list[Name] = Field(min_length=2, max_length=2)
    structuralRelationshipIds: list[Name] = Field(min_length=2, max_length=2)


InterpretRequest.model_rebuild()
