from typing import Any


ACTION_TYPES = [
    "ADD_ENTITY",
    "DELETE_ENTITY",
    "RENAME_ENTITY",
    "UPDATE_CLASSIFIER",
    "ADD_ATTRIBUTE",
    "DELETE_ATTRIBUTE",
    "RENAME_ATTRIBUTE",
    "CHANGE_ATTRIBUTE_TYPE",
    "SET_ATTRIBUTE_PRIMARY_KEY",
    "SET_ATTRIBUTE_NULLABLE",
    "SET_ATTRIBUTE_VISIBILITY",
    "SET_ATTRIBUTE_STATIC",
    "ADD_METHOD",
    "UPDATE_METHOD",
    "DELETE_METHOD",
    "ADD_METHOD_PARAMETER",
    "UPDATE_METHOD_PARAMETER",
    "DELETE_METHOD_PARAMETER",
    "ADD_ENUM_LITERAL",
    "RENAME_ENUM_LITERAL",
    "DELETE_ENUM_LITERAL",
    "ADD_RELATIONSHIP",
    "DELETE_RELATIONSHIP",
    "UPDATE_RELATIONSHIP",
    "CREATE_ASSOCIATION",
    "DELETE_ASSOCIATION",
    "CONVERT_MANY_TO_MANY_ASSOCIATION",
]

DATA_TYPES = ["String", "Long", "Integer", "Double", "Boolean", "Date", "DateTime"]
CARDINALITIES = ["ZERO_ONE", "ONE_ONE", "ZERO_MANY", "ONE_MANY"]
CLASSIFIER_KINDS = ["ENTITY", "CLASS", "INTERFACE", "ENUMERATION"]
RELATION_KINDS = ["ASSOCIATION", "GENERALIZATION", "REALIZATION", "AGGREGATION", "COMPOSITION", "DEPENDENCY"]
VISIBILITIES = ["public", "private", "protected", "package"]
AGGREGATION_ENDS = ["SOURCE", "TARGET"]


def _parameter_schema() -> dict[str, Any]:
    return {
        "type": "object",
        "properties": {
            "name": {"type": "string"},
            "type": {"type": "string"},
        },
        "required": ["name"],
    }


def _method_schema() -> dict[str, Any]:
    return {
        "type": "object",
        "properties": {
            "name": {"type": "string"},
            "returnType": {"type": "string"},
            "visibility": {"type": "string", "enum": VISIBILITIES},
            "static": {"type": "boolean"},
            "abstract": {"type": "boolean"},
            "parameters": {"type": "array", "items": _parameter_schema()},
        },
        "required": ["name"],
    }


def _attribute_schema() -> dict[str, Any]:
    return {
        "type": "object",
        "properties": {
            "name": {"type": "string"},
            "dataType": {"type": "string", "enum": DATA_TYPES},
            "primaryKey": {"type": "boolean"},
            "nullable": {"type": "boolean"},
            "visibility": {"type": "string", "enum": VISIBILITIES},
            "static": {"type": "boolean"},
        },
        "required": ["name", "dataType", "primaryKey", "nullable"],
    }


def _attributes_schema(min_items: int | None = None, max_items: int | None = None) -> dict[str, Any]:
    result: dict[str, Any] = {"type": "array", "items": _attribute_schema()}
    if min_items is not None:
        result["minItems"] = min_items
    if max_items is not None:
        result["maxItems"] = max_items
    return result


def gemini_diagram_plan_schema(
    conversion_attribute_count: int | None = None,
) -> dict[str, Any]:
    """Gemini-compatible output shape; Pydantic remains the strict authority."""
    name = {"type": "string"}
    relationship = {
        "type": "object",
        "properties": {
            "sourceEntity": name,
            "targetEntity": name,
            "sourceCardinality": {"type": "string", "enum": CARDINALITIES},
            "targetCardinality": {"type": "string", "enum": CARDINALITIES},
            "name": name,
            "joinTableName": name,
            "kind": {"type": "string", "enum": RELATION_KINDS},
            "sourceMultiplicity": name,
            "targetMultiplicity": name,
            "aggregationEnd": {"type": "string", "enum": AGGREGATION_ENDS},
        },
        "required": ["sourceEntity", "targetEntity"],
    }
    association = {
        "type": "object",
        "properties": {
            "sourceEntity": name,
            "targetEntity": name,
            "associationEntityName": name,
            "attributes": _attributes_schema(),
        },
        "required": ["sourceEntity", "targetEntity", "associationEntityName", "attributes"],
    }
    conversion_attributes = _attributes_schema(
        conversion_attribute_count,
        conversion_attribute_count,
    )
    operation = {
        "type": "object",
        "properties": {
            "type": {"type": "string", "enum": ACTION_TYPES},
            "entity": {
                "type": "object",
                "properties": {
                    "name": name,
                    "attributes": _attributes_schema(),
                    "kind": {"type": "string", "enum": CLASSIFIER_KINDS},
                    "abstract": {"type": "boolean"},
                    "methods": {"type": "array", "items": _method_schema()},
                    "literals": {"type": "array", "items": name},
                },
                "required": ["name", "attributes"],
            },
            "entityName": name,
            "newName": name,
            "kind": {"type": "string", "enum": CLASSIFIER_KINDS},
            "abstract": {"type": "boolean"},
            "static": {"type": "boolean"},
            "visibility": {"type": "string", "enum": VISIBILITIES},
            "attributeName": name,
            "attribute": _attribute_schema(),
            "dataType": {"type": "string", "enum": DATA_TYPES},
            "value": {"type": "boolean"},
            "method": _method_schema(),
            "methodName": name,
            "parameterTypes": {"type": "array", "items": name},
            "returnType": name,
            "parameter": _parameter_schema(),
            "parameterName": name,
            "newType": name,
            "literal": name,
            "newLiteral": name,
            "relationship": relationship,
            "association": association,
            "associationEntityName": name,
            "conversion": {
                "type": "object",
                "properties": {
                    "relationshipId": name,
                    "sourceEntity": name,
                    "targetEntity": name,
                    "associationEntityName": name,
                    "attributes": conversion_attributes,
                },
                "required": [
                    "relationshipId",
                    "sourceEntity",
                    "targetEntity",
                    "associationEntityName",
                    "attributes",
                ],
            },
        },
        "required": ["type"],
    }
    return {
        "type": "object",
        "properties": {"operations": {"type": "array", "items": operation}},
        "required": ["operations"],
    }


def is_diagram_plan_schema(schema: dict[str, Any]) -> bool:
    operations = schema.get("properties", {}).get("operations")
    if not isinstance(operations, dict):
        return False
    serialized = str(operations)
    return "ADD_ENTITY" in serialized or "ConvertManyToManyAssociation" in serialized


def conversion_attribute_count(schema: dict[str, Any]) -> int | None:
    definition = schema.get("$defs", {}).get("AssociationConversionDefinition", {})
    attributes = definition.get("properties", {}).get("attributes", {})
    minimum = attributes.get("minItems")
    maximum = attributes.get("maxItems")
    return minimum if isinstance(minimum, int) and minimum == maximum else None
