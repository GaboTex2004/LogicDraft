import re
import unicodedata
import logging
from dataclasses import dataclass

from app.schemas.diagram import (
    AddAttribute, AddEntity, AddRelationship, ConvertManyToManyAssociation,
    CreateAssociation, DeleteEntity, DeleteRelationship, DiagramContext, InterpretResponse,
)
from app.services.providers.base import ProviderResponseError
from app.skills.diagram.semantic_safety import validate_semantic_safety

logger = logging.getLogger(__name__)


def _plain(value: str) -> str:
    normalized = unicodedata.normalize("NFD", value.casefold())
    return "".join(char for char in normalized if unicodedata.category(char) != "Mn")


def _key(value: str) -> str:
    return " ".join(_plain(value).split())


@dataclass(frozen=True)
class CompletenessExpectation:
    new_entity_count: int | None
    entity_names: tuple[str, ...]
    relationship_pairs: tuple[tuple[str, str], ...]
    attribute_targets: tuple[tuple[str, str], ...]


@dataclass(frozen=True)
class ConversionExpectation:
    source: str
    target: str
    association: str
    attributes: tuple[tuple[str, str], ...]
    ambiguous_attribute: bool


def extract_conversion_expectation(prompt: str) -> ConversionExpectation | None:
    plain = _plain(prompt)
    if not re.search(r"\b(?:convierte|convertir|transforma|transformar)\b", plain):
        return None
    pair = re.search(r"\brelacion(?:\s+n\s*:\s*m)?\s+entre\s+([a-z][\w-]*)\s+y\s+([a-z][\w-]*)", plain)
    association = re.search(
        r"\bentidad\s+asociativa\s+(?:(?:llamada|denominada)\s+)?([a-z][\w-]*)", plain,
    )
    if not pair or not association:
        return None
    type_aliases = {
        "string": "String", "varchar": "String", "long": "Long", "bigint": "Long",
        "integer": "Integer", "int": "Integer", "double": "Double", "decimal": "Double",
        "boolean": "Boolean", "bool": "Boolean", "date": "Date", "datetime": "DateTime",
        "timestamp": "DateTime",
    }
    tail = plain[association.end():]
    attributes: list[tuple[str, str]] = []
    typed_names: set[str] = set()
    for match in re.finditer(
            r"\b([a-z_][\w-]*)\s+(?:de\s+tipo\s+)?"
            r"(string|varchar|long|bigint|integer|int|double|decimal|boolean|bool|date|datetime|timestamp)\b",
            tail):
        name, data_type = match.group(1), type_aliases[match.group(2)]
        if name not in {"con", "atributo", "atributos", "propios", "tipo"} and name not in typed_names:
            typed_names.add(name)
            attributes.append((name, data_type))
    explicit_names = {
        match.group(1) for match in re.finditer(
            r"\batributo\s+(?:(?:llamado)\s+)?([a-z_][\w-]*)", tail
        )
    }
    return ConversionExpectation(pair.group(1), pair.group(2), association.group(1),
                                 tuple(attributes), bool(explicit_names - typed_names))


class DiagramIncompleteError(ProviderResponseError):
    def __init__(self, reason: str, previous: InterpretResponse):
        super().__init__(reason)
        self.reason = reason
        self.previous = previous


_CREATE_VERB = (
    r"\b(?:crea|crear|creame|cree|crees|agrega|agregar|agregame|agregue|agregues|"
    r"anade|anadir|anademe|anada|anadas|genera|generar|generame|genere|generes|"
    r"incluye|incluir|incluya)"
)
_NUMBER_WORDS = {
    "un": 1, "una": 1, "uno": 1,
    "dos": 2, "tres": 3, "cuatro": 4, "cinco": 5,
    "seis": 6, "siete": 7, "ocho": 8, "nueve": 9, "diez": 10,
}
_COUNT_TOKEN = r"(?:\d{1,2}|dos|tres|cuatro|cinco|seis|siete|ocho|nueve|diez)"


def _requested_entity_names(plain_prompt: str) -> list[str]:
    names: list[str] = []
    plural = re.search(
        rf"{_CREATE_VERB}\s+(?:exactamente\s+)?(?:(?:las?|los?|{_COUNT_TOKEN})\s+)*(?:entidades|clases)\s*:?\s*"
        r"(.+?)(?=\s+(?:y|con)\s+(?:una\s+)?relacion\b|\s+relacionad[ao]s?\b|\s+conectad[ao]s?\b|[.;]|$)",
        plain_prompt,
    )
    if plural:
        for candidate in re.split(r"\s*(?:,|;|\by\b)\s*", plural.group(1)):
            candidate = candidate.strip(" '\"")
            if (re.fullmatch(r"[a-z][\w-]{0,99}", candidate)
                    and candidate not in _NUMBER_WORDS
                    and candidate not in {"entidad", "entidades", "clase", "clases"}
                    and candidate not in names):
                names.append(candidate)
    for match in re.finditer(
            rf"{_CREATE_VERB}\s+(?:(?:una?|la|otra|otro|nueva|nuevo)\s+)*(?:entidad|clase)\s+"
            r"(?:(?:llamad[ao]|denominad[ao]|con\s+nombre|named|called)\s+)?"
            r"[\"']?([a-z][\w-]{0,99})",
            plain_prompt):
        if match.group(1) not in names:
            names.append(match.group(1))
    return names


