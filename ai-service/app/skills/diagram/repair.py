import json

from app.schemas.diagram import DiagramContext, InterpretResponse


def build_repair_prompt(
        original_prompt: str,
        error_summary: str,
        previous: InterpretResponse | None,
        diagram: DiagramContext | None) -> str:
    parts = [
        "Repara una respuesta estructurada de LogicDraft.",
        "Devuelve SOLO JSON válido que cumpla el schema proporcionado.",
        "Devuelve el lote COMPLETO de operaciones, no solamente las operaciones faltantes.",
        "Incluye todas las entidades, todos sus atributos y todas las relaciones solicitadas.",
        "No uses Markdown, explicaciones, SQL ni campos adicionales.",
        "Tipos permitidos: String, Long, Integer, Double, Boolean, Date, DateTime.",
        "No recrees entidades que ya existan en el contexto.",
        "No inventes entidades, atributos ni identificadores. Si no se solicitaron atributos, usa attributes:[].",
        "Error detectado: " + error_summary,
        "Solicitud original (JSON): " + json.dumps(original_prompt, ensure_ascii=False),
    ]
    if diagram is not None:
        parts.append("Diagrama existente (JSON): " + diagram.model_dump_json())
        if any(word in original_prompt.casefold() for word in ("convierte", "convertir", "transforma", "transformar")):
            candidates = [
                {
                    "relationshipId": relationship.id,
                    "sourceEntity": relationship.sourceEntity,
                    "targetEntity": relationship.targetEntity,
                }
                for relationship in diagram.relationships
                if relationship.id is not None
                and relationship.sourceCardinality in ("ZERO_MANY", "ONE_MANY")
                and relationship.targetCardinality in ("ZERO_MANY", "ONE_MANY")
            ]
            parts.extend([
                "La conversion debe ser una unica operacion CONVERT_MANY_TO_MANY_ASSOCIATION con "
                "conversion={relationshipId,sourceEntity,targetEntity,associationEntityName,attributes}.",
                "Relaciones N:M convertibles (JSON): " + json.dumps(candidates, ensure_ascii=False),
                "Copia literalmente relationshipId y los extremos de una unica entrada; no uses placeholders. "
                "Cada atributo propio usa {name,dataType,primaryKey:false,nullable}. No incluyas PK ni FK.",
            ])
    if previous is not None:
        parts.append("Lote anterior incompleto (JSON): " + previous.model_dump_json())
    return "\n".join(parts)
