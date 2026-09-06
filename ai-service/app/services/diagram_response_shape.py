"""Conservative adaptation of model envelopes, never of operation contents."""

_OPERATION_TYPES = ("ADD_ENTITY", "ADD_ATTRIBUTE", "ADD_RELATIONSHIP")


def _is_operation(value: object) -> bool:
    return isinstance(value, dict) and value.get("type") in _OPERATION_TYPES


def canonicalize_diagram_response_shape(data: object) -> object:
    """Wrap only recognized shapes; leave everything else for strict validation.

    Do not discard extras or combine ambiguous envelopes. This function does not
    mutate the input, validate operation contents, or fabricate missing fields.
    """
    if isinstance(data, dict):
        if "operations" in data:
            return data
        if _is_operation(data):
            return {"operations": [data]}
    elif isinstance(data, list) and all(_is_operation(item) for item in data):
        return {"operations": data}
    return data
