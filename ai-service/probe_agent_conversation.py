import asyncio
import time

from app.core.config import get_settings
from app.schemas.agent import AgentAskRequest
from app.services.agent_service import AgentService
from app.services.ai_service import AIService


CONTEXT = {
    "projectId": 7,
    "projectName": "PruebaIA",
    "projectDescription": "Sistema académico para gestionar alumnos, materias, inscripciones y docentes.",
    "diagramId": 1,
    "selectedNodeId": None,
    "selectedEdgeId": None,
    "entities": [
        {"id": "alumno", "name": "Alumno", "attributes": [
            {"name": "id", "dataType": "Long", "primaryKey": True, "nullable": False},
            {"name": "nombre", "dataType": "String", "primaryKey": False, "nullable": False},
        ]},
        {"id": "materia", "name": "Materia", "attributes": [
            {"name": "id", "dataType": "Long", "primaryKey": True, "nullable": False},
            {"name": "nombre", "dataType": "String", "primaryKey": False, "nullable": False},
        ]},
        {"id": "inscripcion", "name": "Inscripcion", "attributes": [
            {"name": "id", "dataType": "Long", "primaryKey": True, "nullable": False},
            {"name": "fecha", "dataType": "Date", "primaryKey": False, "nullable": False},
        ]},
        {"id": "docente", "name": "Docente", "attributes": [
            {"name": "id", "dataType": "Long", "primaryKey": True, "nullable": False},
        ]},
        {"id": "profesor", "name": "Profesor", "attributes": [
            {"name": "id", "dataType": "Long", "primaryKey": True, "nullable": False},
        ]},
    ],
    "relationships": [
        {"id": "r1", "sourceNodeId": "alumno", "targetNodeId": "inscripcion",
         "sourceEntity": "Alumno", "targetEntity": "Inscripcion",
         "sourceCardinality": "ONE_ONE", "targetCardinality": "ZERO_MANY"},
        {"id": "r2", "sourceNodeId": "materia", "targetNodeId": "inscripcion",
         "sourceEntity": "Materia", "targetEntity": "Inscripcion",
         "sourceCardinality": "ONE_ONE", "targetCardinality": "ZERO_MANY"},
    ],
    "associations": [{
        "entityName": "Inscripcion", "tableName": "alumno_materia",
        "endpointEntityNames": ["Alumno", "Materia"],
        "structuralRelationshipIds": ["r1", "r2"],
    }],
    "recentEvents": [],
}
QUESTIONS = [
    "¿Qué podría agregar a mi diagrama?",
    "¿Por qué recomendarías eso?",
    "¿Qué diferencia habría entre Docente y Profesor?",
    "¿Qué atributos serían útiles para Inscripcion?",
]


async def main() -> None:
    service = AgentService(AIService.from_settings(get_settings()))
    history: list[dict[str, str]] = []
    for question in QUESTIONS:
        started = time.perf_counter()
        result = await service.ask(AgentAskRequest(
            message=question, context=CONTEXT, conversation=history[-10:],
        ))
        elapsed = time.perf_counter() - started
        print(f"QUESTION: {question}")
        print(f"SECONDS: {elapsed:.2f}")
        print(f"OPERATIONS: {len(result.operations)}")
        print(f"ANSWER: {result.answer}")
        history.extend([
            {"role": "user", "text": question},
            {"role": "agent", "text": result.answer},
        ])


asyncio.run(main())
