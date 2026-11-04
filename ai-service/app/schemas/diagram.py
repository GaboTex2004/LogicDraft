from typing import Annotated, Literal
from pydantic import BaseModel, ConfigDict, Field, field_validator, model_serializer, model_validator
from app.schemas.data_types import normalize_data_type


class Contract(BaseModel):
    model_config = ConfigDict(extra="forbid", strict=True)


Name = Annotated[str, Field(min_length=1, max_length=100, pattern=r"^[^\s].*[^\s]$|^[^\s]$")]
DataType = Literal["String", "Long", "Integer", "Double", "Boolean", "Date", "DateTime"]
ClassifierKind = Literal["ENTITY", "CLASS", "INTERFACE", "ENUMERATION"]
RelationKind = Literal[
    "ASSOCIATION", "GENERALIZATION", "REALIZATION", "AGGREGATION", "COMPOSITION", "DEPENDENCY"
]
UmlVisibility = Literal["public", "private", "protected", "package"]
AggregationEnd = Literal["SOURCE", "TARGET"]
MULTIPLICATIVE_RELATION_KINDS = {"ASSOCIATION", "AGGREGATION", "COMPOSITION"}


class InterpretRequest(Contract):
    prompt: str = Field(min_length=1, max_length=10000)
    diagram: "DiagramContext | None" = None
    selection: "DiagramSelection | None" = None

    @field_validator("prompt")
    @classmethod
    def nonblank(cls, value: str) -> str:
        if not value.strip():
            raise ValueError("prompt must not be blank")
        return value.strip()


def normalize_visibility(value: object) -> object:
    if isinstance(value, str) and value.strip().lower() in {"public", "private", "protected", "package"}:
        return value.strip().lower()
    return value


class ParameterDefinition(Contract):
    name: Name
    type: Name = "String"

    @model_validator(mode="before")
    @classmethod
    def accept_data_type_alias(cls, value: object) -> object:
        if isinstance(value, dict) and "dataType" in value and "type" not in value:
            copied = dict(value)
            copied["type"] = copied.pop("dataType")
            return copied
        return value


class MethodDefinition(Contract):
    name: Name
    returnType: Name = "void"
    visibility: UmlVisibility = "public"
    static: bool | None = None
    abstract: bool | None = None
    parameters: list[ParameterDefinition] = Field(default_factory=list, max_length=20)

    @field_validator("visibility", mode="before")
    @classmethod
    def normalize_vis(cls, value: object) -> object:
        return normalize_visibility(value)

    @model_validator(mode="after")
    def validate_modifiers(self) -> "MethodDefinition":
        if self.static is True and self.abstract is True:
            raise ValueError("method cannot be both static and abstract")
        return self

    @model_serializer(mode="wrap")
    def omit_absent_optionals(self, serializer):
        return {key: value for key, value in serializer(self).items() if value is not None}


class AttributeDefinition(Contract):
    name: Name
    dataType: DataType
    primaryKey: bool = False
    nullable: bool = True
    visibility: UmlVisibility | None = None
    static: bool | None = None

    @field_validator("dataType", mode="before")
    @classmethod
    def normalize(cls, value: object) -> object:
        return normalize_data_type(value)

    @field_validator("visibility", mode="before")
    @classmethod
    def normalize_vis(cls, value: object) -> object:
        return normalize_visibility(value)

    @model_serializer(mode="wrap")
    def omit_absent_optionals(self, serializer):
        return {key: value for key, value in serializer(self).items() if value is not None}


class EntityDefinition(Contract):
    name: Name
    attributes: list[AttributeDefinition] = Field(max_length=100)
    kind: ClassifierKind | None = None
    abstract: bool | None = None
    methods: list[MethodDefinition] | None = Field(default=None, max_length=100)
    literals: list[Name] | None = Field(default=None, max_length=100)

    @model_validator(mode="before")
    @classmethod
    def default_attributes_for_non_entity_classifiers(cls, value: object) -> object:
        if isinstance(value, dict) and "attributes" not in value and value.get("kind") in {"INTERFACE", "ENUMERATION"}:
            return {**value, "attributes": []}
        return value

    @model_serializer(mode="wrap")
    def omit_absent_optionals(self, serializer):
        return {key: value for key, value in serializer(self).items() if value is not None}


DiagramCardinality = Literal["ZERO_ONE", "ONE_ONE", "ZERO_MANY", "ONE_MANY"]