def _requested_attributes(plain_prompt: str, entity_names: list[str]) -> list[tuple[str, str]]:
    requests: list[tuple[str, str]] = []
    starts = list(re.finditer(r"\b(?:atributo|attribute)\b", plain_prompt))
    for index, start in enumerate(starts):
        prefix = plain_prompt[max(0, start.start() - 45):start.start()]
        if re.search(
            r"(?:\b(?:renombra\w*|cambia\w*|elimina\w*|borra\w*|quita\w*|modifica\w*|"
            r"edita\w*|actualiza\w*|suprime\w*|delete\w*|remove\w*|rename\w*|update\w*|edit\w*|change\w*)\s+"
            r"(?:el\s+|la\s+|un\s+|una\s+|los\s+|las\s+)?$"
            r"|\bcon\s+(?:un\s+|una\s+|los\s+|las\s+)?$)",
            prefix,
        ):
            continue
        end = starts[index + 1].start() if index + 1 < len(starts) else len(plain_prompt)
        clause = plain_prompt[start.start():end]
        attribute = re.match(
            r"(?:atributo|attribute)\s+(?:(?:llamado|named)\s+)?[\"']?([a-z_][\w-]{0,99})",
            clause,
        )
        target = re.search(
            r"\b(?:a|en|to|in)\s+(?:la\s+|the\s+)?(?:entidad\s+|entity\s+)?"
            r"[\"']?([a-z_][\w-]{0,99})",
            clause,
        )
        if attribute and target and target.group(1) not in {
            "muchos", "uno", "una", "cero", "ambas", "ambos", "si", "n", "m",
        }:
            request = (target.group(1), attribute.group(1))
            if request not in requests:
                requests.append(request)
    creation_starts = list(re.finditer(
        rf"{_CREATE_VERB}\s+(?:(?:una?|la|otra|otro|nueva|nuevo)\s+)*(?:entidad|clase)\s+"
        r"(?:(?:llamad[ao]|denominad[ao]|con\s+nombre|named|called)\s+)?"
        r"[\"']?([a-z][\w-]{0,99})[\"']?",
        plain_prompt,
    ))
    for index, creation in enumerate(creation_starts):
        end = creation_starts[index + 1].start() if index + 1 < len(creation_starts) else len(plain_prompt)
        clause = plain_prompt[creation.end():end]
        entity = creation.group(1)
        for attribute in re.finditer(
                r"\b(?:atributo|attribute)\s+(?:(?:llamado|named)\s+)?[\"']?([a-z_][\w-]{0,99})",
                clause):
            if attribute.group(1) not in {"de", "tipo", "con"}:
                request = (entity, attribute.group(1))
                if request not in requests:
                    requests.append(request)

    # Detectar varios atributos tipados en una misma entidad.
    # Ejemplo: Agrega dos atributos en la entidad Auto:
    # "Placa" VARCHAR "año" INTEGER
    multiple_attributes = re.finditer(
        rf"{_CREATE_VERB}\s+"
        rf"(?:exactamente\s+)?(?:{_COUNT_TOKEN})\s+atributos?\s+"
        r"(?:en|a)\s+(?:la\s+)?entidad\s+"
        r"[\"']?([a-z_][\w-]{0,99})[\"']?\s*:?\s*"
        r"(.+?)(?=[.;]|$)",
        plain_prompt,
    )

    for match in multiple_attributes:
        entity_name = match.group(1)
        attributes_text = match.group(2)

        typed_attributes = re.finditer(
            r"""(?<!\w)(?:"([^"]+)"|'([^']+)'|([a-z_][\w-]{0,99}))\s+"""
            r"(?:de\s+tipo\s+|tipo\s+)?"
            r"(string|varchar|long|bigint|integer|int|double|decimal|"
            r"boolean|bool|date|datetime|timestamp)\b",
            attributes_text,
        )

        for attribute in typed_attributes:
            attribute_name = (
                attribute.group(1)
                or attribute.group(2)
                or attribute.group(3)
            )

            request = (entity_name, attribute_name)

            if request not in requests:
                requests.append(request)
    # Reconocer atributos de varias entidades declaradas en un mensaje.
    # Ejemplo:
    # crea 2 entidades "Mecanico" atributos: ID Integer y Nombre Varchar
    # y la otra entidad "Especialidad" atributos: Id Integer Nombre Varchar
    entity_blocks = list(re.finditer(
        r"""\bentidad(?:es)?\s+["']([a-z][\w-]{0,99})["']""",
        plain_prompt,
    ))

    type_pattern = (
        r"string|varchar|long|bigint|integer|int|double|decimal|"
        r"boolean|bool|date|datetime|timestamp"
    )

    for index, entity_match in enumerate(entity_blocks):
        entity_name = entity_match.group(1)

        end = (
            entity_blocks[index + 1].start()
            if index + 1 < len(entity_blocks)
            else len(plain_prompt)
        )

        section = plain_prompt[entity_match.end():end]

        # Analizar secciones que declaran atributos con ':' o 'con atributo(s)'.
        attribute_marker = re.search(r"\b(?:con\s+(?:los?\s+)?)?atributos?\s*:?\s+", section)
        if attribute_marker is None:
            continue

        section = section[attribute_marker.end():]

        typed_attributes = re.finditer(
            rf"""(?<!\w)(?:"([^"]+)"|'([^']+)'|([a-z_][\w-]{{0,99}}))"""
            rf"\s+(?:de\s+tipo\s+|tipo\s+)?({type_pattern})\b",
            section,
        )

        for match in typed_attributes:
            attribute_name = (
                match.group(1)
                or match.group(2)
                or match.group(3)
            )

            request = (entity_name, attribute_name)

            if request not in requests:
                requests.append(request)
    return requests


