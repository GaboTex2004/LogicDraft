import json
import re
import unicodedata

from app.schemas.runtime import RuntimeInterpretResponse, RuntimeSchema
from app.services.ai_service import AIService
from app.services.llm_json_parser import parse_llm_json_response


_PHONE_FIELD_NAMES = frozenset({
    "telefono", "telefonofijo", "telefonomovil", "numerotelefono",
    "celular", "movil", "phone", "phonenumber", "mobilephone",
})
_PHONE_SEQUENCE = re.compile(
    r"(?iu)\b(?:tel[eé]fono|celular|m[oó]vil|phone|n[uú]mero(?:\s+de\s+tel[eé]fono)?)\b"
    r"[^\d]{0,30}(\d+(?:\s*,\s*\d+)*)"
)


def _identifier(value: str) -> str:
    decomposed = unicodedata.normalize("NFD", value.casefold())
    return "".join(char for char in decomposed if unicodedata.category(char) != "Mn" and char.isalnum())


def _is_phone_name(value: str) -> bool:
    return _identifier(value) in _PHONE_FIELD_NAMES


def _phone_value(value: object, field_name: str, source_text: str) -> tuple[str | None, str | None]:
    if not isinstance(value, str) or not value.strip():
        return None, f"Revisa {field_name}: un telefono debe conservarse como texto."
    raw = value.strip()
    normalized = raw
    evidence = ["".join(re.findall(r"\d+", match.group(1))) for match in _PHONE_SEQUENCE.finditer(source_text)]
    if "," in raw:
        if re.fullmatch(r"\d+(?:\s*,\s*\d+)+", raw) is None:
            return None, f"Revisa {field_name}: la secuencia telefonica es ambigua."
        normalized = "".join(re.findall(r"\d+", raw))
        occurrences = evidence.count(normalized)
        if occurrences != 1:
            return None, f"Revisa {field_name}: no pude confirmar una unica secuencia telefonica en el texto."
    elif evidence.count(raw) != 1:
        return None, f"Revisa {field_name}: el valor no aparece como telefono en la instruccion y no sera inventado."
    return normalized, None


