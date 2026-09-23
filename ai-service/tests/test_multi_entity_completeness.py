from app.skills.diagram.completeness import extract_expectation


def test_multiple_entities_with_inline_attributes():
    prompt = (
        "Crea las entidades Autor con ID Integer como clave primaria "
        "y Nombre String, y Libro con ID Integer como clave primaria "
        "y Titulo String. Relaciona Autor con Libro: "
        "un autor puede escribir muchos libros y cada libro pertenece a un autor."
    )

    expected = extract_expectation(prompt, None)

    assert set(expected.entity_names) == {"autor", "libro"}

    assert set(expected.attribute_targets) == {
        ("autor", "id"),
        ("autor", "nombre"),
        ("libro", "id"),
        ("libro", "titulo"),
    }

    assert expected.new_entity_count == 2

import pytest

from app.schemas.diagram import InterpretResponse
from app.skills.diagram.completeness import (
    DiagramIncompleteError,
    validate_completeness,
)


def test_rejects_entities_with_missing_attributes():
    prompt = (
        "Crea las entidades Autor con ID Integer como clave primaria "
        "y Nombre String, y Libro con ID Integer como clave primaria "
        "y Titulo String. Relaciona Autor con Libro: "
        "un autor puede escribir muchos libros y cada libro pertenece a un autor."
    )

    incomplete = InterpretResponse.model_validate({
        "operations": [
            {
                "type": "ADD_ENTITY",
                "entity": {"name": "Autor", "attributes": []},
            },
            {
                "type": "ADD_ENTITY",
                "entity": {"name": "Libro", "attributes": []},
            },
            {
                "type": "ADD_RELATIONSHIP",
                "relationship": {
                    "sourceEntity": "Autor",
                    "targetEntity": "Libro",
                    "sourceCardinality": "ONE_ONE",
                    "targetCardinality": "ZERO_MANY",
                },
            },
        ]
    })

    with pytest.raises(DiagramIncompleteError, match="faltan atributos"):
        validate_completeness(prompt, None, incomplete)

import pytest

from app.schemas.diagram import InterpretResponse
from app.skills.diagram.completeness import (
    DiagramIncompleteError,
    validate_completeness,
)


def test_rejects_entities_with_missing_attributes():
    prompt = (
        "Crea las entidades Autor con ID Integer como clave primaria "
        "y Nombre String, y Libro con ID Integer como clave primaria "
        "y Titulo String. Relaciona Autor con Libro: "
        "un autor puede escribir muchos libros y cada libro pertenece a un autor."
    )

    incomplete = InterpretResponse.model_validate({
        "operations": [
            {
                "type": "ADD_ENTITY",
                "entity": {"name": "Autor", "attributes": []},
            },
            {
                "type": "ADD_ENTITY",
                "entity": {"name": "Libro", "attributes": []},
            },
            {
                "type": "ADD_RELATIONSHIP",
                "relationship": {
                    "sourceEntity": "Autor",
                    "targetEntity": "Libro",
                    "sourceCardinality": "ONE_ONE",
                    "targetCardinality": "ZERO_MANY",
                },
            },
        ]
    })

    with pytest.raises(DiagramIncompleteError, match="faltan atributos"):
        validate_completeness(prompt, None, incomplete)