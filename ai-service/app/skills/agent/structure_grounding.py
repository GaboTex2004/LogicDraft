"""Ground a model interpretation in owner-scoped spans of the human request.

This does not generate UML or replace the LLM. It rejects ambiguous ownership
instead of matching a member globally by name/type or guessing its classifier.
"""
import re
from app.schemas.data_types import normalize_data_type
from app.skills.diagram.semantic_safety import plain

TYPED_ATTRIBUTE = re.compile(
    r'''(?<!\w)(?:"(?P<quoted>[^"]+)"|'(?P<single>[^']+)'|(?P<bare>[\w-]+))\s*:?\s*'''
    r"(?:de\s+tipo\s+|tipo\s+)?(?P<datatype>datetime|timestamp|varchar|integer|string|bigint|double|decimal|boolean|long|int|bool|date)(?!\w)")


def owned_attributes(message: str, classifier_names: list[str]) -> list[tuple[str, str, str, bool]]:
    text = plain(message)
    names = {plain(name) for name in classifier_names}
    # Ownership anchors use names resolved by the interpreter, not an expanding
    # dictionary of Spanish commands. Only explicit member blocks establish scope.
    headers = []
    for name in names:
        for match in re.finditer(rf"(?<!\w){re.escape(name)}(?!\w)\s*[\"']?\s*(?::|con\b)", text):
            headers.append((match.start(), match.end(), name))
    headers.sort()
    typed = list(TYPED_ATTRIBUTE.finditer(text))
    result = []
    for index, attr in enumerate(typed):
        preceding = [header for header in headers if header[1] <= attr.start()]
        if preceding:
            owner = preceding[-1][2]
        elif len(names) == 1:
            owner = next(iter(names))
        else:
            raise ValueError("Indica la clase propietaria de cada atributo usando NombreClase: atributos.")
        next_attribute = typed[index + 1].start() if index + 1 < len(typed) else len(text)
        next_header = next((h[0] for h in headers if h[0] > attr.end()), len(text))
        tail = text[attr.end():min(next_attribute, next_header)]
        pk = bool(re.match(r"\s*[,;]?\s*(?:\(\s*pk\s*\)|pk\b|(?:como\s+)?clave\s+primaria\b|primary\s+key\b)", tail))
        name = attr['quoted'] or attr['single'] or attr['bare']
        result.append((owner, name, normalize_data_type(attr['datatype']), pk))
    return result