def _normalize_relation_kind_alias(value: object) -> object:
    if isinstance(value, dict) and "relationKind" in value:
        copied = {k: v for k, v in value.items() if k != "relationKind"}
        if "kind" not in copied or copied["kind"] is None:
            copied["kind"] = value["relationKind"]
        return copied
    return value


class RelationshipDefinition(Contract):
    sourceEntity: Name
    targetEntity: Name
    sourceCardinality: DiagramCardinality | None = None
    targetCardinality: DiagramCardinality | None = None
    name: Name | None = None
    joinTableName: Name | None = None
    id: Name | None = None
    kind: RelationKind | None = None
    sourceMultiplicity: Name | None = None
    targetMultiplicity: Name | None = None
    aggregationEnd: AggregationEnd | None = None

    @model_validator(mode="before")
    @classmethod
    def accept_relation_kind_alias(cls, value: object) -> object:
        return _normalize_relation_kind_alias(value)

    @model_validator(mode="after")
    def validate_cardinalities_by_kind(self) -> "RelationshipDefinition":
        effective_kind = self.kind or "ASSOCIATION"
        if effective_kind in MULTIPLICATIVE_RELATION_KINDS:
            if self.sourceCardinality is None or self.targetCardinality is None:
                raise ValueError("multiplicative relationship requires sourceCardinality and targetCardinality")
        return self

    @model_serializer(mode="wrap")
    def omit_absent_names(self, serializer):
        return {key: value for key, value in serializer(self).items() if value is not None}


class AddEntity(Contract):
    type: Literal["ADD_ENTITY"]
    entity: EntityDefinition


class DeleteEntity(Contract):
    type: Literal["DELETE_ENTITY"]
    entityName: Name


class RenameEntity(Contract):
    type: Literal["RENAME_ENTITY"]
    entityName: Name
    newName: Name


class UpdateClassifier(Contract):
    type: Literal["UPDATE_CLASSIFIER"]
    entityName: Name
    kind: ClassifierKind | None = None
    abstract: bool | None = None

    @model_validator(mode="after")
    def require_change(self) -> "UpdateClassifier":
        if self.kind is None and self.abstract is None:
            raise ValueError("UPDATE_CLASSIFIER requires kind or abstract")
        return self

    @model_serializer(mode="wrap")
    def omit_absent_optionals(self, serializer):
        return {key: value for key, value in serializer(self).items() if value is not None}


class AddAttribute(Contract):
    type: Literal["ADD_ATTRIBUTE"]
    entityName: Name
    attribute: AttributeDefinition


class AttributeReferenceOperation(Contract):
    entityName: Name
    attributeName: Name


class DeleteAttribute(AttributeReferenceOperation):
    type: Literal["DELETE_ATTRIBUTE"]


class RenameAttribute(AttributeReferenceOperation):
    type: Literal["RENAME_ATTRIBUTE"]
    newName: Name


class ChangeAttributeType(AttributeReferenceOperation):
    type: Literal["CHANGE_ATTRIBUTE_TYPE"]
    dataType: DataType

    @field_validator("dataType", mode="before")
    @classmethod
    def normalize(cls, value: object) -> object:
        return normalize_data_type(value)


class SetAttributePrimaryKey(AttributeReferenceOperation):
    type: Literal["SET_ATTRIBUTE_PRIMARY_KEY"]
    value: bool


class SetAttributeNullable(AttributeReferenceOperation):
    type: Literal["SET_ATTRIBUTE_NULLABLE"]
    value: bool


class SetAttributeVisibility(AttributeReferenceOperation):
    type: Literal["SET_ATTRIBUTE_VISIBILITY"]
    visibility: UmlVisibility

    @field_validator("visibility", mode="before")
    @classmethod
    def normalize_vis(cls, value: object) -> object:
        return normalize_visibility(value)


class SetAttributeStatic(AttributeReferenceOperation):
    type: Literal["SET_ATTRIBUTE_STATIC"]
    value: bool


class AddMethod(Contract):
    type: Literal["ADD_METHOD"]
    entityName: Name
    method: MethodDefinition