def _requested_deleted_entities(plain_prompt: str) -> list[str]:
    deleted: list[str] = []
    for match in re.finditer(
        r"\b(?:elimina\w*|borra\w*|quita\w*|suprime\w*|delete\w*|remove\w*)\s+"
        r"(?:la\s+|una\s+|el\s+)?(?:entidad|clase)\s+[\"']?([a-z][\w-]{0,99})",
        plain_prompt,
    ):
        name = match.group(1)
        if name not in deleted:
            deleted.append(name)
    return deleted


def _non_mutating_request(plain_prompt: str) -> bool:
    stripped = plain_prompt.strip().lstrip("¿¡").strip()
    if re.match(r"^no\s+(?:crea|crear|crees|cree|agrega|anade|genera|conecta|relaciona|elimina|borra|edita|modifica)\b", stripped):
        return True
    return bool(re.match(r"^(?:que|cual|cuales|como|por que|explica|describe|dime|muestra|lee|leer|lista|listar)\b", stripped))


def extract_expectation(prompt: str, diagram: DiagramContext | None) -> CompletenessExpectation:
    plain_prompt = _plain(prompt)
    if _non_mutating_request(plain_prompt):
        return CompletenessExpectation(None, (), (), ())
    count_match = re.search(
        rf"{_CREATE_VERB}\s+(?:exactamente\s+)?({_COUNT_TOKEN})\s+"
        r"(?:clases?|entidades?)\b",
        plain_prompt,
    )
    if count_match:
        token = count_match.group(1)
        requested_count = int(token) if token.isdigit() else _NUMBER_WORDS[token]
    else:
        requested_count = None
    names: list[str] = []
    for match in re.finditer(
            r"\b(?:nombre|llamad[ao]|denominad[ao])\s+[\"']?"
            r"([A-ZÁÉÍÓÚÑ][\wÁÉÍÓÚÑáéíóúñ-]{0,99})",
            prompt):
        if match.group(1) not in names and match.group(1).casefold() not in _TYPE_ALIASES:
            names.append(match.group(1))
    for name in _requested_entity_names(plain_prompt):
        if _key(name) not in {_key(item) for item in names}:
            names.append(name)
    # Reconocer una o varias entidades con atributos en una misma oración.
    # Ejemplo: Crea las entidades Autor con ID Integer y Nombre String,
    # y Libro con ID Integer y Titulo String.
    inline_attributes: list[tuple[str, str]] = []

    inline_match = re.search(
        rf"{_CREATE_VERB}\s+(?:exactamente\s+)?(?:(?:una?|las?|los?|otra|otro|nueva|nuevo|{_COUNT_TOKEN})\s+)*(?:entidad(?:es)?|clases?)\s*:?\s*(.+)",
        plain_prompt,
    )

    if inline_match:
        blocks = re.split(
            r"(?:\s*[;.]\s*|\s*,\s*(?:y\s+)?|\s+y\s+)(?=(?:(?:la|una|otra)\s+(?:entidad|clase)\s+)?[\"']?[a-z][\w-]*[\"']?\s+(?:con(?!\s+nombre\b)|atributos?\s*:)\b)",
            inline_match.group(1),
        )

        for block in blocks:
            match = re.match(
                r"\s*(?:(?:la|una|otra)\s+(?:entidad|clase)\s+)?(?:(?:llamad[ao]|denominad[ao])\s+)?[\"']?([a-z][\w-]*)[\"']?\s+(?:con(?!\s+nombre\b)\s+(?:los?\s+|un\s+)?(?:atributos?\s*:?\s*)?|atributos?\s*:\s*)(.+)",
                block,
            )

            if not match:
                continue

            entity_name, attributes_text = match.groups()
            if entity_name in {"una", "un", "uno", "otra", "otro", "la", "el", "las", "los"}:
                continue

            if _key(entity_name) not in {_key(item) for item in names}:
                names.append(entity_name)

            for attribute in _TYPED_ATTRIBUTE.finditer(attributes_text):
                attribute_name = (
                    attribute.group("double")
                    or attribute.group("single")
                    or attribute.group("bare")
                )
                if attribute_name in {"con", "atributo", "atributos", "tipo", "de", "un", "una"}:
                    continue

                target = (entity_name, attribute_name)

                if target not in inline_attributes:
                    inline_attributes.append(target)

    if requested_count and len(names) < requested_count:
        quoted_entities = re.findall(
            r"""\bentidad(?:es)?\s+["']([a-z][\w-]{0,99})["']""",
            plain_prompt,
        )
        if len(quoted_entities) < requested_count:
            all_quoted = re.findall(r"""["']([a-z][\w-]{0,99})["']""", plain_prompt)
            if len(all_quoted) >= requested_count:
                quoted_entities = all_quoted

        if len(quoted_entities) >= requested_count:
            for q in quoted_entities[:requested_count]:
                if _key(q) not in {_key(item) for item in names}:
                    names.append(q)
        else:
            tail_match = re.search(
                r"\b(?:clases?|entidades?)\s*:?\s*(.+)",
                plain_prompt,
            )

            if tail_match:
                ignored = {
                    "id", "pk", "integer", "varchar", "int", "date", "datetime", "timestamp",
                    "string", "decimal", "double", "boolean", "bool", "long", "bigint",
                    "atributo", "atributos", "con", "una", "un", "la", "el", "las", "los",
                    "relacion", "entre", "de", "tipo",
                }

                candidates = re.findall(
                    r"\b[a-z][\w-]{0,99}\b",
                    tail_match.group(1),
                )

                for name in candidates:
                    if name not in ignored and name != "y" and name not in _NUMBER_WORDS:
                        if _key(name) not in {_key(item) for item in names}:
                            names.append(name)
                        if len(names) >= requested_count:
                            break
    existing = {_key(entity.name) for entity in diagram.entities} if diagram else set()
    deleted_keys = {_key(name) for name in _requested_deleted_entities(plain_prompt)}
    all_attributes = list(dict.fromkeys(_requested_attributes(plain_prompt, names) + inline_attributes))
    if re.search(_CREATE_VERB, plain_prompt):
        for ent_name, _ in all_attributes:
            if _key(ent_name) not in existing and _key(ent_name) not in deleted_keys and _key(ent_name) not in {_key(item) for item in names}:
                names.append(ent_name)

    pairs: list[tuple[str, str]] = []
    direct_patterns = [
        r"\b(?:conecta|relaciona|conecte|relacione)\s+[\"']?([a-z][\w-]*)[\"']?\s+con\s+[\"']?([a-z][\w-]*)[\"']?",
        r"\bnombre\s+[\"']?([a-z][\w-]*)[\"']?.{0,180}?\b(?:conecte|relacione)\s+con\s+[\"']?([a-z][\w-]*)[\"']?",
        r"\brelacion(?:\s+n\s*:\s*m|\s+1\s*:\s*n|\s+1\s*:\s*1)?\s+entre\s+[\"']?([a-z][\w-]*)[\"']?\s+y\s+[\"']?([a-z][\w-]*)[\"']?",
    ]
    for pattern in direct_patterns:
        for match in re.finditer(pattern, plain_prompt, re.DOTALL):
            pair = (match.group(1), match.group(2))
            if pair not in pairs:
                pairs.append(pair)
    if names:
        target_patterns = [
            r"\brelacion(?:\s+de)?(?:\s+(?:n\s*:\s*m|1\s*:\s*n|1\s*:\s*m|1\s*:\s*1|muchos\s+a\s+muchos|uno\s+a\s+muchos|uno\s+a\s+uno))?\s+con\s+(?:la\s+(?:entidad|clase)\s+)?[\"']?([a-z][\w-]*)[\"']?",
            r"\b(?:conectad[ao]s?|relacionad[ao]s?|unid[ao]s?)(?:\s+con\s+(?:una\s+)?relacion(?:\s+de)?(?:\s+(?:n\s*:\s*m|1\s*:\s*n|1\s*:\s*m|1\s*:\s*1|muchos\s+a\s+muchos|uno\s+a\s+muchos|uno\s+a\s+uno))?)?\s+con\s+(?:la\s+(?:entidad|clase)\s+)?[\"']?([a-z][\w-]*)[\"']?",
        ]
        ignored_targets = {
            "una", "un", "uno", "la", "el", "los", "las", "ambas", "ambos",
            "si", "atributo", "atributos", "nombre", "id", "tipo",
        }
        existing_pair_keys = [{_key(a), _key(b)} for a, b in pairs]
        for pattern in target_patterns:
            for match in re.finditer(pattern, plain_prompt):
                target = match.group(1)
                if target in ignored_targets or _key(target) in deleted_keys:
                    continue
                source = names[-1]
                if diagram:
                    for entity in diagram.entities:
                        if _key(entity.name) == _key(target):
                            target = entity.name
                            break
                pair_key = {_key(source), _key(target)}
                if len(pair_key) == 2 and pair_key not in existing_pair_keys:
                    pairs.append((source, target))
                    existing_pair_keys.append(pair_key)
    if re.search(r"\brelacion(?:\s+n\s*:\s*m|\s+1\s*:\s*n|\s+1\s*:\s*1)?\s+entre\s+ambas\b", plain_prompt) and len(names) >= 2:
        pair = (names[-2], names[-1])
        if {_key(pair[0]), _key(pair[1])} not in [{_key(a), _key(b)} for a, b in pairs]:
            pairs.append(pair)

    # Detectar relaciones expresadas mediante "ambas entidades" o "con una relacion de N:M / 1:N / 1:1".
    implicit_relation = re.search(
        r"(?:\bambas\s+entidades\s+(?:conectadas|relacionadas|unidas)(?:\s+(?:con\s+una\s+)?relacion)?\s+n\s*:\s*m\b"
        r"|\b(?:con|y)\s+(?:que\s+tenga\s+)?(?:una\s+)?relacion(?:\s+de)?\s+(?:n\s*:\s*m|1\s*:\s*n|1\s*:\s*m|1\s*:\s*1|muchos\s+a\s+muchos|uno\s+a\s+muchos|uno\s+a\s+uno)\b"
        r"|\bque\s+tenga\s+(?:una\s+)?relacion(?:\s+de)?\s+(?:n\s*:\s*m|1\s*:\s*n|1\s*:\s*m|1\s*:\s*1|muchos\s+a\s+muchos|uno\s+a\s+muchos|uno\s+a\s+uno)\b"
        r"|\brelaciona(?:l[ao]s|d[ao]s)?\s+(?:entre\s+si|de\s+uno\s+a\s+muchos|uno\s+a\s+muchos|muchos\s+a\s+muchos|n\s*:\s*m)\b)",
        plain_prompt,
    )
    if implicit_relation and not pairs:
        if len(names) >= 2:
            pair = (names[-2], names[-1])
            if pair not in pairs:
                pairs.append(pair)
        elif len(names) == 1 and diagram:
            surviving = [
                entity.name for entity in diagram.entities
                if _key(entity.name) not in deleted_keys and _key(entity.name) != _key(names[0])
            ]
            if len(surviving) == 1:
                pair = (surviving[0], names[0])
                if pair not in pairs:
                    pairs.append(pair)

    if names:
        considered = names[:requested_count] if requested_count is not None else names
        unique_considered = dict.fromkeys(_key(name) for name in considered)
        new_count = sum(1 for name in unique_considered if name not in existing)
    else:
        new_count = requested_count
    return CompletenessExpectation(
        new_count,
        tuple(names),
        tuple(pairs),
        tuple(all_attributes),
    )


