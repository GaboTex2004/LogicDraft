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
        r"^(?:que|cual|cuales|cuanto|cuantos|cuanta|cuantas|como|por que|explica(?:me)?|"
        r"describe|dime|muestra(?:me)?|opinas|lee|leer|lista|listar|enumera|analiza|revisa)\b",
        plain,
    )) or "?" in plain
    if interrogative:
        return AgentIntent.INFORMATIONAL
    if re.match(r"^(?:podriamos|podria(?:mos)?|tal vez|quizas|quiza)\b", plain):
        return AgentIntent.AMBIGUOUS
    if re.search(r"\b(?:que tal si|seria bueno|convendria)\b", plain):
        return AgentIntent.AMBIGUOUS
    explicit = re.match(
        r"^(?:(?:por favor)\s+)?(?:"
        r"agrega|agregar|agregame|agregue|"
        r"anade|anadir|anademe|anada|"
        r"crea|crear|creame|cree|"
        r"genera|generar|generame|genere|"
        r"incluye|incluir|incluya|"
        r"conecta|conectar|conecte|"
        r"relaciona|relacionar|relacione|une|unir|"
        r"convierte|convertir|convierta|"
        r"transforma|transformar|transforme|"
        r"edita|editar|edite|"
        r"modifica|modificar|modifique|"
        r"actualiza|actualizar|actualice|"
        r"cambia|cambiar|cambie|"
        r"renombra|renombrar|renombre|"
        r"elimina|eliminar|elimine|"
        r"borra|borrar|borre|"
        r"quita|quitar|quite|suprime|suprimir"
        r")\b",
        plain,
    ) or re.match(
        r"^(?:quiero|necesito)\s+que\s+(?:"
        r"agregues|anadas|crees|generes|incluyas|conectes|relaciones|unas|"
        r"conviertas|transformes|edites|modifiques|actualices|cambies|renombres|"
        r"elimines|borres|quites|suprimas"
        r")\b",
        plain,
    )
    return AgentIntent.MODIFICATION if explicit else AgentIntent.AMBIGUOUS