class UpdateMethod(Contract):
    type: Literal["UPDATE_METHOD"]
    entityName: Name
    methodName: Name
    parameterTypes: list[Name] | None = Field(default=None, max_length=20)
    newName: Name | None = None
    returnType: Name | None = None
    visibility: UmlVisibility | None = None
    static: bool | None = None
    abstract: bool | None = None
    method: MethodDefinition | None = None

    @field_validator("visibility", mode="before")
    @classmethod
    def normalize_vis(cls, value: object) -> object:
        return normalize_visibility(value)

    @model_validator(mode="after")
    def require_change(self) -> "UpdateMethod":
        if (
            self.newName is None
            and self.returnType is None
            and self.visibility is None
            and self.static is None
            and self.abstract is None
            and self.method is None
        ):
            raise ValueError("UPDATE_METHOD requires at least one updated field")
        return self

    @model_serializer(mode="wrap")
    def omit_absent_optionals(self, serializer):
        return {key: value for key, value in serializer(self).items() if value is not None}


class DeleteMethod(Contract):
    type: Literal["DELETE_METHOD"]
    entityName: Name
    methodName: Name
    parameterTypes: list[Name] | None = Field(default=None, max_length=20)

    @model_serializer(mode="wrap")
    def omit_absent_optionals(self, serializer):
        return {key: value for key, value in serializer(self).items() if value is not None}


class AddMethodParameter(Contract):
    type: Literal["ADD_METHOD_PARAMETER"]
    entityName: Name
    methodName: Name
    parameterTypes: list[Name] | None = Field(default=None, max_length=20)
    parameter: ParameterDefinition

    @model_serializer(mode="wrap")
    def omit_absent_optionals(self, serializer):
        return {key: value for key, value in serializer(self).items() if value is not None}


class UpdateMethodParameter(Contract):
    type: Literal["UPDATE_METHOD_PARAMETER"]
    entityName: Name
    methodName: Name
    parameterTypes: list[Name] | None = Field(default=None, max_length=20)
    parameterName: Name
    newName: Name | None = None
    newType: Name | None = None
    parameter: ParameterDefinition | None = None

    @model_validator(mode="after")
    def require_change(self) -> "UpdateMethodParameter":
        if self.newName is None and self.newType is None and self.parameter is None:
            raise ValueError("UPDATE_METHOD_PARAMETER requires newName, newType, or parameter")
        return self

    @model_serializer(mode="wrap")
    def omit_absent_optionals(self, serializer):
        return {key: value for key, value in serializer(self).items() if value is not None}


class DeleteMethodParameter(Contract):
    type: Literal["DELETE_METHOD_PARAMETER"]
    entityName: Name
    methodName: Name
    parameterTypes: list[Name] | None = Field(default=None, max_length=20)
    parameterName: Name

    @model_serializer(mode="wrap")
    def omit_absent_optionals(self, serializer):
        return {key: value for key, value in serializer(self).items() if value is not None}


class AddEnumLiteral(Contract):
    type: Literal["ADD_ENUM_LITERAL"]
    entityName: Name
    literal: Name


class RenameEnumLiteral(Contract):
    type: Literal["RENAME_ENUM_LITERAL"]
    entityName: Name
    literal: Name
    newLiteral: Name

    @model_validator(mode="before")
    @classmethod
    def accept_new_name_alias(cls, value: object) -> object:
        if isinstance(value, dict) and "newName" in value and "newLiteral" not in value:
            copied = {k: v for k, v in value.items() if k != "newName"}
            copied["newLiteral"] = value["newName"]
            return copied
        return value


class DeleteEnumLiteral(Contract):
    type: Literal["DELETE_ENUM_LITERAL"]
    entityName: Name
    literal: Name


class AddRelationship(Contract):
    type: Literal["ADD_RELATIONSHIP"]
    relationship: RelationshipDefinition


class RelationshipReference(Contract):
    sourceEntity: Name
    targetEntity: Name
    name: Name | None = None
    id: Name | None = None
    kind: RelationKind | None = None

    @model_validator(mode="before")
    @classmethod
    def accept_relation_kind_alias(cls, value: object) -> object:
        return _normalize_relation_kind_alias(value)

    @model_serializer(mode="wrap")
    def omit_absent_name(self, serializer):
        return {key: value for key, value in serializer(self).items() if value is not None}


class DeleteRelationship(Contract):
    type: Literal["DELETE_RELATIONSHIP"]
    relationship: RelationshipReference


