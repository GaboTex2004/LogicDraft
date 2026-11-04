package com.sw1.backend.ai.image;

import com.sw1.backend.diagrama.validation.DiagramValidationException;
import java.util.*;

/** Strict image-v1 allowlist. General text-agent operations are intentionally not accepted. */
public final class ImageRecognitionValidator {
    private ImageRecognitionValidator() {}
    public static final Set<String> KINDS = Set.of("ENTITY", "CLASS", "INTERFACE", "ENUMERATION", "ASSOCIATION_CLASS");
    public static final Set<String> RELATIONS = Set.of("ASSOCIATION", "GENERALIZATION", "REALIZATION", "AGGREGATION", "COMPOSITION", "DEPENDENCY");
    private static final Set<String> VISIBILITY = Set.of("public", "private", "protected", "package");

    public static Map<String, Object> validate(Map<String, Object> raw) {
        keys(raw, Set.of("operations"), Set.of("warnings"));
        for (Map<String, Object> op : objects(raw.get("operations"), 100)) {
            String type = text(op.get("type"));
            switch (type) {
                case "ADD_CLASSIFIER" -> {
                    keys(op, Set.of("type", "name", "attributes", "methods", "literals"), Set.of("kind", "abstract"));
                    text(op.get("name"));
                    if (op.get("kind") != null) choice(op.get("kind"), KINDS);
                    optionalBool(op, "abstract");
                    for (Map<String, Object> attr : objects(op.get("attributes"), 100)) {
                        keys(attr, Set.of("name", "type"), Set.of("primaryKey", "nullable", "visibility", "static"));
                        text(attr.get("name")); text(attr.get("type"));
                        optionalBool(attr, "primaryKey"); optionalBool(attr, "nullable"); optionalBool(attr, "static");
                        optionalChoice(attr, "visibility", VISIBILITY);
                    }
                    for (Map<String, Object> method : objects(op.get("methods"), 100)) {
                        keys(method, Set.of("name", "returnType", "parameters"), Set.of("visibility", "static", "abstract"));
                        text(method.get("name")); text(method.get("returnType"));
                        optionalChoice(method, "visibility", VISIBILITY);
                        optionalBool(method, "static"); optionalBool(method, "abstract");
                        for (Map<String, Object> p : objects(method.get("parameters"), 30)) {
                            keys(p, Set.of("name", "type"), Set.of()); text(p.get("name")); text(p.get("type"));
                        }
                    }
                    for (Object literal : list(op.get("literals"), 100)) text(literal);
                }
                case "ADD_RELATIONSHIP" -> {
                    keys(op, Set.of("type", "source", "target", "relationKind"),
                            Set.of("name", "sourceMultiplicity", "targetMultiplicity", "aggregationEnd"));
                    text(op.get("source")); text(op.get("target")); choice(op.get("relationKind"), RELATIONS);
                    for (String field : List.of("name", "sourceMultiplicity", "targetMultiplicity"))
                        if (op.get(field) != null) text(op.get(field));
                    optionalChoice(op, "aggregationEnd", Set.of("SOURCE", "TARGET"));
                }
                default -> fail("Operacion de imagen no permitida: " + type);
            }
        }
        for (Object warning : list(raw.getOrDefault("warnings", List.of()), 100))
            if (!(warning instanceof String s) || s.isBlank() || s.length() > 500) fail("Advertencia invalida");
        return raw;
    }

    private static void keys(Map<String, Object> map, Set<String> required, Set<String> optional) {
        if (map == null || !map.keySet().containsAll(required)) fail("Contrato de imagen incompleto");
        Set<String> allowed = new HashSet<>(required); allowed.addAll(optional);
        if (!allowed.containsAll(map.keySet())) fail("Campos de imagen no permitidos");
    }
    public static String text(Object raw) {
        if (!(raw instanceof String s) || s.isBlank() || s.length() > 100 || !s.equals(s.strip()) || !s.matches("\\S(?:.*\\S)?"))
            fail("Nombre o tipo de imagen invalido");
        return (String) raw;
    }
    private static void choice(Object raw, Set<String> choices) {
        if (!choices.contains(text(raw))) fail("Valor UML no soportado: " + raw);
    }
    private static void optionalChoice(Map<String, Object> map, String key, Set<String> choices) {
        if (map.get(key) != null) choice(map.get(key), choices);
    }
    private static void optionalBool(Map<String, Object> map, String key) {
        if (map.get(key) != null && !(map.get(key) instanceof Boolean)) fail("Booleano invalido: " + key);
    }
    public static List<?> list(Object raw, int max) {
        if (!(raw instanceof List<?> items) || items.size() > max) fail("Lista de imagen invalida");
        return (List<?>) raw;
    }
    @SuppressWarnings("unchecked")
    public static List<Map<String, Object>> objects(Object raw, int max) {
        List<?> items = list(raw, max);
        if (items.stream().anyMatch(item -> !(item instanceof Map<?, ?>))) fail("Objeto de imagen invalido");
        return (List<Map<String, Object>>) items;
    }
    private static void fail(String message) { throw new DiagramValidationException(message); }
}