def _sanitize_requested_operations(
        prompt: str, expected: CompletenessExpectation, response: InterpretResponse) -> InterpretResponse:
    plain_prompt = _plain(prompt)
    if _non_mutating_request(plain_prompt):
        if response.operations:
            logger.info("Diagram AI discarded %d operations for a non-mutating request", len(response.operations))
        return InterpretResponse(operations=[])

    entity_keys = {_key(name) for name in expected.entity_names}
    attribute_keys = {(_key(entity), _key(attribute)) for entity, attribute in expected.attribute_targets}
    relationship_keys = [{_key(source), _key(target)} for source, target in expected.relationship_pairs]
    # These operations already passed semantic_safety against exact user targets.
    # A deletion verb by itself never grants authority to delete any entity.
    deleted_entity_keys = {_key(op.entityName) for op in response.operations if isinstance(op, DeleteEntity)}
    mentions_attributes = bool(attribute_keys) or re.search(
        r"\b(?:atributos?|attributes?|campos?|fields?)\b",
        plain_prompt,
    ) is not None
    mentions_relationship = bool(relationship_keys) or re.search(
        r"\b(?:relacion|relaciones|relaciona|relacionar|relacione|conecta|conectar|conecte|pertenece)\b",
        plain_prompt,
    ) is not None
    confident = bool(entity_keys or attribute_keys or relationship_keys or deleted_entity_keys)
    if not confident:
        return response

    sanitized = []
    removed = 0
    for operation in response.operations:
        if isinstance(operation, AddEntity):
            # La solicitud pide atributos para entidades existentes,
            # no crear una entidad nueva.
            if attribute_keys and not entity_keys:
                removed += 1
                continue

            entity_key = _key(operation.entity.name)
            if entity_keys and entity_key not in entity_keys:
                removed += 1
                continue
            allowed_attributes = {
                attribute for entity, attribute in attribute_keys if entity == entity_key
            }
            if not mentions_attributes:
                allowed_attributes = set()
            if allowed_attributes or not mentions_attributes:
                attributes = [
                    attribute for attribute in operation.entity.attributes
                    if _key(attribute.name) in allowed_attributes
                ]
                removed += len(operation.entity.attributes) - len(attributes)
                operation = operation.model_copy(update={
                    "entity": operation.entity.model_copy(update={"attributes": attributes}),
                })
        elif isinstance(operation, AddAttribute):
            if attribute_keys and (_key(operation.entityName), _key(operation.attribute.name)) not in attribute_keys:
                removed += 1
                continue
            if not mentions_attributes:
                removed += 1
                continue
        elif isinstance(operation, AddRelationship):
            pair = {_key(operation.relationship.sourceEntity), _key(operation.relationship.targetEntity)}
            if pair & deleted_entity_keys:
                removed += 1
                continue
            if relationship_keys and pair not in relationship_keys:
                removed += 1
                continue
            if not mentions_relationship:
                removed += 1
                continue
        elif isinstance(operation, DeleteRelationship):
            pair = {_key(operation.relationship.sourceEntity), _key(operation.relationship.targetEntity)}
            if pair & deleted_entity_keys:
                removed += 1
                continue
        sanitized.append(operation)
    if removed:
        logger.info("Diagram AI removed %d unrequested operation elements", removed)
    return InterpretResponse(operations=sanitized)