class RelationshipUpdate(RelationshipReference):
    sourceCardinality: DiagramCardinality | None = None
    targetCardinality: DiagramCardinality | None = None
    joinTableName: Name | None = None
    sourceMultiplicity: Name | None = None
    targetMultiplicity: Name | None = None
    aggregationEnd: AggregationEnd | None = None

    @model_validator(mode="after")
    def validate_cardinalities_by_kind(self) -> "RelationshipUpdate":
        effective_kind = self.kind or "ASSOCIATION"
        if effective_kind in MULTIPLICATIVE_RELATION_KINDS:
            if self.sourceCardinality is None or self.targetCardinality is None:
                raise ValueError("multiplicative relationship update requires sourceCardinality and targetCardinality")
        return self


class UpdateRelationship(Contract):
    type: Literal["UPDATE_RELATIONSHIP"]
    relationship: RelationshipUpdate


class AssociationCreationDefinition(Contract):
    sourceEntity: Name
    targetEntity: Name
    associationEntityName: Name
    attributes: list[AttributeDefinition] = Field(max_length=100)

    @field_validator("attributes")
    @classmethod
    def own_attributes(cls, value: list[AttributeDefinition]) -> list[AttributeDefinition]:
        if any(attribute.primaryKey for attribute in value):
            raise ValueError("association attributes cannot replace the generated primary key")
        return value


class CreateAssociation(Contract):
    type: Literal["CREATE_ASSOCIATION"]
    association: AssociationCreationDefinition


class DeleteAssociation(Contract):
    type: Literal["DELETE_ASSOCIATION"]
    associationEntityName: Name


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


Operation = Annotated[
    AddEntity
    | DeleteEntity
    | RenameEntity
    | UpdateClassifier
    | AddAttribute
    | DeleteAttribute
    | RenameAttribute
    | ChangeAttributeType
    | SetAttributePrimaryKey
    | SetAttributeNullable
    | SetAttributeVisibility
    | SetAttributeStatic
    | AddMethod
    | UpdateMethod
    | DeleteMethod
    | AddMethodParameter
    | UpdateMethodParameter
    | DeleteMethodParameter
    | AddEnumLiteral
    | RenameEnumLiteral
    | DeleteEnumLiteral
    | AddRelationship
    | DeleteRelationship
    | UpdateRelationship
    | CreateAssociation
    | DeleteAssociation
    | ConvertManyToManyAssociation,
    Field(discriminator="type"),
]


class InterpretResponse(Contract):
    operations: list[Operation] = Field(max_length=50)


class ConversionInterpretResponse(Contract):
    """Narrow provider schema used only while interpreting an explicit conversion."""

    operations: list[ConvertManyToManyAssociation] = Field(max_length=1)


class ContextRelationship(RelationshipDefinition):
    id: Name | None = None

    @model_validator(mode="before")
    @classmethod
    def legacy_context(cls, value: object) -> object:
        value = _normalize_relation_kind_alias(value)
        if not isinstance(value, dict) or "sourceCardinality" in value or "targetCardinality" in value:
            return value
        if value.get("kind") in {"GENERALIZATION", "REALIZATION", "DEPENDENCY"}:
            return value
        legacy = {
            "ONE_TO_ONE": ("ONE_ONE", "ONE_ONE"),
            "ONE_TO_MANY": ("ONE_ONE", "ZERO_MANY"),
            "MANY_TO_ONE": ("ZERO_MANY", "ONE_ONE"),
            "MANY_TO_MANY": ("ZERO_MANY", "ZERO_MANY"),
            None: ("ONE_ONE", "ONE_ONE"),
        }
        kind = value.get("relationshipType")
        if not (kind is None or isinstance(kind, str)) or kind not in legacy:
            return value
        source, target = legacy[kind]
        result = {k: v for k, v in value.items() if k != "relationshipType"}
        return {**result, "sourceCardinality": source, "targetCardinality": target}


class DiagramContext(Contract):
    entities: list[EntityDefinition]
    relationships: list[ContextRelationship]
    associations: list["AssociationContext"] = Field(default_factory=list)


class AssociationContext(Contract):
    entityName: Name
    tableName: Name
    endpointEntityNames: list[Name] = Field(min_length=2, max_length=2)
    structuralRelationshipIds: list[Name] = Field(min_length=2, max_length=2)


class DiagramSelection(Contract):
    kind: Literal["ENTITY", "RELATIONSHIP"]
    entityName: Name | None = None
    sourceEntity: Name | None = None
    targetEntity: Name | None = None
    relationshipName: Name | None = None


InterpretRequest.model_rebuild()