class RuntimeAIService:
    """Interpret CREATE intent only. Spring alone validates and persists records."""

    def __init__(self, ai: AIService) -> None:
        self._ai = ai

    async def interpret(self, text: str, schema: RuntimeSchema) -> RuntimeInterpretResponse:
        context = schema.model_dump(mode="json")
        prompt = (
            "Interpreta una instruccion para registrar datos. Responde SOLO un objeto JSON. "
            "No ejecutes SQL, codigo ni comandos. Solo CREATE. "
            "Usa nombres exactos de entidades, atributos y relaciones del esquema. "
            "No incluyas claves con valor null. No incluyas PK generated dentro de values. "
            "Si la entidad no tiene relaciones, relations debe ser un objeto vacio. "
            "No inventes IDs: si el usuario nombra una entidad relacionada, coloca el nombre "
            "en relations con la clave <relacion>Id; no en values. Para relaciones multiple=true usa una lista "
            "de nombres en relations, preservando exactamente los nombres mencionados. "
            "Los telefonos STRING deben conservar todos los digitos como texto, incluidos ceros iniciales; "
            "si vienen en grupos separados por comas conserva la secuencia literal y no inventes digitos. "
            "Si falta informacion obligatoria responde status NEEDS_CLARIFICATION y una pregunta breve. "
            "Si reconoces CREATE conserva entity, values y relations parciales para que Spring los valide; "
            "no cambies un CREATE incompleto a otra operacion. "
            "Si no es CREATE o no se entiende, responde status NOT_UNDERSTOOD. "
            "Para CREATE entendido responde status INTERPRETED, operation CREATE, entity, "
            "values (atributos y solo IDs explicitamente proporcionados) y relations "
            "(nombres semanticos de relaciones).\n"
            f"Esquema: {json.dumps(context, ensure_ascii=False, sort_keys=True)}\n"
            f"Instruccion: {text}"
        )
        response_schema = {
            "type": "object",
            "properties": {
                "status": {"type": "string", "enum": ["INTERPRETED", "NEEDS_CLARIFICATION", "NOT_UNDERSTOOD"]},
                "operation": {"type": ["string", "null"]},
                "entity": {"type": ["string", "null"]},
                "values": {"type": ["object", "null"]},
                "relations": {"type": ["object", "null"]},
                "message": {"type": ["string", "null"]},
                "missingFields": {"type": "array", "items": {"type": "string"}},
            },
            "required": ["status", "operation", "entity", "values", "relations", "message", "missingFields"],
            "additionalProperties": False,
        }
        raw = await self._ai.generate(prompt, response_schema)
        try:
            parsed = parse_llm_json_response(raw)
            result = RuntimeInterpretResponse.model_validate(parsed)
        except (ValueError, TypeError):
            return RuntimeInterpretResponse(status="NOT_UNDERSTOOD", message="No pude interpretar el comando.")
        if result.operation is not None and result.operation != "CREATE":
            return RuntimeInterpretResponse(
                status="NOT_UNDERSTOOD",
                message=f"La operacion {result.operation} no esta permitida; la IA solo registra datos.",
            )
        if result.status in {"INTERPRETED", "NEEDS_CLARIFICATION"} and result.entity is not None:
            entity = next((item for item in schema.entities if item.name == result.entity), None)
            if entity is None:
                return RuntimeInterpretResponse(
                    status="NOT_UNDERSTOOD",
                    message=f"La entidad {result.entity} no existe en la aplicacion.",
                )
            values = result.values or {}
            relations = result.relations or {}
            generated = {field.name for field in entity.fields if field.generated}
            relation_names = {relation.name for relation in entity.relations}
            result.values = {key: value for key, value in values.items() if key not in generated}
            result.relations = {
                key: value for key, value in relations.items()
                if key in relation_names and value is not None
            }
            relation_by_name = {relation.name: relation for relation in entity.relations}
            for name, value in result.relations.items():
                relation = relation_by_name[name]
                if relation.multiple and (not isinstance(value, list)
                                          or not value
                                          or any(not isinstance(item, str) or not item.strip() for item in value)):
                    return RuntimeInterpretResponse(
                        status="NEEDS_CLARIFICATION", operation="CREATE", entity=entity.name,
                        values=result.values, relations=result.relations,
                        message=f"Indica una lista clara de nombres existentes para {name}.",
                        missingFields=[name],
                    )
            for field in entity.fields:
                if not _is_phone_name(field.name) or result.values.get(field.name) is None:
                    continue
                if field.type != "STRING":
                    return RuntimeInterpretResponse(
                        status="NEEDS_CLARIFICATION", operation="CREATE", entity=entity.name,
                        values=result.values, relations=result.relations,
                        message=f"Revisa {field.name}: debe estar definido como STRING para conservar el telefono.",
                        missingFields=[field.name],
                    )
                normalized_phone, phone_error = _phone_value(result.values[field.name], field.name, text)
                if phone_error is not None:
                    return RuntimeInterpretResponse(
                        status="NEEDS_CLARIFICATION", operation="CREATE", entity=entity.name,
                        values=result.values, relations=result.relations,
                        message=phone_error, missingFields=[field.name],
                    )
                result.values[field.name] = normalized_phone
            missing = [
                field.name for field in entity.fields
                if not field.generated and not field.nullable
                and (field.name not in result.values or result.values[field.name] is None
                     or isinstance(result.values[field.name], str) and not result.values[field.name].strip())
            ]
            missing.extend(
                relation.name for relation in entity.relations
                if not relation.nullable
                and result.values.get(relation.name) is None
                and result.relations.get(relation.name) is None
            )
            if missing:
                fields = ", ".join(missing)
                return RuntimeInterpretResponse(
                    status="NEEDS_CLARIFICATION",
                    operation="CREATE",
                    entity=entity.name,
                    values=result.values,
                    relations=result.relations,
                    message=f"Falta informacion obligatoria para registrar {entity.name}: {fields}.",
                    missingFields=missing,
                )
            if result.status == "NEEDS_CLARIFICATION":
                return result
            result.status = "INTERPRETED"
            result.operation = "CREATE"
        elif result.status == "INTERPRETED":
            return RuntimeInterpretResponse(
                status="NEEDS_CLARIFICATION",
                operation="CREATE",
                message="Indica que entidad quieres registrar.",
            )
        return result
