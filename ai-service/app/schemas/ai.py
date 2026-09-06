from pydantic import BaseModel, Field, field_validator


class GenerateRequest(BaseModel):
    prompt: str = Field(..., description="Text instruction for the configured AI provider")

    @field_validator("prompt")
    @classmethod
    def prompt_must_not_be_blank(cls, value: str) -> str:
        value = value.strip()
        if not value:
            raise ValueError("prompt must not be empty")
        return value


class GenerateResponse(BaseModel):
    content: str


class HealthResponse(BaseModel):
    status: str
    provider: str
    providerAvailable: bool
