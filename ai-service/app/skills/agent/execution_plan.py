"""Bounded command planning over authorized names; not a new UML executor.

Known grammar yields an exact plan for the existing operations. Other commands
stay with the provider + completeness gates, never a guessed partial plan.
"""
import re
from dataclasses import dataclass

from app.schemas.diagram import DiagramContext, InterpretResponse
from app.schemas.data_types import normalize_data_type
from app.skills.diagram.semantic_safety import CREATE, DELETE, ENTITY, PREFIX, named, plain, statements

TOKEN = r'''(?:"[^"]+"|'[^']+'|[\w-]+)'''
OWNER = ENTITY + rf"(?P<owner>{TOKEN})"
ATTR = rf"(?P<attribute>{TOKEN})"
TYPE = r"(?:(?:de\s+)?tipo\s+)?(?P<datatype>string|varchar|text|long|bigint|integer|int|double|decimal|boolean|bool|date|datetime|timestamp)"


class Clarification(ValueError):
    pass


@dataclass(frozen=True)
class ExecutionPlan:
    operations: tuple[dict, ...] = ()
    reason: str | None = None


def _unquote(value: str) -> str:
    return value.strip().strip('"\'')


def _resolve(name: str, diagram: DiagramContext):
    matches = [e for e in diagram.entities if plain(e.name) == plain(_unquote(name))]
    if len(matches) != 1:
        raise Clarification(f"El clasificador {_unquote(name)} no existe o su nombre es ambiguo. Indica un clasificador existente; no creare otro sin autorizacion.")
    return matches[0]


def _surface(name: str, original: str) -> str:
    """Keep literal spelling/case from the human request, never the model."""
    name = _unquote(name)
    for match in re.finditer(r'''"([^"]+)"|'([^']+)'|[\w-]+''', original):
        value = match.group(1) or match.group(2) or match.group(0)
        if plain(value) == plain(name):
            return value
    return name


def _attribute(entity, name: str):
    matches = [a for a in entity.attributes if plain(a.name) == plain(_unquote(name))]
    if len(matches) != 1:
        raise Clarification(f"No hay un atributo unico {entity.name}.{_unquote(name)}.")
    return matches[0]


def _alias(name: str) -> str:
    value = re.escape(plain(name))
    return rf"(?:{value}|{value}s|{value}es)"


def cardinalities(clause: str, source: str, target: str) -> tuple[str, str]:
    """Multiplicity at an end counts that classifier per ONE opposite classifier."""
    counts: dict[str, str] = {}
    def put(end: str, value: str):
        if end in counts and counts[end] != value:
            raise Clarification("La instruccion contiene cardinalidades contradictorias.")
        counts[end] = value

    ratio = re.search(r"\b(1|n|m)\s*:\s*(1|n|m)\b", clause)
    if ratio:
        put(source, "ONE_ONE" if ratio[1] == "1" else "ZERO_MANY")
        put(target, "ONE_ONE" if ratio[2] == "1" else "ZERO_MANY")
    elif re.search(r"\bmuchos a muchos\b", clause):
        put(source, "ZERO_MANY"); put(target, "ZERO_MANY")
    elif re.search(r"\buno a muchos\b", clause):
        put(source, "ONE_ONE"); put(target, "ZERO_MANY")
    elif re.search(r"\buno a uno\b", clause):
        put(source, "ONE_ONE"); put(target, "ONE_ONE")
    for owner, counted in ((source, target), (target, source)):
        # Explicit endpoint notation: Producto 0..N, Categoria 1..1.
        explicit = re.search(
            rf"\b{_alias(counted)}\s*(?:con\s+)?\(?\s*"
            r"(0\.\.1|1\.\.1|0\.\.(?:n|\*)|1\.\.(?:n|\*))\s*\)?",
            clause,
        )
        if explicit:
            put(counted, {"0..1": "ZERO_ONE", "1..1": "ONE_ONE", "0..n": "ZERO_MANY", "0..*": "ZERO_MANY", "1..n": "ONE_MANY", "1..*": "ONE_MANY"}[explicit[1]])
        for match in re.finditer(rf"\b(?:un|una|cada|el|la)\s+{_alias(owner)}\b(.{{0,160}}?)\b{_alias(counted)}\b", clause):
            text = match[1]
            if not re.search(r"\b(?:tiene|tienen|tener|pertenece|pertenecen|contiene|contienen|si o si|puede|pueda|debe|solo|solamente)\b", text):
                continue
            many = re.search(r"\b(?:muchos|muchas|varios|varias|hartos|hartas|artos|artas)\b|\bo mas\b", text)
            minimum = re.search(r"\bal menos (?:un|una|uno|1)\b|\b(?:un|uno|una|1) o mas\b", text)
            optional = re.search(r"\b(?:puede|pueda|opcional|cero|0)\b", text)
            required = re.search(r"\b(?:obligatoriamente|obligatorio|obligatoria|exactamente|si o si|debe|solo|solamente)\b", text)
            if many or minimum:
                put(counted, "ONE_MANY" if minimum else "ZERO_MANY")
            elif re.search(r"\b(?:una|un|uno|uan|1)\b", text):
                put(counted, "ONE_ONE" if required or not optional else "ZERO_ONE")
    if source not in counts or target not in counts:
        raise Clarification(f"Indica cuantas instancias de {source} corresponden a una de {target} y viceversa (0..1, 1..1, 0..N o 1..N).")
    return counts[source], counts[target]


