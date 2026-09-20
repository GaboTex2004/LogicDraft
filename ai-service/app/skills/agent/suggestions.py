import re


_TECHNICAL_OPERATION = re.compile(
    r"\b(?:ADD_ENTITY|ADD_ATTRIBUTE|ADD_RELATIONSHIP|CONVERT_MANY_TO_MANY_ASSOCIATION)\b",
)


def contains_technical_operation(answer: str) -> bool:
    """Reject internal protocol identifiers instead of masking a bad model response."""
    return bool(_TECHNICAL_OPERATION.search(answer))
