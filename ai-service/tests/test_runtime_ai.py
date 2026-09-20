import asyncio
import json
import unittest
from unittest.mock import patch
from fastapi.testclient import TestClient
from pydantic import ValidationError

from app.main import app
from app.schemas.runtime import RuntimeSchema
from app.services.runtime_ai_service import RuntimeAIService
from app.services.providers.base import ProviderResponseError


SCHEMA = RuntimeSchema.model_validate({
    "entities": [
        {"name": "Categoria", "fields": [
            {"name": "id", "type": "INTEGER", "nullable": False, "primaryKey": True, "generated": True},
            {"name": "nombre", "type": "STRING", "nullable": False, "primaryKey": False, "generated": False},
        ], "relations": []},
        {"name": "Corte", "fields": [
            {"name": "id", "type": "INTEGER", "nullable": False, "primaryKey": True, "generated": True},
            {"name": "nombre", "type": "STRING", "nullable": False, "primaryKey": False, "generated": False},
            {"name": "precio", "type": "DECIMAL", "nullable": False, "primaryKey": False, "generated": False},
        ], "relations": [{"name": "categoriaId", "target": "Categoria", "nullable": False, "multiple": False}]},
        {"name": "Personal", "fields": [
            {"name": "id", "type": "INTEGER", "nullable": False, "primaryKey": True, "generated": True},
            {"name": "nombre", "type": "STRING", "nullable": False, "primaryKey": False, "generated": False},
            {"name": "edad", "type": "INTEGER", "nullable": False, "primaryKey": False, "generated": False},
            {"name": "telefono", "type": "STRING", "nullable": False, "primaryKey": False, "generated": False},
            {"name": "saldo", "type": "DECIMAL", "nullable": True, "primaryKey": False, "generated": False},
        ], "relations": []},
    ],
})


class FakeAI:
    def __init__(self, response: str) -> None:
        self.response = response
        self.prompt = ""
        self.format = None

    async def generate(self, prompt: str, json_schema: dict | None = None) -> str:
        self.prompt = prompt
        self.format = json_schema
        return self.response


class FailingAI:
    async def generate(self, prompt: str, json_schema: dict | None = None) -> str:
        raise ProviderResponseError("simulated provider error")


def interpret(response: str, text: str = "Registra una categoria llamada Cabello"):
    fake = FakeAI(response)
    result = asyncio.run(RuntimeAIService(fake).interpret(text, SCHEMA))
    return result, fake


MANY_SCHEMA = RuntimeSchema.model_validate({"entities": [
    {"name": "Materia", "fields": [
        {"name": "id", "type": "INTEGER", "nullable": False, "primaryKey": True, "generated": True},
        {"name": "nombre", "type": "STRING", "nullable": False, "primaryKey": False, "generated": False},
    ], "relations": []},
    {"name": "Alumno", "fields": [
        {"name": "id", "type": "INTEGER", "nullable": False, "primaryKey": True, "generated": True},
        {"name": "nombre", "type": "STRING", "nullable": False, "primaryKey": False, "generated": False},
    ], "relations": [{"name": "materiasIds", "target": "Materia", "nullable": False, "multiple": True}]},
]})


