import asyncio
import json
from types import SimpleNamespace
from unittest.mock import AsyncMock, patch

from fastapi import FastAPI
from fastapi.testclient import TestClient
from google.genai import errors as gemini_errors
import httpx
import pytest
from pydantic import ValidationError

from app.api.routes.image import MAX_IMAGE_BYTES, router
from app.schemas.image import ImageDocument, ImageRecognition
from app.services.gemini_vision_service import GeminiVisionService, VISION_PROMPT, gemini_image_schema

PNG_HEADER = b"\x89PNG\r\n\x1a\n"


def make_client() -> TestClient:
    app = FastAPI()
    app.include_router(router)
    return TestClient(app)


def classifier(**extra):
    return dict(type="ADD_CLASSIFIER", name="Cliente", kind="CLASS",
                attributes=[], methods=[], literals=[], **extra)


def interpret(payload):
    client = SimpleNamespace(
        aio=SimpleNamespace(models=SimpleNamespace(generate_content=AsyncMock(
            return_value=SimpleNamespace(text=json.dumps(payload))))), close=lambda: None)
    settings = SimpleNamespace(gemini_api_key="test-only", gemini_model="mock")
    with patch("app.services.gemini_vision_service.get_settings", return_value=settings), \
            patch("app.services.gemini_vision_service.genai.Client", return_value=client):
        result = asyncio.run(GeminiVisionService().interpret(
            b"image", "image/png", diagram=ImageDocument(version=2, nodes=[], edges=[])))
    return result, client


@pytest.mark.parametrize("operation", [
    "DELETE_ENTITY", "DELETE_ATTRIBUTE", "UPDATE_ENTITY", "UPDATE_ATTRIBUTE",
    "RENAME_ENTITY", "RENAME_ATTRIBUTE", "CHANGE_ATTRIBUTE_TYPE", "SET_ATTRIBUTE_PRIMARY_KEY",
    "SET_ATTRIBUTE_NULLABLE", "DELETE_RELATIONSHIP", "UPDATE_RELATIONSHIP",
    "DELETE_ASSOCIATION", "CONVERT_MANY_TO_MANY_ASSOCIATION", "REPLACE_DOCUMENT",
])
def test_rejects_nonadditive_model_operations(operation):
    with pytest.raises(ValidationError):
        interpret({"operations": [{"type": operation, "entityName": "Cliente"}]})


def test_preserves_uml_observations_and_supplies_json_schema():
    value = classifier()
    value["methods"] = [{"name": "buscar", "returnType": "Cliente",
                         "parameters": [{"name": "id", "type": "Long"}]}]
    result, client = interpret({"operations": [value], "warnings": ["Una etiqueta es ilegible"]})
    assert result.operations[0].kind == "CLASS"
    assert result.operations[0].methods[0].parameters[0].type == "Long"
    schema = client.aio.models.generate_content.call_args.kwargs["config"].response_json_schema
    assert schema == gemini_image_schema()


def test_gemini_image_schema_strips_unsupported_json_schema_keywords():
    schema = gemini_image_schema()
    forbidden = {
        "$defs", "$ref", "pattern", "discriminator", "default",
        "additionalProperties", "title", "minLength", "maxLength", "maxItems", "const", "oneOf",
    }

    def inspect_node(node):
        if isinstance(node, dict):
            assert forbidden.isdisjoint(node.keys()), f"Forbidden keys found: {forbidden & node.keys()}"
            if "anyOf" in node:
                assert not any(isinstance(item, dict) and item.get("type") == "null" for item in node["anyOf"])
            for value in node.values():
                inspect_node(value)
        elif isinstance(node, list):
            for item in node:
                inspect_node(item)

    inspect_node(schema)


def test_classifier_allows_omitted_optional_kind_and_lists():
    parsed = ImageRecognition.model_validate_json(
        json.dumps({"operations": [{"type": "ADD_CLASSIFIER", "name": "Producto"}]})
    )
    op = parsed.operations[0]
    assert op.kind is None
    assert op.attributes == []
    assert op.methods == []
    assert op.literals == []


@pytest.mark.parametrize("extra", [{"id": "existing-id"}, {"position": {"x": 0}}, {"replace": True}])
def test_rejects_model_ids_and_replacement_fields(extra):
    with pytest.raises(ValidationError):
        ImageRecognition.model_validate({"operations": [classifier(**extra)]})


def test_image_context_accepts_full_v1_and_v2_without_losing_metadata():
    for version in (1, 2):
        value = ImageDocument(
            version=version,
            nodes=[
                {"id": "e1", "data": {"name": "Auditoria", "kind": "ENTITY", "attributes": []}},
                {"id": "c1", "data": {"name": "Venta", "kind": "CLASS", "attributes": []}},
                {"id": "ac1", "data": {"name": "compra", "kind": "ASSOCIATION_CLASS", "association": {"key": "value"}}},
            ],
            edges=[
                {"id": "r1", "source": "c1", "target": "ac1", "data": {"sourceMultiplicity": "1", "targetMultiplicity": "1..*"}},
            ],
        )
        assert value.model_dump()["nodes"][2]["data"]["association"] == {"key": "value"}