def _relationship(clause: str, diagram: DiagramContext):
    # Una creacion de clase con instrucciones adicionales
    # no debe interpretarse como una relacion aislada.
    if (
        re.match(
            rf"{PREFIX}{CREATE}\s+"
            r"(?:(?:una?|la|nueva|nuevo)\s+)*clase\b",
            clause,
        )
        and re.search(r"\brelacion\b", clause)
    ):
        return None
    prefix = rf"{PREFIX}(?P<verb>{CREATE}|{DELETE}|relaciona|relacionar|conecta|conectar|modifica|actualiza|cambia)\s+"
    multiplicity = r"(?:0\.\.1|1\.\.1|0\.\.(?:n|\*)|1\.\.(?:n|\*))"

    pair = re.match(
        prefix
        + r"(?:(?:una|la|un)\s+)?"
        + r"(?:relacion\s+(?:de\s+)?(?:(?:1|n|m)\s*:\s*(?:1|n|m)\s+)?(?:entre|en)\s+)?"
        + ENTITY
        + rf"(?P<source>{TOKEN})\s*(?:\(\s*{multiplicity}\s*\))?\s+(?:y|con)\s+"
        + ENTITY
        + rf"(?P<target>{TOKEN})\s*(?:\(\s*{multiplicity}\s*\))?(?P<tail>.*)$",
        clause,
    )
    if not pair or not (re.search(r"\brelacion\b", clause) or pair["verb"].startswith(("relacion", "conect"))):
        return None
    source = _resolve(pair["source"], diagram).name
    target = _resolve(pair["target"], diagram).name
    if source == target:
        raise Clarification("Las autorrelaciones no estan soportadas.")
    existing = [r for r in diagram.relationships if {plain(r.sourceEntity), plain(r.targetEntity)} == {plain(source), plain(target)}]
    if len(existing) > 1:
        raise Clarification("Hay varias relaciones entre esos clasificadores. Indica cual deseas modificar.")
    reference = {"sourceEntity": source, "targetEntity": target}
    if re.fullmatch(DELETE, pair["verb"]):
        if not existing:
            raise Clarification(f"No existe una relacion entre {source} y {target}.")
        rel = existing[0]
        reference = {"sourceEntity": rel.sourceEntity, "targetEntity": rel.targetEntity}
        if rel.id: reference["id"] = rel.id
        if rel.kind: reference["kind"] = rel.kind
        if rel.name: reference["name"] = rel.name
        return [{"type": "DELETE_RELATIONSHIP", "relationship": reference}]
    sc, tc = cardinalities(clause, source, target)
    if existing:
        rel = existing[0]
        direct = plain(rel.sourceEntity) == plain(source)
        old = (rel.sourceCardinality, rel.targetCardinality) if direct else (rel.targetCardinality, rel.sourceCardinality)
        if old == (sc, tc) and (rel.kind or "ASSOCIATION") == "ASSOCIATION":
            return []
        if pair["verb"] not in {"modifica", "actualiza", "cambia"}:
            raise Clarification("Ya existe una relacion con otro tipo o cardinalidades. Solicita explicitamente su modificacion; no se creara un duplicado.")
        if rel.kind not in {None, "ASSOCIATION"}:
            raise Clarification("Especifica el tipo UML y los extremos de la relacion existente antes de modificarla.")
        reference = {"sourceEntity": rel.sourceEntity, "targetEntity": rel.targetEntity,
                     "sourceCardinality": sc if direct else tc, "targetCardinality": tc if direct else sc}
        if rel.id: reference["id"] = rel.id
        if rel.name: reference["name"] = rel.name
        return [{"type": "UPDATE_RELATIONSHIP", "relationship": reference}]
    if pair["verb"] in {"modifica", "actualiza", "cambia"}:
        raise Clarification("La relacion que deseas modificar no existe.")
    return [{"type": "ADD_RELATIONSHIP", "relationship": {**reference, "sourceCardinality": sc, "targetCardinality": tc}}]

