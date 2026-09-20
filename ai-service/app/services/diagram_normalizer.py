from app.schemas.diagram import (
    AddAttribute, AddEntity, AddRelationship, AttributeDefinition, InterpretResponse,
)
from app.services.providers.base import ProviderResponseError


class DiagramConflictError(ProviderResponseError):
    """Validated operations contain incompatible definitions of an attribute."""


def normalize_operations(document: InterpretResponse) -> InterpretResponse:
    """Deduplicate validated operations without mutating input or reordering survivors."""
    definitions: dict[tuple[str, str], AttributeDefinition] = {}
    embedded: set[tuple[str, str]] = set()
    prepared = []

    def register(entity: str, attribute: AttributeDefinition) -> tuple[str, str]:
        key = (entity.casefold(), attribute.name.casefold())
        previous = definitions.get(key)
        if previous is not None and (
            previous.dataType, previous.primaryKey, previous.nullable
        ) != (attribute.dataType, attribute.primaryKey, attribute.nullable):
            raise DiagramConflictError("La respuesta de IA contiene definiciones de atributos en conflicto.")
        definitions.setdefault(key, attribute)
        return key

    # Inspect every definition before removing anything. This also catches redundant
    # ADD_ATTRIBUTE operations that appear before their ADD_ENTITY.
    for operation in document.operations:
        if isinstance(operation, AddEntity):
            attributes = []
            seen: set[tuple[str, str]] = set()
            for attribute in operation.entity.attributes:
                key = register(operation.entity.name, attribute)
                embedded.add(key)
                if key not in seen:
                    seen.add(key)
                    attributes.append(attribute)
            operation = operation.model_copy(update={
                "entity": operation.entity.model_copy(update={"attributes": attributes})
            })
        elif isinstance(operation, AddAttribute):
            register(operation.entityName, operation.attribute)
        prepared.append(operation)

    operations = []
    seen_attributes: set[tuple[str, str]] = set()
    seen_relationships: set[tuple[tuple[str, str], ...]] = set()
    for operation in prepared:
        if isinstance(operation, AddAttribute):
            key = (operation.entityName.casefold(), operation.attribute.name.casefold())
            if key in embedded or key in seen_attributes:
                continue
            seen_attributes.add(key)
        elif isinstance(operation, AddRelationship):
            relationship = operation.relationship
            key = (*tuple(sorted(((relationship.sourceEntity.casefold(), relationship.sourceCardinality),
                                  (relationship.targetEntity.casefold(), relationship.targetCardinality)))),
                   ((relationship.name or "").casefold(), "name"))
            if key in seen_relationships:
                continue
            seen_relationships.add(key)
        operations.append(operation)
    return InterpretResponse(operations=operations)
