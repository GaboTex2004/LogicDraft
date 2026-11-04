"""Image observations, not executable general-agent operations. Contract image-v1."""
from typing import Annotated, Literal

from pydantic import BaseModel, ConfigDict, Field


class ImageContract(BaseModel):
    model_config = ConfigDict(extra="forbid", strict=True)


Name = Annotated[str, Field(min_length=1, max_length=100, pattern=r"^\S(?:.*\S)?$")]
Visibility = Literal["public", "private", "protected", "package"]


class ImageAttribute(ImageContract):
    name: Name
    type: Name
    primaryKey: bool | None = None
    nullable: bool | None = None
    visibility: Visibility | None = None
    static: bool | None = None


class ImageParameter(ImageContract):
    name: Name
    type: Name


class ImageMethod(ImageContract):
    name: Name
    returnType: Name
    parameters: list[ImageParameter] = Field(default_factory=list, max_length=30)
    visibility: Visibility | None = None
    static: bool | None = None
    abstract: bool | None = None


class ImageClassifier(ImageContract):
    type: Literal["ADD_CLASSIFIER"]
    name: Name
    kind: Literal["ENTITY", "CLASS", "INTERFACE", "ENUMERATION", "ASSOCIATION_CLASS"] | None = None
    attributes: list[ImageAttribute] = Field(default_factory=list, max_length=100)
    methods: list[ImageMethod] = Field(default_factory=list, max_length=100)
    literals: list[Name] = Field(default_factory=list, max_length=100)
    abstract: bool | None = None


class ImageRelationship(ImageContract):
    type: Literal["ADD_RELATIONSHIP"]
    source: Name
    target: Name
    relationKind: Literal["ASSOCIATION", "GENERALIZATION", "REALIZATION", "AGGREGATION", "COMPOSITION", "DEPENDENCY"]
    name: Name | None = None
    sourceMultiplicity: Name | None = None
    targetMultiplicity: Name | None = None
    aggregationEnd: Literal["SOURCE", "TARGET"] | None = None


class ImageRecognition(ImageContract):
    operations: list[Annotated[ImageClassifier | ImageRelationship, Field(discriminator="type")]] = Field(max_length=100)
    warnings: list[Annotated[str, Field(min_length=1, max_length=500)]] = Field(default_factory=list, max_length=100)


class ImageDocument(ImageContract):
    version: Literal[1, 2]
    nodes: list[dict] = Field(max_length=2000)
    edges: list[dict] = Field(max_length=5000)