# Verify types only when explicitly present in the prompt, scoped by entity.
_TYPE_ALIASES = {
    "string": "String", "varchar": "String", "long": "Long", "bigint": "Long",
    "integer": "Integer", "int": "Integer", "double": "Double", "decimal": "Double",
    "boolean": "Boolean", "bool": "Boolean", "date": "Date", "datetime": "DateTime",
    "timestamp": "DateTime",
}
_TYPED_ATTRIBUTE = re.compile(
    r'''(?<!\w)(?:"(?P<double>[^"\n]+)"|'(?P<single>[^'\n]+)'|'''
    r'''(?P<bare>[a-z_][\w-]{0,99}))\s+(?:de\s+tipo\s+|tipo\s+)?'''
    r'''(?P<type>datetime|timestamp|string|varchar|bigint|integer|double|decimal|'''
    r'''boolean|long|int|bool|date)\b'''
    r'''(?P<pk>\s*(?:\(\s*pk\s*\)|\bpk\b|\b(?:como\s+)?clave\s+primaria\b|\b(?:como\s+)?primary\s+key\b))?'''
)
_ENTITY_HEADER = re.compile(
    r'''\b(?:entidad(?:es)?|clases?)\s+(?:(?:llamad[ao]|denominad[ao])\s+)?(?:"(?P<double>[^"\n]+)"|'''
    r''''(?P<single>[^'\n]+)'|(?P<bare>[a-z][\w-]{0,99}))'''
)


