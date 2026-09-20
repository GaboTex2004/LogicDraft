package com.sw1.backend.generator.spring.render;

import com.sw1.backend.generator.schema.ApplicationEntity;
import com.sw1.backend.generator.schema.ApplicationField;
import com.sw1.backend.generator.schema.ApplicationSchema;
import com.sw1.backend.generator.schema.CanonicalType;
import com.sw1.backend.generator.spring.OwnedRelation;
import com.sw1.backend.generator.spring.SpringNames;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Generates a schema-bound command boundary; the LLM never receives persistence authority. */
public final class RuntimeAiRenderer {
    private RuntimeAiRenderer() {}

    public static String render(String basePackage, ApplicationSchema schema,
                                Map<String, List<OwnedRelation>> relations) {
        StringBuilder out = new StringBuilder();
        out.append("package ").append(basePackage).append(".ai;\n\n")
                .append("import ").append(basePackage).append(".dto.*;\n")
                .append("import ").append(basePackage).append(".service.*;\n")
                .append("import ").append(basePackage).append(".repository.*;\n")
                .append("import java.math.BigDecimal;\nimport java.net.*;\nimport java.time.*;\nimport java.util.*;\n")
                .append("import org.springframework.beans.factory.annotation.Value;\n")
                .append("import org.springframework.core.ParameterizedTypeReference;\n")
                .append("import org.springframework.http.*;\n")
                .append("import org.springframework.http.client.SimpleClientHttpRequestFactory;\n")
                .append("import org.slf4j.*;\n")
                .append("import org.springframework.web.bind.annotation.*;\n")
                .append("import org.springframework.web.client.*;\n")
                .append("import org.springframework.web.server.ResponseStatusException;\n\n")
                .append("@RestController\n@RequestMapping(\"/api/ai/commands\")\n")
                .append("public class RuntimeAiCommandController {\n")
                .append("  private static final Logger log = LoggerFactory.getLogger(RuntimeAiCommandController.class);\n")
                .append("  private static final String SCHEMA = \"").append(javaQuote(schemaJson(schema, relations))).append("\";\n")
                .append("  private final RestClient client;\n")
                .append("  private final String aiServiceUrl;\n");
        Set<String> dependencies = new LinkedHashSet<>();
        for (ApplicationEntity entity : schema.entities()) {
            dependencies.add(entity.technicalName() + "Service");
            for (OwnedRelation relation : relations.get(entity.id())) dependencies.add(relation.target().technicalName() + "Repository");
        }
        for (String dependency : dependencies) out.append("  private final ").append(dependency).append(" ")
                .append(SpringNames.lowerFirst(dependency)).append(";\n");
        out.append("\n  @org.springframework.beans.factory.annotation.Autowired\n")
                .append("  public RuntimeAiCommandController(@Value(\"${ai.service.url:http://localhost:8000}\") String aiServiceUrl");
        for (String dependency : dependencies) out.append(", ").append(dependency).append(" ")
                .append(SpringNames.lowerFirst(dependency));
        out.append(") {\n    this(aiServiceUrl, runtimeClient()");
        for (String dependency : dependencies) out.append(", ").append(SpringNames.lowerFirst(dependency));
        out.append(");\n  }\n\n  RuntimeAiCommandController(String aiServiceUrl, RestClient client");
        for (String dependency : dependencies) out.append(", ").append(dependency).append(" ")
                .append(SpringNames.lowerFirst(dependency));
        out.append(") {\n    this.aiServiceUrl = aiServiceUrl.replaceAll(\"/$\", \"\");\n")
                .append("    this.client = client;\n");
        for (String dependency : dependencies) out.append("    this.").append(SpringNames.lowerFirst(dependency))
                .append(" = ").append(SpringNames.lowerFirst(dependency)).append(";\n");
        out.append("  }\n\n")
                .append("  public record CommandRequest(String text) {}\n\n")
                .append("  @PostMapping\n  public Map<String, Object> execute(@RequestBody CommandRequest command) {\n")
                .append("    if (command == null || command.text() == null || command.text().isBlank() || command.text().length() > 2000)\n")
                .append("      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, \"Escribe una instruccion valida.\");\n")
                .append("    String endpoint = aiServiceUrl + \"/api/runtime/interpret\";\n")
                .append("    log.info(\"Runtime AI command received: {}\", safeBody(command.text()));\n")
                .append("    log.info(\"Calling AI service: {}\", safeUrl(endpoint));\n")
                .append("    Map<String, Object> result;\n    try {\n")
                .append("      ResponseEntity<Map<String, Object>> response = client.post().uri(endpoint)\n")
                .append("          .body(Map.of(\"text\", command.text(), \"schema\", SCHEMA)).retrieve()\n")
                .append("          .toEntity(new ParameterizedTypeReference<Map<String, Object>>() {});\n")
                .append("      log.info(\"AI service responded: {}\", response.getStatusCode().value());\n")
                .append("      result = response.getBody();\n")
                .append("    } catch (RestClientResponseException error) {\n")
                .append("      int status = error.getStatusCode().value();\n")
                .append("      log.warn(\"AI service responded: {} body={}\", status, safeBody(error.getResponseBodyAsString()));\n")
                .append("      HttpStatus mapped = status == 503 ? HttpStatus.SERVICE_UNAVAILABLE\n")
                .append("          : status == 504 ? HttpStatus.GATEWAY_TIMEOUT : HttpStatus.BAD_GATEWAY;\n")
                .append("      throw new ResponseStatusException(mapped, upstreamMessage(error.getResponseBodyAsString(), mapped));\n")
                .append("    } catch (ResourceAccessException error) {\n")
                .append("      if (hasCause(error, java.net.SocketTimeoutException.class)\n")
                .append("          || hasCause(error, java.net.http.HttpTimeoutException.class)) {\n")
                .append("        log.warn(\"AI service timeout calling {}: {}\", safeUrl(endpoint), error.getClass().getSimpleName());\n")
                .append("        throw new ResponseStatusException(HttpStatus.GATEWAY_TIMEOUT, \"El servicio de IA excedio el tiempo de espera.\");\n")
                .append("      }\n")
                .append("      log.warn(\"AI service connection failed calling {}: {}\", safeUrl(endpoint), error.getClass().getSimpleName());\n")
                .append("      throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, \"El servicio de IA no esta disponible.\");\n")
                .append("    } catch (RestClientException error) {\n")
                .append("      log.warn(\"AI service returned invalid JSON/contract: {}\", error.getClass().getSimpleName());\n")
                .append("      throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, \"El servicio de IA devolvio una respuesta invalida.\");\n")
                .append("    }\n")
                .append("    return apply(result, command.text());\n  }\n\n")
                .append("  Map<String, Object> apply(Map<String, Object> result, String text) {\n")
                .append("    if (result == null) throw invalidUpstream(\"empty body\");\n")
                .append("    Object status = result.get(\"status\");\n")
                .append("    if (!(status instanceof String statusText) || !Set.of(\"INTERPRETED\", \"NEEDS_CLARIFICATION\", \"NOT_UNDERSTOOD\").contains(statusText))\n")
                .append("      throw invalidUpstream(\"missing or unknown status\");\n")
                .append("    if (\"NEEDS_CLARIFICATION\".equals(status) || \"NOT_UNDERSTOOD\".equals(status))\n")
                .append("      return reply(statusText, safeMessage(result.get(\"message\")), checkedMissingFields(result.get(\"missingFields\")));\n")
                .append("    if (!\"INTERPRETED\".equals(status) || !\"CREATE\".equals(result.get(\"operation\")))\n")
                .append("      return reply(\"NOT_UNDERSTOOD\", \"Solo se permite registrar datos.\");\n")
                .append("    if (!(result.get(\"entity\") instanceof String entity) || !(result.get(\"values\") instanceof Map<?, ?> rawValues)\n")
                .append("        || !(result.get(\"relations\") instanceof Map<?, ?> rawRelations))\n")
                .append("      throw invalidUpstream(\"INTERPRETED response misses entity, values or relations\");\n")
                .append("    try {\n")
                .append("      Map<String, Object> values = checkedMap(rawValues);\n")
                .append("      Map<String, Object> semantic = checkedMap(rawRelations);\n")
                .append("      return switch (entity) {\n");
        for (ApplicationEntity entity : schema.entities()) {
            out.append("        case \"").append(javaQuote(entity.technicalName())).append("\" -> create")
                    .append(entity.technicalName()).append("(values, semantic, text);\n");
        }
        out.append("        default -> reply(\"NOT_UNDERSTOOD\", \"La entidad no existe.\");\n")
                .append("      };\n")
                .append("    } catch (InvalidCommand error) {\n")
                .append("      log.warn(\"Runtime AI validation rejected command: {}\", error.getMessage());\n")
                .append("      return reply(error.clarification ? \"NEEDS_CLARIFICATION\" : \"NOT_UNDERSTOOD\", error.getMessage());\n")
                .append("    }\n  }\n\n");
        for (ApplicationEntity entity : schema.entities()) entityMethod(out, entity, relations.get(entity.id()));
        out.append("  private static Map<String, Object> checkedMap(Map<?, ?> raw) {\n")
                .append("    Map<String, Object> result = new LinkedHashMap<>();\n")
                .append("    for (var entry : raw.entrySet()) {\n")
                .append("      if (!(entry.getKey() instanceof String key)) throw new InvalidCommand(\"Campo desconocido.\", false);\n")
                .append("      result.put(key, entry.getValue());\n    }\n    return result;\n  }\n")
                .append("  private static void known(Map<String, Object> values, String... names) {\n")
                .append("    Set<String> allowed = Set.of(names);\n")
                .append("    for (String name : values.keySet()) if (!allowed.contains(name))\n")
                .append("      throw new InvalidCommand(\"Atributo o relacion inexistente: \" + name, false);\n  }\n")
                .append("  private static Object required(Map<String, Object> values, String name, boolean optional) {\n")
                .append("    Object value = values.get(name);\n")
                .append("    if (value == null && !optional) throw new InvalidCommand(\"Falta el campo obligatorio: \" + name, true);\n")
                .append("    return value;\n  }\n")
                .append("  private static String stringValue(Object value, String name) {\n")
                .append("    if (value == null) return null;\n")
                .append("    if (!(value instanceof String text) || text.isBlank()) throw new InvalidCommand(\"Valor invalido para \" + name, false);\n")
                .append("    return text.trim();\n  }\n")
                .append("  private static String phoneValue(Object value, String name, String sourceText) {\n")
                .append("    if (value == null) return null;\n")
                .append("    if (!(value instanceof String raw) || raw.isBlank())\n")
                .append("      throw new InvalidCommand(\"Revisa \" + name + \": un telefono debe conservarse como texto.\", true);\n")
                .append("    raw = raw.trim();\n")
                .append("    boolean grouped = raw.indexOf(',') >= 0;\n")
                .append("    if (grouped && !raw.matches(\"\\\\d+(?:\\\\s*,\\\\s*\\\\d+)+\"))\n")
                .append("      throw new InvalidCommand(\"Revisa \" + name + \": la secuencia telefonica es ambigua.\", true);\n")
                .append("    String normalized = grouped ? raw.replaceAll(\"[\\\\s,]\", \"\") : raw;\n")
                .append("    var evidence = java.util.regex.Pattern.compile(\"(?iu)\\\\b(?:tel[eé]fono|celular|m[oó]vil|phone|n[uú]mero(?:\\\\s+de\\\\s+tel[eé]fono)?)\\\\b[^\\\\d]{0,30}(\\\\d+(?:\\\\s*,\\\\s*\\\\d+)*)\")\n")
                .append("        .matcher(sourceText);\n")
                .append("    int occurrences = 0;\n")
                .append("    while (evidence.find()) if (evidence.group(1).replaceAll(\"[\\\\s,]\", \"\").equals(normalized)) occurrences++;\n")
                .append("    if (occurrences != 1) throw new InvalidCommand(\"Revisa \" + name\n")
                .append("        + \": el valor no aparece como un unico telefono en la instruccion y no sera inventado.\", true);\n")
                .append("    return normalized;\n  }\n")
                .append("  private static Integer integerValue(Object value, String name) {\n")
                .append("    if (value == null) return null;\n")
                .append("    if (!(value instanceof Number number) || value instanceof Float || value instanceof Double)\n")
                .append("      throw new InvalidCommand(\"Numero entero invalido para \" + name, false);\n")
                .append("    try { return new BigDecimal(number.toString()).intValueExact(); }\n")
                .append("    catch (ArithmeticException | NumberFormatException error) { throw new InvalidCommand(\"Numero entero invalido para \" + name, false); }\n  }\n")
                .append("  private static Long longValue(Object value, String name) {\n")
                .append("    if (value == null) return null;\n")
                .append("    if (!(value instanceof Number number) || value instanceof Float || value instanceof Double)\n")
                .append("      throw new InvalidCommand(\"Numero entero invalido para \" + name, false);\n")
                .append("    try { return new BigDecimal(number.toString()).longValueExact(); }\n")
                .append("    catch (ArithmeticException | NumberFormatException error) { throw new InvalidCommand(\"Numero entero invalido para \" + name, false); }\n  }\n")
                .append("  private static BigDecimal decimalValue(Object value, String name) {\n")
                .append("    if (value == null) return null;\n")
                .append("    if (!(value instanceof Number number)) throw new InvalidCommand(\"Numero decimal invalido para \" + name, false);\n")
                .append("    try { return new BigDecimal(number.toString()); }\n")
                .append("    catch (NumberFormatException error) { throw new InvalidCommand(\"Numero decimal invalido para \" + name, false); }\n  }\n")
                .append("  private static Boolean booleanValue(Object value, String name) {\n")
                .append("    if (value == null) return null;\n")
                .append("    if (!(value instanceof Boolean flag)) throw new InvalidCommand(\"Booleano invalido para \" + name, false);\n")
                .append("    return flag;\n  }\n")
                .append("  private static LocalDate dateValue(Object value, String name) {\n")
                .append("    if (value == null) return null;\n")
                .append("    try { return LocalDate.parse(stringValue(value, name)); }\n")
                .append("    catch (java.time.format.DateTimeParseException error) { throw new InvalidCommand(\"Fecha invalida para \" + name, false); }\n  }\n")
                .append("  private static LocalDateTime dateTimeValue(Object value, String name) {\n")
                .append("    if (value == null) return null;\n")
                .append("    try { return LocalDateTime.parse(stringValue(value, name)); }\n")
                .append("    catch (java.time.format.DateTimeParseException error) { throw new InvalidCommand(\"Fecha/hora invalida para \" + name, false); }\n  }\n")
                .append("  private static Map<String, Object> reply(String status, String message) {\n")
                .append("    return Map.of(\"status\", status, \"message\", message);\n  }\n")
                .append("  private static Map<String, Object> reply(String status, String message, List<String> missingFields) {\n")
                .append("    Map<String, Object> response = new LinkedHashMap<>();\n")
                .append("    response.put(\"status\", status);\n    response.put(\"message\", message);\n")
                .append("    if (!missingFields.isEmpty()) response.put(\"missingFields\", List.copyOf(missingFields));\n")
                .append("    return response;\n  }\n")
                .append("  private static List<String> checkedMissingFields(Object value) {\n")
                .append("    if (!(value instanceof List<?> raw)) return List.of();\n")
                .append("    List<String> fields = new ArrayList<>();\n")
                .append("    for (Object item : raw) if (item instanceof String name && !name.isBlank() && name.length() <= 100) fields.add(name);\n")
                .append("    return List.copyOf(fields);\n  }\n")
                .append("  private static Map<String, Object> missingReply(String entity, List<String> missingFields) {\n")
                .append("    return reply(\"NEEDS_CLARIFICATION\", \"Falta informacion obligatoria para registrar \" + entity + \": \"\n")
                .append("        + String.join(\", \", missingFields) + \".\", missingFields);\n  }\n")
                .append("  private static String safeMessage(Object value) {\n")
                .append("    return value instanceof String text && !text.isBlank() && text.length() <= 300\n")
                .append("        ? text : \"No pude interpretar el comando.\";\n  }\n")
                .append("  private static ResponseStatusException invalidUpstream(String reason) {\n")
                .append("    log.warn(\"AI service contract validation failed: {}\", reason);\n")
                .append("    return new ResponseStatusException(HttpStatus.BAD_GATEWAY, \"El servicio de IA devolvio una respuesta invalida.\");\n  }\n")
                .append("  private static RestClient runtimeClient() {\n")
                .append("    var requestFactory = new SimpleClientHttpRequestFactory();\n")
                .append("    requestFactory.setConnectTimeout(Duration.ofSeconds(5));\n")
                .append("    requestFactory.setReadTimeout(Duration.ofSeconds(65));\n")
                .append("    return RestClient.builder().requestFactory(requestFactory).build();\n  }\n")
                .append("  private static boolean hasCause(Throwable error, Class<? extends Throwable> type) {\n")
                .append("    for (Throwable current = error; current != null; current = current.getCause())\n")
                .append("      if (type.isInstance(current)) return true;\n")
                .append("    return false;\n  }\n")
                .append("  private static String safeUrl(String value) {\n")
                .append("    try {\n")
                .append("      URI uri = URI.create(value);\n")
                .append("      return new URI(uri.getScheme(), null, uri.getHost(), uri.getPort(), uri.getPath(), null, null).toString();\n")
                .append("    } catch (IllegalArgumentException | URISyntaxException error) { return \"<invalid-ai-service-url>\"; }\n  }\n")
                .append("  private static String logValue(String value) {\n")
                .append("    if (value == null) return \"<null>\";\n")
                .append("    String clean = value.replaceAll(\"[\\\\r\\\\n\\\\t]+\", \" \");\n")
                .append("    return clean.length() <= 500 ? clean : clean.substring(0, 500) + \"...\";\n  }\n")
                .append("  private static String safeBody(String value) {\n")
                .append("    if (value == null || value.isBlank()) return \"<empty>\";\n")
                .append("    String clean = value.replaceAll(\"(?i)\\\\b(authorization|token|password|secret)\\\\b\\\\s*(?:[=:]\\\\s*)?[^,}\\\\s]+\", \"$1=<redacted>\");\n")
                .append("    clean = clean.replaceAll(\"[\\\\r\\\\n\\\\t]+\", \" \" );\n")
                .append("    return clean.length() <= 1000 ? clean : clean.substring(0, 1000) + \"...\";\n  }\n")
                .append("  private static String upstreamMessage(String body, HttpStatus status) {\n")
                .append("    if (body != null) {\n")
                .append("      for (String key : List.of(\"message\", \"detail\")) {\n")
                .append("        int keyAt = body.indexOf(\"\\\"\" + key + \"\\\"\");\n")
                .append("        int colon = keyAt < 0 ? -1 : body.indexOf(':', keyAt);\n")
                .append("        int start = colon < 0 ? -1 : body.indexOf(34, colon);\n")
                .append("        int end = start < 0 ? -1 : body.indexOf(34, start + 1);\n")
                .append("        if (start >= 0 && end > start + 1) return logValue(body.substring(start + 1, end));\n")
                .append("      }\n")
                .append("    }\n")
                .append("    return status == HttpStatus.SERVICE_UNAVAILABLE ? \"El servicio de IA no esta disponible.\"\n")
                .append("        : status == HttpStatus.GATEWAY_TIMEOUT ? \"El servicio de IA excedio el tiempo de espera.\"\n")
                .append("        : \"El servicio de IA devolvio una respuesta invalida.\";\n  }\n")
                .append("  private static boolean explicitId(String text, String name, Object value) {\n")
                .append("    return value != null && java.util.regex.Pattern.compile(\"(?iu)\\\\b\"\n")
                .append("        + java.util.regex.Pattern.quote(name) + \"\\\\s*[:=#]\\\\s*\"\n")
                .append("        + java.util.regex.Pattern.quote(String.valueOf(value)) + \"\\\\b\")\n")
                .append("        .matcher(text).find();\n  }\n")
                .append("  private static class InvalidCommand extends RuntimeException {\n")
                .append("    final boolean clarification;\n")
                .append("    InvalidCommand(String message, boolean clarification) { super(message); this.clarification = clarification; }\n")
                .append("  }\n}\n");
        return out.toString();
    }

