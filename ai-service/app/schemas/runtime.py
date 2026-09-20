from typing import Literal

from pydantic import BaseModel, ConfigDict, Field


class StrictModel(BaseModel):
    model_config = ConfigDict(extra="forbid", strict=True)


class RuntimeField(StrictModel):
    name: str = Field(min_length=1)
    type: Literal["STRING", "INTEGER", "LONG", "DECIMAL", "BOOLEAN", "DATE", "DATETIME"]
    nullable: bool
    primaryKey: bool
    generated: bool


class RuntimeRelation(StrictModel):
    name: str = Field(min_length=1)
    target: str = Field(min_length=1)
    nullable: bool
    multiple: bool


class RuntimeEntity(StrictModel):
    name: str = Field(min_length=1)
    fields: list[RuntimeField]
    relations: list[RuntimeRelation]


class RuntimeSchema(StrictModel):
    entities: list[RuntimeEntity] = Field(min_length=1, max_length=100)


class RuntimeInterpretRequest(StrictModel):
    text: str = Field(min_length=1, max_length=2000)
    application_schema: str = Field(alias="schema", min_length=2, max_length=200000)


class RuntimeInterpretResponse(StrictModel):
    status: Literal["INTERPRETED", "NEEDS_CLARIFICATION", "NOT_UNDERSTOOD"]
    operation: str | None = None
    entity: str | None = None
    values: dict[str, object] | None = None
    relations: dict[str, object] | None = None
    message: str | None = None
    missingFields: list[str] = Field(default_factory=list)
