"""Extract one JSON document without repairing syntax or merging documents."""
import json
import logging

logger = logging.getLogger(__name__)


def _unique_object(pairs: list) -> dict:
    result = {}
    for key, value in pairs:
        if key in result:
            raise ValueError("Duplicate JSON object key")
        result[key] = value
    return result


def _reject_constant(value: str) -> None:
    raise ValueError("Non-standard JSON constant")


def _is_explanation(text: str) -> bool:
    text = text.strip()
    if not text:
        return True
    # Deliberately narrow: prose, not code, another JSON scalar, or stray delimiters.
    if len(text) > 500 or not text[0].isalpha():
        return False
    if not all(c.isalpha() or c.isspace() or c in ".,:;!?'-()" for c in text):
        return False
    words = text.translate(str.maketrans({c: " " for c in ".,:;!?'-()"})).split()
    return not any(word in {"true", "false", "null", "NaN", "Infinity"} for word in words)


def _structural_preview(raw: str) -> str:
    """Development diagnostic: only punctuation/layout, all content redacted.

    Even malformed strings, bare credentials and truncated JWTs are never logged.
    This is a preview, not a parser or a JSON repair mechanism.
    """
    output = []
    in_string = escaped = in_token = False
    for char in raw[:1500]:
        if in_string:
            if escaped:
                escaped = False
            elif char == "\\":
                escaped = True
            elif char == '"':
                output.append('"')
                in_string = False
            continue
        if char == '"':
            output.append('"<redacted>')
            in_string = True
            in_token = False
        elif char in "{}[],:`" or char.isspace():
            output.append(char if char in "{}[],:` \n\r\t" else " ")
            in_token = False
        elif not in_token:
            output.append("<redacted>")
            in_token = True
    return "".join(output)[:1500]


def parse_llm_json_response(raw: str) -> object:
    text = raw.strip()
    lines = text.splitlines()
    if len(lines) >= 3 and lines[0].strip().lower() in {"```", "```json"} and lines[-1].strip() == "```":
        text = "\n".join(lines[1:-1]).strip()
    decoder = json.JSONDecoder(object_pairs_hook=_unique_object, parse_constant=_reject_constant)
    try:
        try:
            return decoder.decode(text)
        except json.JSONDecodeError:
            # Never skip a broken first candidate to salvage one of its children.
            start = next((i for i, char in enumerate(text) if char in "{["), -1)
            if start < 0 or not _is_explanation(text[:start]):
                raise ValueError("No unambiguous JSON document with explanatory prefix") from None
            data, end = decoder.raw_decode(text, start)
            if not _is_explanation(text[end:]):
                raise ValueError("Ambiguous JSON documents or non-explanatory suffix")
            return data
    except (ValueError, RecursionError) as error:
        if isinstance(error, json.JSONDecodeError):
            logger.warning("Invalid LLM JSON: %s line=%d column=%d", error.msg, error.lineno, error.colno)
        else:
            # Messages below are fixed strings owned by this parser, never input.
            reason = str(error) if str(error) in {
                "Duplicate JSON object key", "Non-standard JSON constant",
                "No unambiguous JSON document with explanatory prefix",
                "Ambiguous JSON documents or non-explanatory suffix",
            } else type(error).__name__
            logger.warning("Invalid LLM JSON: %s", reason)
        logger.debug("LLM parse failure structuralPreview=%r", _structural_preview(raw))
        raise