    private static void entityMethod(StringBuilder out, ApplicationEntity entity, List<OwnedRelation> relations) {
        String type = entity.technicalName();
        out.append("  private Map<String, Object> create").append(type)
                .append("(Map<String, Object> values, Map<String, Object> semantic, String text) {\n");
        List<ApplicationField> fields = entity.fields().stream().filter(field -> !field.generated()).toList();
        String[] names = new String[fields.size() + relations.size()];
        int index = 0;
        for (ApplicationField field : fields) names[index++] = field.technicalName();
        for (OwnedRelation relation : relations) names[index++] = relation.dtoPropertyName();
        out.append("    known(values");
        for (String name : names) out.append(", \"").append(javaQuote(name)).append("\"");
        out.append(");\n    known(semantic");
        for (OwnedRelation relation : relations) out.append(", \"").append(javaQuote(relation.dtoPropertyName())).append("\"");
        out.append(");\n");
        out.append("    List<String> missingFields = new ArrayList<>();\n");
        for (ApplicationField field : fields) {
            if (!field.nullable()) out.append("    if (values.get(\"").append(javaQuote(field.technicalName()))
                    .append("\") == null || values.get(\"").append(javaQuote(field.technicalName()))
                    .append("\") instanceof String fieldText && fieldText.isBlank()) missingFields.add(\"")
                    .append(javaQuote(field.technicalName())).append("\");\n");
        }
        for (OwnedRelation relation : relations) {
            if (!relation.optional()) out.append("    if (values.get(\"").append(javaQuote(relation.dtoPropertyName()))
                    .append("\") == null && semantic.get(\"").append(javaQuote(relation.dtoPropertyName()))
                    .append("\") == null) missingFields.add(\"").append(javaQuote(relation.dtoPropertyName()))
                    .append("\");\n");
        }
        out.append("    if (!missingFields.isEmpty()) return missingReply(\"")
                .append(javaQuote(entity.name())).append("\", missingFields);\n");
        for (ApplicationField field : fields) {
            if (isPhoneName(field) && field.type() != CanonicalType.STRING) {
                out.append("    if (values.get(\"").append(javaQuote(field.technicalName())).append("\") != null)\n")
                        .append("      throw new InvalidCommand(\"Revisa ").append(javaQuote(field.technicalName()))
                        .append(": debe estar definido como STRING para conservar el telefono.\", true);\n");
            }
        }
        for (ApplicationField field : fields) out.append("    var ").append(field.technicalName())
                .append("Value = ").append(scalarExpression(field)).append(";\n");
        for (OwnedRelation relation : relations) {
            if (relation.kind() == OwnedRelation.Kind.MANY_TO_MANY) manyRelationValue(out, relation);
            else relationValue(out, relation);
        }
        out.append("    ").append(type).append("Response saved = ")
                .append(SpringNames.lowerFirst(type)).append("Service.crear(new ").append(type).append("CreateRequest(\n");
        List<String> args = new java.util.ArrayList<>();
        for (ApplicationField field : fields) args.add("        " + field.technicalName() + "Value");
        for (OwnedRelation relation : relations) {
            if (relation.kind() == OwnedRelation.Kind.MANY_TO_MANY) {
                args.add("        " + relation.dtoPropertyName() + "Value");
            } else args.add("        " + relation.dtoPropertyName() + "Value");
        }
        out.append(String.join(",\n", args)).append("));\n")
                .append("    Map<String, Object> result = new LinkedHashMap<>();\n")
                .append("    result.put(\"status\", \"EXECUTED\");\n")
                .append("    result.put(\"operation\", \"CREATE\");\n")
                .append("    result.put(\"entity\", \"").append(javaQuote(type)).append("\");\n")
                .append("    result.put(\"message\", \"Registro guardado correctamente\");\n")
                .append("    result.put(\"recordId\", saved.")
                .append(entity.fields().stream().filter(ApplicationField::primaryKey).findFirst().orElseThrow().technicalName())
                .append("());\n    return result;\n  }\n");
    }