def _typed_attribute_expectations(
        prompt: str, expected: CompletenessExpectation
) -> dict[tuple[str, str], tuple[str, bool]]:
    plain = _plain(prompt)
    requested = {(_key(entity), _key(name)) for entity, name in expected.attribute_targets}
    requested_entities = {entity for entity, _ in requested}
    headers = []
    for match in _ENTITY_HEADER.finditer(plain):
        name = match.group("double") or match.group("single") or match.group("bare")
        if _key(name) in requested_entities:
            headers.append((match, _key(name)))

    specs: dict[tuple[str, str], tuple[str, bool]] = {}
    for index, (header, entity) in enumerate(headers):
        end = headers[index + 1][0].start() if index + 1 < len(headers) else len(plain)
        section = plain[header.end():end]
        marker = re.search(r"\b(?:con\s+(?:los?\s+)?)?atributos?\s*:?", section)
        if marker:
            section = section[marker.end():]
        elif not re.search(rf"{_CREATE_VERB}\s+", plain[:header.start()]):
            continue
        for match in _TYPED_ATTRIBUTE.finditer(section):
            name = match.group("double") or match.group("single") or match.group("bare")
            key = (entity, _key(name))
            if key in requested:
                specs[key] = (_TYPE_ALIASES[match.group("type")], bool(match.group("pk")))
    return specs


