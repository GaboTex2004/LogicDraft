import re
import unicodedata
from enum import StrEnum


class AgentIntent(StrEnum):
    INFORMATIONAL = "INFORMATIONAL"
    MODIFICATION = "MODIFICATION"
    AMBIGUOUS = "AMBIGUOUS"


def _plain(value: str) -> str:
    normalized = unicodedata.normalize("NFD", value.casefold())
    without_accents = "".join(char for char in normalized if unicodedata.category(char) != "Mn")
    return " ".join(without_accents.replace("¿", " ").replace("¡", " ").split())


def classify_agent_intent(message: str) -> AgentIntent:
    """Classify edit authorization from user text, never from model output."""
    plain = _plain(message).strip()
    interrogative = bool(re.match(
        r"^(?:que|cual|cuales|como|por que|explica(?:me)?|describe|dime|muestra|opinas)\b", plain,
    )) or "?" in plain
    if interrogative:
        return AgentIntent.INFORMATIONAL
    if re.match(r"^(?:podriamos|podria(?:mos)?|tal vez|quizas|quiza)\b", plain):
        return AgentIntent.AMBIGUOUS
    if re.search(r"\b(?:que tal si|seria bueno|convendria)\b", plain):
        return AgentIntent.AMBIGUOUS
    explicit = re.match(
        r"^(?:(?:por favor)\s+)?(?:agrega|anade|crea|conecta|relaciona|convierte|transforma)\b",
        plain,
    ) or re.match(
        r"^(?:quiero|necesito)\s+que\s+(?:agregues|anadas|crees|conectes|relaciones|conviertas|transformes)\b",
        plain,
    )
    return AgentIntent.MODIFICATION if explicit else AgentIntent.AMBIGUOUS