    private static void relationValue(StringBuilder out, OwnedRelation relation) {
        String name = relation.dtoPropertyName();
        ApplicationEntity target = relation.target();
        ApplicationField pk = target.fields().stream().filter(ApplicationField::primaryKey).findFirst().orElseThrow();
        String idType = switch (pk.type()) {
            case INTEGER -> "Integer"; case LONG -> "Long"; case STRING -> "String";
            case DECIMAL -> "BigDecimal"; case BOOLEAN -> "Boolean";
            case DATE -> "LocalDate"; case DATETIME -> "LocalDateTime";
        };
        out.append("    if (values.containsKey(\"").append(name).append("\") && semantic.containsKey(\"")
                .append(name).append("\")) throw new InvalidCommand(\"Relacion ambigua: ").append(name).append("\", true);\n")
                .append("    if (values.get(\"").append(name).append("\") != null && !explicitId(text, \"")
                .append(name).append("\", values.get(\"").append(name)
                .append("\"))) throw new InvalidCommand(\"Indica explicitamente ").append(name)
                .append("=<ID> o usa el nombre de la relacion.\", true);\n")
                .append("    ").append(idType).append(" ").append(name).append("Value = ")
                .append(conversion(pk.type(), "values.get(\"" + name + "\")", name)).append(";\n");
        ApplicationField visible = target.fields().stream().filter(field -> field.type() == CanonicalType.STRING
                && field.technicalName().equalsIgnoreCase("nombre")).findFirst().orElse(null);
        out.append("    if (semantic.containsKey(\"").append(name).append("\")) {\n");
        if (visible == null) {
            out.append("      throw new InvalidCommand(\"La relacion ").append(name)
                    .append(" no tiene un nombre consultable; indica su ID.\", true);\n");
        } else {
            String repo = SpringNames.lowerFirst(target.technicalName()) + "Repository";
            out.append("      String label = stringValue(semantic.get(\"").append(name).append("\"), \"")
                    .append(name).append("\");\n")
                    .append("      if (label == null) throw new InvalidCommand(\"Indica la relacion ")
                    .append(name).append(".\", true);\n")
                    .append("      if (!text.toLowerCase(Locale.ROOT).contains(label.toLowerCase(Locale.ROOT)))\n")
                    .append("        throw new InvalidCommand(\"El nombre de la relacion no aparece en la instruccion.\", true);\n")
                    .append("      var matches = ").append(repo).append(".findTop2ByNombreIgnoreCase(label);\n")
                    .append("      if (matches.isEmpty()) throw new InvalidCommand(\"No existe ")
                    .append(target.name()).append(" con nombre \" + label, true);\n")
                    .append("      if (matches.size() > 1) throw new InvalidCommand(\"Hay varias coincidencias para \" + label, true);\n")
                    .append("      ").append(name).append("Value = matches.get(0).get")
                    .append(upper(pk.technicalName())).append("();\n");
        }
        out.append("    }\n")
                .append("    if (").append(name).append("Value == null && !")
                .append(relation.optional()).append(") throw new InvalidCommand(\"Falta la relacion ")
                .append(name).append(".\", true);\n");
    }