class RuntimeAITest(unittest.TestCase):
    def test_create_interpretation_uses_application_schema_and_structured_format(self):
        response = json.dumps({"status": "INTERPRETED", "operation": "CREATE", "entity": "Categoria",
                               "values": {"nombre": "Cabello"}, "relations": {}, "message": None})
        result, fake = interpret(response)
        self.assertEqual(result.status, "INTERPRETED")
        self.assertEqual(result.values, {"nombre": "Cabello"})
        self.assertIn('"name": "categoriaId"', fake.prompt)
        self.assertIn('"nullable": false', fake.prompt)
        self.assertIn('"primaryKey": true', fake.prompt)
        self.assertEqual(fake.format["properties"]["status"]["enum"], ["INTERPRETED", "NEEDS_CLARIFICATION", "NOT_UNDERSTOOD"])


    def test_semantic_relation_stays_a_name_not_an_invented_id(self):
        response = json.dumps({"status": "INTERPRETED", "operation": "CREATE", "entity": "Corte",
                               "values": {"nombre": "Degradado", "precio": 25},
                               "relations": {"categoriaId": "Cabello"}, "message": None})
        result, _ = interpret(response, "Registra un corte Degradado de 25 en la categoria Cabello")
        self.assertEqual(result.relations, {"categoriaId": "Cabello"})
        self.assertNotIn("categoriaId", result.values)

    def test_many_to_many_uses_an_explicit_list_of_existing_names(self):
        response = json.dumps({"status": "INTERPRETED", "operation": "CREATE", "entity": "Alumno",
                               "values": {"nombre": "Gabriel"},
                               "relations": {"materiasIds": ["Matematicas", "Fisica"]}, "message": None})
        fake = FakeAI(response)
        result = asyncio.run(RuntimeAIService(fake).interpret(
            "Registra un alumno Gabriel y relacionalo con Matematicas y Fisica", MANY_SCHEMA))
        self.assertEqual(result.status, "INTERPRETED")
        self.assertEqual(result.relations["materiasIds"], ["Matematicas", "Fisica"])
        self.assertIn("multiple=true usa una lista", fake.prompt)

    def test_many_to_many_scalar_or_missing_required_list_requests_clarification(self):
        cases = [({"materiasIds": "Matematicas"}, ["materiasIds"]), ({}, ["materiasIds"])]
        for relations, missing in cases:
            with self.subTest(relations=relations):
                response = json.dumps({"status": "INTERPRETED", "operation": "CREATE", "entity": "Alumno",
                                       "values": {"nombre": "Gabriel"}, "relations": relations, "message": None})
                result = asyncio.run(RuntimeAIService(FakeAI(response)).interpret(
                    "Registra un alumno Gabriel con Matematicas", MANY_SCHEMA))
                self.assertEqual(result.status, "NEEDS_CLARIFICATION")
                self.assertEqual(result.missingFields, missing)

    def test_null_generated_id_and_spurious_null_relation_are_removed(self):
        response = json.dumps({"status": "INTERPRETED", "operation": "CREATE", "entity": "Categoria",
                               "values": {"id": None, "nombre": "Cabello"},
                               "relations": {"Categoria": None}, "message": "Registro exitoso"})
        result, _ = interpret(response)
        self.assertEqual(result.values, {"nombre": "Cabello"})
        self.assertEqual(result.relations, {})

    def test_generated_id_and_unknown_value_remain_safe(self):
        response = json.dumps({"status": "INTERPRETED", "operation": "CREATE", "entity": "Categoria",
                               "values": {"id": 99, "nombre": "Cabello", "sql": "DROP"},
                               "relations": {"inventada": "valor"}, "message": None})
        result, _ = interpret(response)
        self.assertNotIn("id", result.values)
        self.assertEqual(result.values["sql"], "DROP")
        self.assertNotIn("inventada", result.relations)


    def test_malformed_llm_json_never_becomes_an_operation(self):
        for raw in ["{malformed", "{}", "[]", '{"status":"INTERPRETED","status":"INTERPRETED"}']:
            with self.subTest(raw=raw):
                result, _ = interpret(raw)
                self.assertEqual(result.status, "NOT_UNDERSTOOD")


    def test_non_create_operation_is_rejected(self):
        for operation in ["UPDATE", "DELETE"]:
            with self.subTest(operation=operation):
                response = json.dumps({"status": "INTERPRETED", "operation": operation, "entity": "Categoria",
                                       "values": {"id": 1}, "relations": {}, "message": None})
                result, _ = interpret(response, f"{operation} la categoria Cabello")
                self.assertEqual(result.status, "NOT_UNDERSTOOD")
                self.assertIn(operation, result.message)

    def test_incomplete_create_reports_required_name_and_phone_deterministically(self):
        cases = [
            ({"edad": 23, "telefono": "78159999"}, ["nombre"],
             "Registra un personal de 23 anos con telefono 78159999"),
            ({"nombre": "Jose", "edad": 23}, ["telefono"],
             "Registra un personal llamado Jose de 23 anos"),
        ]
        for values, expected, text in cases:
            with self.subTest(expected=expected):
                response = json.dumps({"status": "INTERPRETED", "operation": "CREATE", "entity": "Personal",
                                       "values": values, "relations": {}, "message": None})
                result, _ = interpret(response, text)
                self.assertEqual(result.status, "NEEDS_CLARIFICATION")
                self.assertEqual(result.missingFields, expected)
                self.assertIn(expected[0], result.message)

    def test_missing_generated_primary_key_is_never_requested(self):
        response = json.dumps({"status": "INTERPRETED", "operation": "CREATE", "entity": "Personal",
                               "values": {"nombre": "Jose", "edad": 23, "telefono": "78159999"},
                               "relations": {}, "message": None})
        result, _ = interpret(response, "Registra a Jose de 23 anos con telefono 78159999")
        self.assertEqual(result.status, "INTERPRETED")
        self.assertNotIn("id", result.missingFields)

    def test_missing_required_relation_requests_clarification(self):
        response = json.dumps({"status": "INTERPRETED", "operation": "CREATE", "entity": "Corte",
                               "values": {"nombre": "Degradado", "precio": 25},
                               "relations": {}, "message": None})
        result, _ = interpret(response, "Registra un corte Degradado de 25")
        self.assertEqual(result.status, "NEEDS_CLARIFICATION")
        self.assertEqual(result.missingFields, ["categoriaId"])
        self.assertIn("categoriaId", result.message)

    def test_unknown_entity_has_a_specific_rejection(self):
        response = json.dumps({"status": "INTERPRETED", "operation": "CREATE", "entity": "Factura",
                               "values": {"numero": 1}, "relations": {}, "message": None})
        result, _ = interpret(response, "Registra una factura numero 1")
        self.assertEqual(result.status, "NOT_UNDERSTOOD")
        self.assertIn("Factura", result.message)

    def test_invalid_age_is_left_for_spring_type_validation_and_not_invented(self):
        response = json.dumps({"status": "INTERPRETED", "operation": "CREATE", "entity": "Personal",
                               "values": {"nombre": "Jose", "edad": "veintitres", "telefono": "78159999"},
                               "relations": {}, "message": None})
        result, _ = interpret(response, "Registra a Jose de veintitres anos con telefono 78159999")
        self.assertEqual(result.status, "INTERPRETED")
        self.assertEqual(result.values["edad"], "veintitres")

    def test_grouped_phone_is_joined_without_changing_digit_order(self):
        response = json.dumps({"status": "INTERPRETED", "operation": "CREATE", "entity": "Personal",
                               "values": {"nombre": "Jose", "edad": 23, "telefono": "781, 543, 23"},
                               "relations": {}, "message": None})
        result, _ = interpret(response, "Registra a Jose de 23 anos con telefono 781, 543, 23")
        self.assertEqual(result.status, "INTERPRETED")
        self.assertEqual(result.values["telefono"], "78154323")

    def test_grouped_phone_preserves_leading_zeroes(self):
        response = json.dumps({"status": "INTERPRETED", "operation": "CREATE", "entity": "Personal",
                               "values": {"nombre": "Ana", "edad": 30, "telefono": "078, 015, 03"},
                               "relations": {}, "message": None})
        result, _ = interpret(response, "Registra a Ana de 30 anos con telefono 078, 015, 03")
        self.assertEqual(result.values["telefono"], "07801503")

    def test_phone_normalization_does_not_touch_decimal_or_other_fields(self):
        response = json.dumps({"status": "INTERPRETED", "operation": "CREATE", "entity": "Personal",
                               "values": {"nombre": "Ana", "edad": 30, "telefono": "781, 543, 23",
                                          "saldo": "1,25"},
                               "relations": {}, "message": None})
        result, _ = interpret(response, "Registra a Ana, edad 30, telefono 781, 543, 23 y saldo 1,25")
        self.assertEqual(result.values["telefono"], "78154323")
        self.assertEqual(result.values["saldo"], "1,25")

    def test_ambiguous_or_invented_phone_requests_human_review(self):
        cases = [
            ("781, extension 543", "telefono 781, extension 543"),
            ("99999999", "Registra a Jose de 23 anos"),
        ]
        for phone, text in cases:
            with self.subTest(phone=phone):
                response = json.dumps({"status": "INTERPRETED", "operation": "CREATE", "entity": "Personal",
                                       "values": {"nombre": "Jose", "edad": 23, "telefono": phone},
                                       "relations": {}, "message": None})
                result, _ = interpret(response, text)
                self.assertEqual(result.status, "NEEDS_CLARIFICATION")
                self.assertEqual(result.missingFields, ["telefono"])

    def test_optional_integer_phone_can_be_omitted_but_is_not_coerced_to_string(self):
        schema = RuntimeSchema.model_validate({"entities": [{"name": "Personal", "fields": [
            {"name": "id", "type": "INTEGER", "nullable": False, "primaryKey": True, "generated": True},
            {"name": "telefono", "type": "INTEGER", "nullable": True, "primaryKey": False, "generated": False},
        ], "relations": []}]})
        omitted = json.dumps({"status": "INTERPRETED", "operation": "CREATE", "entity": "Personal",
                              "values": {}, "relations": {}, "message": None})
        result = asyncio.run(RuntimeAIService(FakeAI(omitted)).interpret("Registra un personal", schema))
        self.assertEqual(result.status, "INTERPRETED")

        provided = json.dumps({"status": "INTERPRETED", "operation": "CREATE", "entity": "Personal",
                               "values": {"telefono": 78154323}, "relations": {}, "message": None})
        result = asyncio.run(RuntimeAIService(FakeAI(provided)).interpret(
            "Registra un personal con telefono 78154323", schema))
        self.assertEqual(result.status, "NEEDS_CLARIFICATION")
        self.assertIn("STRING", result.message)


    def test_runtime_schema_is_strict(self):
        invalid = SCHEMA.model_dump()
        invalid["entities"][0]["fields"][0]["type"] = "SQL"
        with self.assertRaises(ValidationError):
            RuntimeSchema.model_validate(invalid)

    def test_runtime_http_route_uses_separate_interpret_endpoint(self):
        fake = FakeAI(json.dumps({"status": "INTERPRETED", "operation": "CREATE", "entity": "Categoria",
                                  "values": {"nombre": "Cabello"}, "relations": {}, "message": None}))
        with patch("app.api.routes.runtime.AIService.from_settings", return_value=fake):
            response = TestClient(app).post("/api/runtime/interpret", json={
                "text": "Registra una categoria llamada Cabello", "schema": SCHEMA.model_dump_json(),
            })
        self.assertEqual(response.status_code, 200)
        self.assertEqual(response.json()["operation"], "CREATE")

    def test_runtime_http_route_rejects_malformed_schema_before_provider(self):
        response = TestClient(app).post("/api/runtime/interpret", json={"text": "Registra algo", "schema": "{broken"})
        self.assertEqual(response.status_code, 422)

    def test_runtime_http_route_returns_clarification_and_not_understood_as_200(self):
        for status in ["NEEDS_CLARIFICATION", "NOT_UNDERSTOOD"]:
            with self.subTest(status=status):
                fake = FakeAI(json.dumps({"status": status, "operation": None, "entity": None,
                                          "values": None, "relations": None, "message": "Necesito mas datos"}))
                with patch("app.api.routes.runtime.AIService.from_settings", return_value=fake):
                    response = TestClient(app).post("/api/runtime/interpret", json={
                        "text": "Registra algo", "schema": SCHEMA.model_dump_json(),
                    })
                self.assertEqual(response.status_code, 200)
                self.assertEqual(response.json()["status"], status)

    def test_runtime_http_route_maps_provider_invalid_response_to_502(self):
        with patch("app.api.routes.runtime.AIService.from_settings", return_value=FailingAI()):
            response = TestClient(app).post("/api/runtime/interpret", json={
                "text": "Registra algo", "schema": SCHEMA.model_dump_json(),
            })
        self.assertEqual(response.status_code, 502)