def validate_completeness(
        prompt: str, diagram: DiagramContext | None, response: InterpretResponse) -> InterpretResponse:
    if _non_mutating_request(_plain(prompt)):
        return InterpretResponse(operations=[])
    # Reject unsafe batches BEFORE sanitization/repair can retain or hide them.
    validate_semantic_safety(prompt, diagram, response)
    conversion_expected = extract_conversion_expectation(prompt)
    if conversion_expected is not None:
        if conversion_expected.ambiguous_attribute:
            raise DiagramIncompleteError("falta el tipo explicito de un atributo propio", response)
        if diagram is None:
            raise DiagramIncompleteError("falta el contexto del diagrama para convertir la relacion", response)
        pair = {_key(conversion_expected.source), _key(conversion_expected.target)}
        candidates = [relationship for relationship in diagram.relationships
                      if {_key(relationship.sourceEntity), _key(relationship.targetEntity)} == pair
                      and relationship.sourceCardinality in ("ZERO_MANY", "ONE_MANY")
                      and relationship.targetCardinality in ("ZERO_MANY", "ONE_MANY")]
        if len(candidates) != 1:
            reason = "no existe una relacion N:M candidata" if not candidates else "existen varias relaciones N:M candidatas"
            raise DiagramIncompleteError(reason + "; se requiere aclaracion", response)
        conversions = [operation for operation in response.operations
                       if isinstance(operation, ConvertManyToManyAssociation)]
        if len(conversions) != 1:
            raise DiagramIncompleteError("falta una unica operacion estructurada de conversion", response)
        operation = conversions[0]
        conversion = operation.conversion
        candidate = candidates[0]
        if conversion.relationshipId != candidate.id:
            raise DiagramIncompleteError("el relationshipId no coincide con la relacion real", response)
        if {_key(conversion.sourceEntity), _key(conversion.targetEntity)} != pair:
            raise DiagramIncompleteError("los extremos de la conversion no coinciden", response)
        if _key(conversion.associationEntityName) != _key(conversion_expected.association):
            raise DiagramIncompleteError("el nombre de la entidad asociativa no coincide", response)
        requested = {(_key(name), data_type) for name, data_type in conversion_expected.attributes}
        received = {(_key(attribute.name), attribute.dataType) for attribute in conversion.attributes}
        if received != requested:
            raise DiagramIncompleteError("los atributos propios no coinciden exactamente con los solicitados", response)

    expected = extract_expectation(prompt, diagram)
    response = _sanitize_requested_operations(prompt, expected, response)
    existing_entities = {_key(entity.name) for entity in diagram.entities} if diagram else set()
    expected_new = {_key(name) for name in expected.entity_names if _key(name) not in existing_entities}
    added_entities = [operation.entity.name for operation in response.operations if isinstance(operation, AddEntity)]
    if conversion_expected is not None:
        added_entities.append(conversion_expected.association)
    available = {_key(entity.name) for entity in diagram.entities} if diagram else set()
    available.update(_key(name) for name in added_entities)
    if conversion_expected is not None:
        available.add(_key(conversion_expected.association))

    if expected.new_entity_count is not None and len(added_entities) < expected.new_entity_count:
        raise DiagramIncompleteError(
            f"se solicitaron {expected.new_entity_count} entidades nuevas y se recibieron {len(added_entities)}",
            response,
        )
    if expected.entity_names:
        missing = [name for name in expected.entity_names if _key(name) not in available]
        if missing:
            raise DiagramIncompleteError("faltan entidades solicitadas: " + ", ".join(missing), response)

    requested_deletes = [
        name for name in _requested_deleted_entities(_plain(prompt))
        if not diagram or _key(name) in existing_entities
    ]
    deleted_in_ops = {_key(op.entityName) for op in response.operations if isinstance(op, DeleteEntity)}
    missing_deletes = [name for name in requested_deletes if _key(name) not in deleted_in_ops]
    if missing_deletes:
        raise DiagramIncompleteError("falta eliminar entidades solicitadas: " + ", ".join(missing_deletes), response)

    # Keep full definitions rather than only names: completeness includes
    # explicitly requested data types and primary-key properties.
    attributes: dict[str, dict[str, object]] = {}
    if diagram:
        for entity in diagram.entities:
            attributes[_key(entity.name)] = {
                _key(attribute.name): attribute for attribute in entity.attributes
            }
    entity_positions: dict[str, int] = {}
    for index, operation in enumerate(response.operations):
        if isinstance(operation, AddEntity):
            entity_key = _key(operation.entity.name)
            entity_positions[entity_key] = index
            attributes[entity_key] = {
                _key(attribute.name): attribute for attribute in operation.entity.attributes
            }
        elif isinstance(operation, AddAttribute):
            attributes.setdefault(_key(operation.entityName), {})[
                _key(operation.attribute.name)
            ] = operation.attribute
        elif isinstance(operation, ConvertManyToManyAssociation):
            attributes[_key(operation.conversion.associationEntityName)] = {
                _key(attribute.name): attribute for attribute in operation.conversion.attributes
            }
        elif isinstance(operation, CreateAssociation):
            attributes[_key(operation.association.associationEntityName)] = {
                _key(attribute.name): attribute for attribute in operation.association.attributes
            }

    missing_attributes = [
        f"{entity}.{attribute}"
        for entity, attribute in expected.attribute_targets
        if _key(attribute) not in attributes.get(_key(entity), {})
    ]
    if missing_attributes:
        raise DiagramIncompleteError(
            "faltan atributos solicitados: " + ", ".join(missing_attributes),
            response,
        )

    for (entity, name), (expected_type, requires_pk) in _typed_attribute_expectations(prompt, expected).items():
        actual = attributes[entity][name]
        if actual.dataType != expected_type:
            raise DiagramIncompleteError(
                f"tipo incorrecto para {entity}.{name}: se solicito {expected_type}", response,
            )
        if requires_pk and (not actual.primaryKey or actual.nullable):
            raise DiagramIncompleteError(
                f"{entity}.{name} debe ser PK y no nullable", response,
            )

    relations: list[tuple[str, str]] = []
    if diagram:
        relations.extend((_key(item.sourceEntity), _key(item.targetEntity)) for item in diagram.relationships)
    relations.extend((_key(operation.relationship.sourceEntity), _key(operation.relationship.targetEntity))
                     for operation in response.operations if isinstance(operation, AddRelationship))
    relations.extend((_key(operation.association.sourceEntity), _key(operation.association.targetEntity))
                     for operation in response.operations if isinstance(operation, CreateAssociation))
    for source, target in expected.relationship_pairs:
        pair = {_key(source), _key(target)}
        if not any({_source, _target} == pair for _source, _target in relations):
            raise DiagramIncompleteError(f"falta la relacion solicitada entre {source} y {target}", response)

    # Verificar la cardinalidad cuando el usuario solicita N:M.
    if conversion_expected is None and re.search(
        r"\bn\s*:\s*m\b|\bmuchos\s+a\s+muchos\b",
        _plain(prompt),
    ):
        all_relationships = []

        if diagram:
            all_relationships.extend(diagram.relationships)

        all_relationships.extend(
            operation.relationship
            for operation in response.operations
            if isinstance(operation, AddRelationship)
        )

        association_pairs = [
            {_key(operation.association.sourceEntity), _key(operation.association.targetEntity)}
            for operation in response.operations if isinstance(operation, CreateAssociation)
        ]

        many_cardinalities = {"ZERO_MANY", "ONE_MANY"}

        for source, target in expected.relationship_pairs:
            requested_pair = {_key(source), _key(target)}

            correct_relationship = requested_pair in association_pairs or any(
                {
                    _key(relationship.sourceEntity),
                    _key(relationship.targetEntity),
                } == requested_pair
                and relationship.sourceCardinality in many_cardinalities
                and relationship.targetCardinality in many_cardinalities
                for relationship in all_relationships
            )

            if not correct_relationship:
                raise DiagramIncompleteError(
                    f"la relacion entre {source} y {target} debe ser N:M",
                    response,
                )
            
    for index, operation in enumerate(response.operations):
        if not isinstance(operation, AddRelationship):
            continue
        for endpoint in (operation.relationship.sourceEntity, operation.relationship.targetEntity):
            endpoint_key = _key(endpoint)
            if endpoint_key in expected_new and entity_positions.get(endpoint_key, index + 1) >= index:
                raise DiagramIncompleteError(
                    f"la entidad {endpoint} debe crearse antes de la relacion que la referencia",
                    response,
                )
    return response