def test_route_accepts_small_and_max_size_valid_images():
    recognition = ImageRecognition(operations=[classifier()])
    for size in (64, MAX_IMAGE_BYTES):
        payload = PNG_HEADER + (b"0" * (size - len(PNG_HEADER)))
        with patch.object(GeminiVisionService, "interpret", new=AsyncMock(return_value=recognition)):
            response = make_client().post(
                "/ai/image/interpret",
                files={"image": ("diagram.png", payload, "image/png")},
                data={"diagram": '{"version":2,"nodes":[],"edges":[]}'},
            )
        assert response.status_code == 200
        assert response.json()["operations"][0]["name"] == "Cliente"


def test_route_rejects_image_exceeding_limit_with_413():
    payload = PNG_HEADER + (b"0" * (MAX_IMAGE_BYTES - len(PNG_HEADER) + 1))
    response = make_client().post(
        "/ai/image/interpret",
        files={"image": ("diagram.png", payload, "image/png")},
        data={"diagram": '{"version":2,"nodes":[],"edges":[]}'},
    )
    assert response.status_code == 413
    assert "5 MB" in response.json()["detail"]


def test_route_rejects_invalid_gemini_response_with_502():
    client = SimpleNamespace(
        aio=SimpleNamespace(models=SimpleNamespace(generate_content=AsyncMock(
            return_value=SimpleNamespace(text='{"operations":[{"type":"INVALID_OP"}]}')))),
        close=lambda: None,
    )
    settings = SimpleNamespace(gemini_api_key="test-only", gemini_model="mock")
    with patch("app.services.gemini_vision_service.get_settings", return_value=settings), \
            patch("app.services.gemini_vision_service.genai.Client", return_value=client):
        response = make_client().post(
            "/ai/image/interpret",
            files={"image": ("diagram.png", PNG_HEADER + b"mock", "image/png")},
            data={"diagram": '{"version":2,"nodes":[],"edges":[]}'},
        )
    assert response.status_code == 502
    assert "image-v1" in response.json()["detail"]


def test_route_handles_gemini_503_429_and_timeout():
    err_503 = gemini_errors.ServerError(
        503,
        {"error": {"code": 503, "message": "High demand", "status": "UNAVAILABLE"}},
    )
    with patch.object(GeminiVisionService, "interpret", new=AsyncMock(side_effect=err_503)):
        res_503 = make_client().post(
            "/ai/image/interpret",
            files={"image": ("diagram.png", PNG_HEADER + b"mock", "image/png")},
        )
    assert res_503.status_code == 503
    assert "problema temporal" in res_503.json()["detail"]

    err_429 = gemini_errors.ClientError(
        429,
        {"error": {"code": 429, "message": "Quota exceeded", "status": "RESOURCE_EXHAUSTED"}},
    )
    with patch.object(GeminiVisionService, "interpret", new=AsyncMock(side_effect=err_429)):
        res_429 = make_client().post(
            "/ai/image/interpret",
            files={"image": ("diagram.png", PNG_HEADER + b"mock", "image/png")},
        )
    assert res_429.status_code == 503
    assert "cuota" in res_429.json()["detail"]

    with patch.object(GeminiVisionService, "interpret", new=AsyncMock(side_effect=TimeoutError("Timed out"))):
        res_timeout = make_client().post(
            "/ai/image/interpret",
            files={"image": ("diagram.png", PNG_HEADER + b"mock", "image/png")},
        )
    assert res_timeout.status_code == 504
    assert "tiempo" in res_timeout.json()["detail"]

    with patch.object(GeminiVisionService, "interpret", new=AsyncMock(side_effect=httpx.ReadTimeout("Timed out"))):
        res_httpx_timeout = make_client().post(
            "/ai/image/interpret",
            files={"image": ("diagram.png", PNG_HEADER + b"mock", "image/png")},
        )
    assert res_httpx_timeout.status_code == 504


def test_route_rejects_destructive_recognition_without_external_provider():
    with patch.object(GeminiVisionService, "interpret", side_effect=ValueError("unsafe")):
        response = make_client().post("/ai/image/interpret", files={
            "image": ("diagram.png", PNG_HEADER + b"mock", "image/png")},
            data={"diagram": '{"version":2,"nodes":[],"edges":[]}'})
    assert response.status_code == 502


def test_vision_prompt_distinguishes_association_class_dashed_connector_and_multiplicities():
    assert "ASSOCIATION_CLASS" in VISION_PROMPT
    assert "NUNCA emitas ADD_RELATIONSHIP para la linea discontinua" in VISION_PROMPT
    assert "0..N" in VISION_PROMPT
    assert "1..N" in VISION_PROMPT
    assert "REGLAS CRITICAS PARA ATRIBUTOS, METODOS Y LITERALES" in VISION_PROMPT
    assert "Sin atributos" in VISION_PROMPT
    assert "PK" in VISION_PROMPT


