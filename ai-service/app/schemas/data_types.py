"""Recognized model aliases; unknown values remain subject to strict validation."""
import re


_ALIASES = {
    "string": "String", "varchar": "String", "char": "String", "text": "String",
    "integer": "Integer", "int": "Integer",
    "long": "Long", "bigint": "Long",
    "double": "Double", "float": "Double", "double precision": "Double",
    "decimal": "Double", "numeric": "Double", "real": "Double",
    "boolean": "Boolean", "bool": "Boolean", "date": "Date",
    "datetime": "DateTime", "timestamp": "DateTime",
    "timestamp without time zone": "DateTime", "timestamp with time zone": "DateTime",
}


def normalize_data_type(value: object) -> object:
    if not isinstance(value, str):
        return value
    key = " ".join(value.casefold().split())
    if re.fullmatch(r"varchar\s*\(\s*[1-9][0-9]*\s*\)", key):
        return "String"
    return _ALIASES.get(key, value)


def normalize_type_aliases(value: object) -> object:
    """Copy JSON recursively, modifying only recognized dataType values."""
    if isinstance(value, list):
        return [normalize_type_aliases(item) for item in value]
    if isinstance(value, dict):
        return {
            key: normalize_data_type(item) if key == "dataType" else normalize_type_aliases(item)
            for key, item in value.items()
        }
    return value