def _class_command(
    clause: str,
    diagram: DiagramContext,
    original: str,
):
    pattern = (
        rf"{PREFIX}{CREATE}\s+"
        r"(?:(?:una?|la|nueva|nuevo)\s+)*"
        r"(?P<kind>clase|entidad)\s+"
        r"(?:(?:con\s+(?:el\s+)?nombre(?:\s+de)?"
        r"|llamad[ao]|denominad[ao])\s+)?"
        rf"(?P<name>{TOKEN})"
    )

    match = re.fullmatch(pattern, clause)
    if match is None:
        return None

    name = _surface(match["name"], original)
    kind = "CLASS" if match["kind"] == "clase" else "ENTITY"

    existing = [
        entity for entity in diagram.entities
        if plain(entity.name) == plain(name)
    ]

    if existing:
        if len(existing) != 1 or existing[0].kind != kind:
            raise Clarification(
                f"Ya existe un clasificador llamado {name}. "
                "No se creara otro con un tipo distinto."
            )
        return []

    return [{
        "type": "ADD_ENTITY",
        "entity": {
            "name": name,
            "kind": kind,
            "attributes": [],
            "methods": [],
        },
    }]

def _class_relationship_command(
    clause: str,
    diagram: DiagramContext,
    original: str,
):
    multiplicity = r"(?:0\.\.1|1\.\.1|0\.\.(?:n|\*)|1\.\.(?:n|\*))"

    pattern = (
        rf"{PREFIX}{CREATE}\s+"
        r"(?:(?:una?|la|nueva|nuevo)\s+)*clase\s+"
        r"(?:(?:con\s+(?:el\s+)?nombre(?:\s+de)?"
        r"|llamad[ao]|denominad[ao])\s+)?"
        rf"(?P<new_name>{TOKEN})\s+y\s+"
        r"(?:hace|crea|agrega|genera|define|establece)\s+"
        r"(?:(?:una|la)\s+)?relacion\s+con\s+"
        r"(?:(?:el|la)\s+)?(?:(?:clase|entidad)\s+)?"
        rf"(?P<other_name>{TOKEN})\s+de\s*\(\s*"
        rf"(?P<src>{multiplicity})\s*\)\s+a\s*\(\s*"
        rf"(?P<dst>{multiplicity})\s*\)"
    )

    match = re.fullmatch(pattern, clause)
    if match is None:
        return None

    new_name = _surface(match["new_name"], original)
    other = _resolve(match["other_name"], diagram)

    if any(
        plain(entity.name) == plain(new_name)
        for entity in diagram.entities
    ):
        raise Clarification(
            f"La clase {new_name} ya existe. "
            "Solicita solamente la relacion o utiliza otro nombre."
        )

    card_map = {
        "0..1": "ZERO_ONE",
        "1..1": "ONE_ONE",
        "0..n": "ZERO_MANY",
        "0..*": "ZERO_MANY",
        "1..n": "ONE_MANY",
        "1..*": "ONE_MANY",
    }

    return [
        {
            "type": "ADD_ENTITY",
            "entity": {
                "name": new_name,
                "kind": "CLASS",
                "attributes": [],
                "methods": [],
            },
        },
        {
            "type": "ADD_RELATIONSHIP",
            "relationship": {
                "sourceEntity": new_name,
                "targetEntity": other.name,
                "sourceCardinality": card_map[match["src"]],
                "targetCardinality": card_map[match["dst"]],
            },
        },
    ]

def _attribute_command(clause: str, diagram: DiagramContext, original: str):
    patterns = [
        rf"{PREFIX}{CREATE}\s+(?:(?:un|el|nuevo)\s+)*(?:atributo|campo)\s+(?:en|a|para)\s+{OWNER}\s+(?:con\s+(?:el\s+)?nombre\s+(?:de\s+)?|llamado\s+){ATTR}\s+{TYPE}",
        rf"{PREFIX}{CREATE}\s+(?:(?:un|el|nuevo)\s+)*(?:atributo|campo)\s+{ATTR}\s+{TYPE}\s+(?:a|en|de)\s+{OWNER}",
        rf"{PREFIX}{CREATE}\s+(?:a|en)\s+{OWNER}\s+(?:(?:el|un)\s+)?(?:atributo|campo)\s+{ATTR}\s+{TYPE}",
    ]
    for pattern in patterns:
        match = re.fullmatch(pattern, clause)
        if not match: continue
        entity = _resolve(match["owner"], diagram)
        attr_name = _surface(match["attribute"], original)
        if plain(attr_name) in {"en", "de", "el", "la", "tipo", "nombre"}:
            raise Clarification("Indica el nombre del atributo entre comillas.")
        datatype = normalize_data_type(match["datatype"])
        existing = [a for a in entity.attributes if plain(a.name) == plain(attr_name)]
        if existing:
            if len(existing) == 1 and existing[0].dataType == datatype: return []
            raise Clarification(f"El atributo {entity.name}.{attr_name} ya existe con otro tipo o es ambiguo.")
        return [{"type": "ADD_ATTRIBUTE", "entityName": entity.name, "attribute": {"name": attr_name,
                 "dataType": datatype, "primaryKey": False, "nullable": True}}]
    match = re.fullmatch(rf"{PREFIX}{DELETE}\s+(?:(?:el|un)\s+)?(?:atributo|campo)\s+{ATTR}\s+(?:de|en)\s+{OWNER}", clause)
    if match:
        entity = _resolve(match["owner"], diagram); attr = _attribute(entity, match["attribute"])
        return [{"type": "DELETE_ATTRIBUTE", "entityName": entity.name, "attributeName": attr.name}]
    return None


