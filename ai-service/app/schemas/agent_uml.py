"""Typed, read-only UML snapshot supplied by Spring after project authorization."""
from typing import Literal
from pydantic import BaseModel, ConfigDict, Field


class UmlContextModel(BaseModel):
    model_config = ConfigDict(extra="forbid", strict=True)


class UmlAttribute(UmlContextModel):
    id: str | None = None
    name: str
    type: str
    primaryKey: bool = False
    nullable: bool | None = None
    visibility: str | None = None
    static: bool | None = None


class UmlParameter(UmlContextModel):
    id: str | None = None
    name: str
    type: str


class UmlMethod(UmlContextModel):
    id: str | None = None
    name: str
    returnType: str
    visibility: str = "public"
    static: bool | None = None
    abstract: bool | None = None
    parameters: list[UmlParameter] = Field(default_factory=list, max_length=100)


class UmlEndpoint(UmlContextModel):
    role: Literal["SOURCE", "TARGET"]
    entityId: str
    relationshipId: str
    foreignKeyName: str


class UmlAssociation(UmlContextModel):
    kind: Literal["MANY_TO_MANY_ASSOCIATION"]
    tableName: str
    uniquePair: bool
    endpoints: list[UmlEndpoint]
    sourceMultiplicity: str | None = None
    targetMultiplicity: str | None = None


class UmlClassifier(UmlContextModel):
    name: str
    kind: Literal["ENTITY", "CLASS", "INTERFACE", "ENUMERATION", "ASSOCIATION_CLASS"] = "ENTITY"
    abstract: bool | None = None
    attributes: list[UmlAttribute] = Field(default_factory=list, max_length=500)
    methods: list[UmlMethod] = Field(default_factory=list, max_length=500)
    literals: list[str] = Field(default_factory=list, max_length=500)
    association: UmlAssociation | None = None


class UmlNode(UmlContextModel):
    id: str
    data: UmlClassifier


class UmlRelationship(UmlContextModel):
    relationKind: Literal["ASSOCIATION", "GENERALIZATION", "REALIZATION", "AGGREGATION", "COMPOSITION", "DEPENDENCY"] = "ASSOCIATION"
    relationshipType: str | None = None
    name: str | None = None
    joinTableName: str | None = None
    sourceCardinality: str | None = None
    targetCardinality: str | None = None
    sourceMultiplicity: str | None = None
    targetMultiplicity: str | None = None
    aggregationEnd: str | None = None


class UmlEdge(UmlContextModel):
    id: str | None = None
    source: str
    target: str
    data: UmlRelationship = Field(default_factory=UmlRelationship)


class AgentUmlDocument(UmlContextModel):
    version: Literal[1, 2]
    nodes: list[UmlNode] = Field(max_length=2000)
    edges: list[UmlEdge] = Field(max_length=5000)
