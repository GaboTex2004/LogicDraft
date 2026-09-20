from functools import lru_cache
from pathlib import Path

from pydantic import Field
from pydantic_settings import BaseSettings, SettingsConfigDict

ENV_FILE = Path(__file__).resolve().parents[2] / ".env"


class Settings(BaseSettings):
    ai_provider: str = "ollama"
    ollama_base_url: str = "http://localhost:11434"
    ollama_model: str = ""
    groq_api_key: str = ""
    groq_whisper_model: str = "whisper-large-v3-turbo"
    gemini_api_key: str = ""
    gemini_model: str = "gemini-3.5-flash-lite"
    ai_request_timeout: float = Field(default=60, gt=0)
    ai_service_host: str = "0.0.0.0"
    ai_service_port: int = Field(default=8000, ge=1, le=65535)
    
    model_config = SettingsConfigDict(
        env_file=ENV_FILE,
        env_file_encoding="utf-8",
        extra="ignore",
    )


@lru_cache
def get_settings() -> Settings:
    return Settings()