    private static void manyRelationValue(StringBuilder out, OwnedRelation relation) {
        String name = relation.dtoPropertyName();
        ApplicationEntity target = relation.target();
        ApplicationField pk = target.fields().stream().filter(ApplicationField::primaryKey).findFirst().orElseThrow();
        String idType = switch (pk.type()) {
            case INTEGER -> "Integer"; case LONG -> "Long"; case STRING -> "String";
            case DECIMAL -> "BigDecimal"; case BOOLEAN -> "Boolean";
            case DATE -> "LocalDate"; case DATETIME -> "LocalDateTime";
        };
        ApplicationField visible = target.fields().stream().filter(field -> field.type() == CanonicalType.STRING
                && field.technicalName().equalsIgnoreCase("nombre")).findFirst().orElse(null);
        out.append("    if (values.get(\"").append(name).append("\") != null)\n")
                .append("      throw new InvalidCommand(\"Para ").append(name)
                .append(" usa nombres de registros existentes, no IDs inferidos.\", true);\n")
                .append("    Set<").append(idType).append("> ").append(name).append("Value = new LinkedHashSet<>();\n")
                .append("    if (semantic.get(\"").append(name).append("\") != null) {\n")
                .append("      if (!(semantic.get(\"").append(name).append("\") instanceof List<?> labels) || labels.isEmpty())\n")
                .append("        throw new InvalidCommand(\"Indica una lista de nombres para ").append(name).append(".\", true);\n");
        if (visible == null) {
            out.append("      throw new InvalidCommand(\"La relacion ").append(name)
                    .append(" no tiene un nombre consultable; asignala desde el formulario.\", true);\n");
        } else {
            String repo = SpringNames.lowerFirst(target.technicalName()) + "Repository";
            out.append("      for (Object rawLabel : labels) {\n")
                    .append("        String label = stringValue(rawLabel, \"").append(name).append("\");\n")
                    .append("        if (!text.toLowerCase(Locale.ROOT).contains(label.toLowerCase(Locale.ROOT)))\n")
                    .append("          throw new InvalidCommand(\"El nombre relacionado no aparece en la instruccion: \" + label, true);\n")
                    .append("        var matches = ").append(repo).append(".findTop2ByNombreIgnoreCase(label);\n")
                    .append("        if (matches.isEmpty()) throw new InvalidCommand(\"No existe ")
                    .append(target.name()).append(" con nombre \" + label, true);\n")
                    .append("        if (matches.size() > 1) throw new InvalidCommand(\"Hay varias coincidencias para \" + label, true);\n")
                    .append("        ").append(name).append("Value.add(matches.get(0).get")
                    .append(upper(pk.technicalName())).append("());\n")
                    .append("      }\n");
        }
        out.append("    }\n")
                .append("    if (").append(name).append("Value.isEmpty() && !").append(relation.optional())
                .append(") throw new InvalidCommand(\"Falta la relacion ").append(name).append(".\", true);\n");
    }