def plan_execution(message: str, diagram: DiagramContext) -> ExecutionPlan | None:
    operations = []

    try:
        clauses = statements(message)

        # Resolver conjuntamente la creacion de una clase
        # y una relacion que depende de ella.
        combined = _class_relationship_command(
            " y ".join(clauses),
            diagram,
            message,
        )

        if combined is not None:
            operations.extend(combined)
        else:
            for clause in clauses:
                planned = _class_command(
                    clause, diagram, message
                )

                if planned is None:
                    planned = _attribute_command(
                        clause, diagram, message
                    )

                if planned is None:
                    planned = _relationship(
                        clause, diagram
                    )

                # Una orden desconocida vuelve al camino
                # de Ollama; no aplicamos lotes parciales.
                if planned is None:
                    return None

                operations.extend(planned)
        if len(operations) > 50:
            raise Clarification("El lote supera el limite de 50 operaciones.")
        # Reuse the existing operation schema instead of another executor/contract.
        validated = InterpretResponse.model_validate({"operations": operations})
        return ExecutionPlan(tuple(op.model_dump(exclude_none=True) for op in validated.operations))
    except Clarification as error:
        return ExecutionPlan(reason=str(error))


def matches_plan(response: InterpretResponse, plan: ExecutionPlan) -> bool:
    def canonical(value):
        if isinstance(value, dict): return {k: canonical(v) for k, v in value.items() if v is not None}
        if isinstance(value, list): return [canonical(v) for v in value]
        return plain(value) if isinstance(value, str) else value
    expected = InterpretResponse.model_validate({"operations": list(plan.operations)})
    actual = response.model_dump(exclude_none=True)
    wanted = expected.model_dump(exclude_none=True)
    if len(actual["operations"]) != len(wanted["operations"]): return False
    for received, requested in zip(actual["operations"], wanted["operations"]):
        if received.get("type") == requested.get("type") == "ADD_RELATIONSHIP":
            for field in ("name", "joinTableName"):
                if field not in requested["relationship"]: received["relationship"].pop(field, None)
            received["relationship"].setdefault("kind", "ASSOCIATION")
            requested["relationship"].setdefault("kind", "ASSOCIATION")
    return canonical(actual) == canonical(wanted)


def action_coverage_error(message: str, response: InterpretResponse) -> str | None:
    """Check every explicit clause, including CRUD already implemented outside this planner."""
    remaining = list(response.operations)
    for clause in statements(message):
        verb = re.match(rf"{PREFIX}(?P<verb>{CREATE}|{DELETE}|renombra|cambia|modifica|actualiza|marca|establece)\s+", clause)
        if not verb: continue
        tail = clause[verb.end():]
        noun = re.match(r"(?:(?:un|una|nuevo|nueva|el|la|los|las)\s+)*(parametro|metodo|literal|atributo|campo|clase|entidad|interfaz|enumeracion|relacion)\b", tail)
        if not noun: continue
        create = bool(re.fullmatch(CREATE, verb["verb"]))
        delete = bool(re.fullmatch(DELETE, verb["verb"]))
        subject = noun[1]
        family = {"parametro": "METHOD_PARAMETER", "metodo": "METHOD", "literal": "ENUM_LITERAL",
                  "atributo": "ATTRIBUTE", "campo": "ATTRIBUTE", "relacion": "RELATIONSHIP"}.get(subject, "ENTITY")
        if create: allowed = {"ADD_" + family}
        elif delete: allowed = {"DELETE_" + family}
        elif family == "ENTITY": allowed = {"RENAME_ENTITY", "UPDATE_CLASSIFIER"}
        elif family == "ATTRIBUTE": allowed = {"RENAME_ATTRIBUTE", "CHANGE_ATTRIBUTE_TYPE", "SET_ATTRIBUTE_PRIMARY_KEY", "SET_ATTRIBUTE_NULLABLE", "SET_ATTRIBUTE_VISIBILITY", "SET_ATTRIBUTE_STATIC"}
        else: allowed = {"UPDATE_" + family, "RENAME_" + family}
        matches = [op for op in remaining if op.type in allowed]
        if not matches: return f"Falta una operacion para la instruccion: {clause[:180]}"
        remaining.remove(matches[0])
    return None