    private static String scalarExpression(ApplicationField field) {
        String name = field.technicalName();
        if (field.type() == CanonicalType.STRING && isPhoneName(field)) {
            return "phoneValue(required(values, \"" + name + "\", " + field.nullable() + "), \""
                    + name + "\", text)";
        }
        return conversion(field.type(), "required(values, \"" + name + "\", " + field.nullable() + ")", name);
    }

    private static boolean isPhoneName(ApplicationField field) {
        return Set.of("telefono", "telefonofijo", "telefonomovil", "numerotelefono", "celular", "movil",
                        "phone", "phonenumber", "mobilephone")
                .contains(field.technicalName().toLowerCase(Locale.ROOT));
    }

    private static String conversion(CanonicalType type, String value, String name) {
        String method = switch (type) {
            case STRING -> "stringValue"; case INTEGER -> "integerValue"; case LONG -> "longValue";
            case DECIMAL -> "decimalValue"; case BOOLEAN -> "booleanValue";
            case DATE -> "dateValue"; case DATETIME -> "dateTimeValue";
        };
        return method + "(" + value + ", \"" + name + "\")";
    }

    private static String schemaJson(ApplicationSchema schema, Map<String, List<OwnedRelation>> relations) {
        StringBuilder out = new StringBuilder("{\"entities\":[");
        boolean firstEntity = true;
        for (ApplicationEntity entity : schema.entities()) {
            if (!firstEntity) out.append(',');
            firstEntity = false;
            out.append("{\"name\":").append(jsonQuote(entity.technicalName())).append(",\"fields\":[");
            boolean first = true;
            for (ApplicationField field : entity.fields()) {
                if (!first) out.append(',');
                first = false;
                out.append("{\"name\":").append(jsonQuote(field.technicalName()))
                        .append(",\"type\":").append(jsonQuote(field.type().name()))
                        .append(",\"nullable\":").append(field.nullable())
                        .append(",\"primaryKey\":").append(field.primaryKey())
                        .append(",\"generated\":").append(field.generated()).append('}');
            }
            out.append("],\"relations\":[");
            first = true;
            for (OwnedRelation relation : relations.get(entity.id())) {
                if (!first) out.append(',');
                first = false;
                out.append("{\"name\":").append(jsonQuote(relation.dtoPropertyName()))
                        .append(",\"target\":").append(jsonQuote(relation.target().technicalName()))
                        .append(",\"nullable\":").append(relation.optional())
                        .append(",\"multiple\":").append(relation.kind() == OwnedRelation.Kind.MANY_TO_MANY)
                        .append('}');
            }
            out.append("]}");
        }
        return out.append("]}").toString();
    }

    private static String jsonQuote(String value) {
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    private static String javaQuote(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n");
    }

    private static String upper(String value) { return Character.toUpperCase(value.charAt(0)) + value.substring(1); }
}
