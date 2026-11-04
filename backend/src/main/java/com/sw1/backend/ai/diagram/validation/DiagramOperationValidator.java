package com.sw1.backend.ai.diagram.validation;

import com.sw1.backend.ai.client.AiServiceException;
import com.sw1.backend.ai.diagram.dto.*;
import com.sw1.backend.ai.diagram.model.*;
import java.util.*;
import org.springframework.http.HttpStatus;

public final class DiagramOperationValidator {
    private static final Set<String> SUPPORTED_CLASSIFIER_KINDS = Set.of(
            "ENTITY", "CLASS", "INTERFACE", "ENUMERATION");
    private static final Set<String> SUPPORTED_RELATION_KINDS = Set.of(
            "ASSOCIATION", "GENERALIZATION", "REALIZATION", "AGGREGATION", "COMPOSITION", "DEPENDENCY");
    private static final Set<String> MULTIPLICATIVE_RELATION_KINDS = Set.of(
            "ASSOCIATION", "AGGREGATION", "COMPOSITION");
    private static final Set<String> SUPPORTED_VISIBILITIES = Set.of(
            "public", "private", "protected", "package");
    private static final Set<String> SUPPORTED_AGGREGATION_ENDS = Set.of(
            "SOURCE", "TARGET");

    private DiagramOperationValidator() {}

    public static DiagramInterpretResponse validate(Map<String, Object> raw) {
        try {
            Map<?, ?> root = object(raw, "operations");
            List<DiagramOperation> operations = new ArrayList<>();
            for (Object item : list(root.get("operations"), 50)) {
                if (!(item instanceof Map<?, ?> op)) throw new IllegalArgumentException();
                DiagramOperationType type = DiagramOperationType.valueOf(text(op.get("type")));
                switch (type) {
                    case ADD_ENTITY -> {
                        object(op, "type", "entity");
                        Map<?, ?> entity = objectWithOptional(op.get("entity"),
                                Set.of("name"),
                                Set.of("attributes", "kind", "classifierKind", "abstract", "methods", "literals"));
                        String kind = optionalClassifierKind(
                                entity.get("kind") != null ? entity.get("kind") : entity.get("classifierKind"));
                        boolean allowsMissingAttributes = "INTERFACE".equals(kind) || "ENUMERATION".equals(kind);
                        if (entity.get("attributes") == null && !allowsMissingAttributes) {
                            throw new IllegalArgumentException();
                        }
                        List<AttributeDefinition> attributes = new ArrayList<>();
                        if (entity.get("attributes") != null) {
                            for (Object value : list(entity.get("attributes"), 100)) {
                                attributes.add(attribute(value));
                            }
                        }
                        Boolean isAbstract = optionalBoolean(entity.get("abstract"));
                        List<MethodDefinition> methods = entity.get("methods") == null
                                ? null
                                : parseMethods(entity.get("methods"));
                        List<String> literals = entity.get("literals") == null
                                ? null
                                : parseStringList(entity.get("literals"), 100);
                        operations.add(new DiagramOperation(type,
                                new EntityDefinition(
                                        text(entity.get("name")),
                                        List.copyOf(attributes),
                                        kind,
                                        isAbstract,
                                        methods,
                                        literals),
                                null, null, null));
                    }
                    case DELETE_ENTITY -> {
                        object(op, "type", "entityName");
                        operations.add(operation(type, text(op.get("entityName")), null, null, null, null, null, null));
                    }
                    case RENAME_ENTITY -> {
                        object(op, "type", "entityName", "newName");
                        operations.add(operation(type, text(op.get("entityName")), null, null,
                                text(op.get("newName")), null, null, null));
                    }
                    case UPDATE_CLASSIFIER -> {
                        objectWithOptional(op, Set.of("type", "entityName"), Set.of("kind", "classifierKind", "abstract"));
                        String kind = optionalClassifierKind(
                                op.get("kind") != null ? op.get("kind") : op.get("classifierKind"));
                        Boolean isAbstract = optionalBoolean(op.get("abstract"));
                        if (kind == null && isAbstract == null) throw new IllegalArgumentException();
                        operations.add(DiagramOperation.updateClassifier(text(op.get("entityName")), kind, isAbstract));
                    }
                    case ADD_ATTRIBUTE -> {
                        object(op, "type", "entityName", "attribute");
                        operations.add(new DiagramOperation(type, null, text(op.get("entityName")), attribute(op.get("attribute")), null));
                    }
                    case DELETE_ATTRIBUTE -> {
                        object(op, "type", "entityName", "attributeName");
                        operations.add(operation(type, text(op.get("entityName")), text(op.get("attributeName")),
                                null, null, null, null, null));
                    }
                    case RENAME_ATTRIBUTE -> {
                        object(op, "type", "entityName", "attributeName", "newName");
                        operations.add(operation(type, text(op.get("entityName")), text(op.get("attributeName")),
                                null, text(op.get("newName")), null, null, null));
                    }
                    case CHANGE_ATTRIBUTE_TYPE -> {
                        object(op, "type", "entityName", "attributeName", "dataType");
                        operations.add(operation(type, text(op.get("entityName")), text(op.get("attributeName")),
                                DiagramDataType.valueOf(text(op.get("dataType"))), null, null, null, null));
                    }
                    case SET_ATTRIBUTE_PRIMARY_KEY, SET_ATTRIBUTE_NULLABLE -> {
                        object(op, "type", "entityName", "attributeName", "value");
                        if (!(op.get("value") instanceof Boolean value)) throw new IllegalArgumentException();
                        operations.add(operation(type, text(op.get("entityName")), text(op.get("attributeName")),
                                null, null, value, null, null));
                    }
                    case SET_ATTRIBUTE_VISIBILITY -> {
                        object(op, "type", "entityName", "attributeName", "visibility");
                        operations.add(DiagramOperation.setAttributeVisibility(
                                text(op.get("entityName")),
                                text(op.get("attributeName")),
                                visibility(op.get("visibility"))));
                    }
                    case SET_ATTRIBUTE_STATIC -> {
                        objectWithOptional(op, Set.of("type", "entityName", "attributeName"), Set.of("value", "static"));
                        Object rawVal = op.get("value") != null ? op.get("value") : op.get("static");
                        if (!(rawVal instanceof Boolean value)) throw new IllegalArgumentException();
                        operations.add(DiagramOperation.setAttributeStatic(
                                text(op.get("entityName")),
                                text(op.get("attributeName")),
                                value));
                    }
                    case ADD_METHOD -> {
                        object(op, "type", "entityName", "method");
                        operations.add(DiagramOperation.addMethod(
                                text(op.get("entityName")),
                                parseMethod(op.get("method"))));
                    }
                    case UPDATE_METHOD -> {
                        objectWithOptional(op,
                                Set.of("type", "entityName", "methodName"),
                                Set.of("parameterTypes", "newName", "returnType", "visibility", "static", "abstract", "method"));
                        String entityName = text(op.get("entityName"));
                        String methodName = text(op.get("methodName"));
                        List<String> paramTypes = op.get("parameterTypes") == null
                                ? null
                                : parseStringList(op.get("parameterTypes"), 20);
                        String newName = optionalText(op.get("newName"));
                        String returnType = optionalText(op.get("returnType"));
                        String vis = op.get("visibility") == null ? null : visibility(op.get("visibility"));
                        Boolean isStatic = optionalBoolean(op.get("static"));
                        Boolean isAbstract = optionalBoolean(op.get("abstract"));
                        MethodDefinition method = op.get("method") == null ? null : parseMethod(op.get("method"));
                        if (newName == null && returnType == null && vis == null
                                && isStatic == null && isAbstract == null && method == null) {
                            throw new IllegalArgumentException();
                        }
                        operations.add(DiagramOperation.updateMethod(
                                entityName, methodName, paramTypes, newName, returnType, vis, isStatic, isAbstract, method));
                    }
                    case DELETE_METHOD -> {
                        objectWithOptional(op, Set.of("type", "entityName", "methodName"), Set.of("parameterTypes"));
                        List<String> paramTypes = op.get("parameterTypes") == null
                                ? null
                                : parseStringList(op.get("parameterTypes"), 20);
                        operations.add(DiagramOperation.deleteMethod(
                                text(op.get("entityName")),
                                text(op.get("methodName")),
                                paramTypes));
                    }
                    case ADD_METHOD_PARAMETER -> {
                        objectWithOptional(op,
                                Set.of("type", "entityName", "methodName", "parameter"),
                                Set.of("parameterTypes"));
                        List<String> paramTypes = op.get("parameterTypes") == null
                                ? null
                                : parseStringList(op.get("parameterTypes"), 20);
                        operations.add(DiagramOperation.addMethodParameter(
                                text(op.get("entityName")),
                                text(op.get("methodName")),
                                paramTypes,
                                parseParameter(op.get("parameter"))));
                    }
                    case UPDATE_METHOD_PARAMETER -> {
                        objectWithOptional(op,
                                Set.of("type", "entityName", "methodName", "parameterName"),
                                Set.of("parameterTypes", "newName", "newType", "parameter"));
                        String entityName = text(op.get("entityName"));
                        String methodName = text(op.get("methodName"));
                        List<String> paramTypes = op.get("parameterTypes") == null
                                ? null
                                : parseStringList(op.get("parameterTypes"), 20);
                        String parameterName = text(op.get("parameterName"));
                        String newName = optionalText(op.get("newName"));
                        String newType = optionalText(op.get("newType"));
                        ParameterDefinition parameter = op.get("parameter") == null
                                ? null
                                : parseParameter(op.get("parameter"));
                        if (newName == null && newType == null && parameter == null) {
                            throw new IllegalArgumentException();
                        }
                        operations.add(DiagramOperation.updateMethodParameter(
                                entityName, methodName, paramTypes, parameterName, newName, newType, parameter));
                    }
                    case DELETE_METHOD_PARAMETER -> {
                        objectWithOptional(op,
                                Set.of("type", "entityName", "methodName", "parameterName"),
                                Set.of("parameterTypes"));
                        List<String> paramTypes = op.get("parameterTypes") == null
                                ? null
                                : parseStringList(op.get("parameterTypes"), 20);
                        operations.add(DiagramOperation.deleteMethodParameter(
                                text(op.get("entityName")),
                                text(op.get("methodName")),
                                paramTypes,
                                text(op.get("parameterName"))));
                    }
                    case ADD_ENUM_LITERAL -> {
                        object(op, "type", "entityName", "literal");
                        operations.add(DiagramOperation.addEnumLiteral(
                                text(op.get("entityName")),
                                text(op.get("literal"))));
                    }
                    case RENAME_ENUM_LITERAL -> {
                        objectWithOptional(op, Set.of("type", "entityName", "literal"), Set.of("newLiteral", "newName"));
                        Object rawNew = op.get("newLiteral") != null ? op.get("newLiteral") : op.get("newName");
                        if (rawNew == null) throw new IllegalArgumentException();
                        operations.add(DiagramOperation.renameEnumLiteral(
                                text(op.get("entityName")),
                                text(op.get("literal")),
                                text(rawNew)));
                    }
                    case DELETE_ENUM_LITERAL -> {
                        object(op, "type", "entityName", "literal");
                        operations.add(DiagramOperation.deleteEnumLiteral(
                                text(op.get("entityName")),
                                text(op.get("literal"))));
                    }
                    case ADD_RELATIONSHIP -> {
                        object(op, "type", "relationship");
                        Map<?, ?> rel = objectWithOptional(op.get("relationship"),
                                Set.of("sourceEntity", "targetEntity"),
                                Set.of("sourceCardinality", "targetCardinality", "name", "joinTableName",
                                        "kind", "relationKind", "sourceMultiplicity", "targetMultiplicity",
                                        "aggregationEnd", "id"));
                        String relKind = optionalRelationKind(rel);
                        boolean multiplicative = relKind == null || MULTIPLICATIVE_RELATION_KINDS.contains(relKind);
                        DiagramCardinality sourceCard = null;
                        DiagramCardinality targetCard = null;
                        if (multiplicative) {
                            sourceCard = DiagramCardinality.valueOf(text(rel.get("sourceCardinality")));
                            targetCard = DiagramCardinality.valueOf(text(rel.get("targetCardinality")));
                        } else {
                            if (rel.get("sourceCardinality") != null) {
                                sourceCard = DiagramCardinality.valueOf(text(rel.get("sourceCardinality")));
                            }
                            if (rel.get("targetCardinality") != null) {
                                targetCard = DiagramCardinality.valueOf(text(rel.get("targetCardinality")));
                            }
                        }
                        String aggregationEnd = optionalAggregationEnd(rel.get("aggregationEnd"));
                        operations.add(new DiagramOperation(type, null, null, null, new RelationshipDefinition(
                                text(rel.get("sourceEntity")),
                                text(rel.get("targetEntity")),
                                sourceCard,
                                targetCard,
                                optionalText(rel.get("name")),
                                optionalText(rel.get("joinTableName")),
                                optionalText(rel.get("id")),
                                relKind,
                                optionalText(rel.get("sourceMultiplicity")),
                                optionalText(rel.get("targetMultiplicity")),
                                aggregationEnd)));
                    }
                    case DELETE_RELATIONSHIP -> {
                        object(op, "type", "relationship");
                        Map<?, ?> rel = objectWithOptional(op.get("relationship"),
                                Set.of("sourceEntity", "targetEntity"),
                                Set.of("name", "kind", "relationKind", "id"));
                        String relKind = optionalRelationKind(rel);
                        var reference = new RelationshipDefinition(
                                text(rel.get("sourceEntity")),
                                text(rel.get("targetEntity")),
                                null,
                                null,
                                optionalText(rel.get("name")),
                                null,
                                optionalText(rel.get("id")),
                                relKind,
                                null,
                                null,
                                null);
                        operations.add(operation(type, null, null, null, null, null, reference, null));
                    }
                    case UPDATE_RELATIONSHIP -> {
                        object(op, "type", "relationship");
                        Map<?, ?> rel = objectWithOptional(op.get("relationship"),
                                Set.of("sourceEntity", "targetEntity"),
                                Set.of("sourceCardinality", "targetCardinality", "name", "joinTableName",
                                        "kind", "relationKind", "sourceMultiplicity", "targetMultiplicity",
                                        "aggregationEnd", "id"));
                        String relKind = optionalRelationKind(rel);
                        boolean multiplicative = relKind == null || MULTIPLICATIVE_RELATION_KINDS.contains(relKind);
                        DiagramCardinality sourceCard = null;
                        DiagramCardinality targetCard = null;
                        if (multiplicative) {
                            sourceCard = DiagramCardinality.valueOf(text(rel.get("sourceCardinality")));
                            targetCard = DiagramCardinality.valueOf(text(rel.get("targetCardinality")));
                        } else {
                            if (rel.get("sourceCardinality") != null) {
                                sourceCard = DiagramCardinality.valueOf(text(rel.get("sourceCardinality")));
                            }
                            if (rel.get("targetCardinality") != null) {
                                targetCard = DiagramCardinality.valueOf(text(rel.get("targetCardinality")));
                            }
                        }
                        String aggregationEnd = optionalAggregationEnd(rel.get("aggregationEnd"));
                        var update = new RelationshipDefinition(
                                text(rel.get("sourceEntity")),
                                text(rel.get("targetEntity")),
                                sourceCard,
                                targetCard,
                                optionalText(rel.get("name")),
                                optionalText(rel.get("joinTableName")),
                                optionalText(rel.get("id")),
                                relKind,
                                optionalText(rel.get("sourceMultiplicity")),
                                optionalText(rel.get("targetMultiplicity")),
                                aggregationEnd);
                        operations.add(operation(type, null, null, null, null, null, update, null));
                    }
                    case CREATE_ASSOCIATION -> {
                        object(op, "type", "association");
                        Map<?, ?> association = object(op.get("association"), "sourceEntity", "targetEntity",
                                "associationEntityName", "attributes");
                        List<AttributeDefinition> attributes = new ArrayList<>();
                        for (Object value : list(association.get("attributes"), 100)) {
                            AttributeDefinition attribute = attribute(value);
                            if (attribute.primaryKey()) throw new IllegalArgumentException();
                            attributes.add(attribute);
                        }
                        var definition = new AssociationCreationDefinition(text(association.get("sourceEntity")),
                                text(association.get("targetEntity")), text(association.get("associationEntityName")),
                                List.copyOf(attributes));
                        operations.add(operation(type, null, null, null, null, null, null, definition));
                    }
                    case DELETE_ASSOCIATION -> {
                        object(op, "type", "associationEntityName");
                        operations.add(new DiagramOperation(type, null, null, null, null, null, null, null,
                                null, null, null, text(op.get("associationEntityName"))));
                    }
                    case CONVERT_MANY_TO_MANY_ASSOCIATION -> {
                        object(op, "type", "conversion");
                        Map<?, ?> conversion = object(op.get("conversion"), "relationshipId", "sourceEntity",
                                "targetEntity", "associationEntityName", "attributes");
                        List<AttributeDefinition> attributes = new ArrayList<>();
                        for (Object value : list(conversion.get("attributes"), 100)) {
                            AttributeDefinition attribute = attribute(value);
                            if (attribute.primaryKey()) throw new IllegalArgumentException();
                            attributes.add(attribute);
                        }
                        operations.add(new DiagramOperation(type, null, null, null, null,
                                new AssociationConversionDefinition(text(conversion.get("relationshipId")),
                                        text(conversion.get("sourceEntity")), text(conversion.get("targetEntity")),
                                        text(conversion.get("associationEntityName")), List.copyOf(attributes))));
                    }
                }
            }
            return new DiagramInterpretResponse(List.copyOf(operations));
        } catch (IllegalArgumentException exception) {
            throw new AiServiceException(
                    HttpStatus.BAD_GATEWAY,
                    AiServiceException.ErrorCategory.INVALID_MODEL_RESPONSE,
                    "El servicio de IA devolvio operaciones invalidas",
                    "Operacion propuesta no cumple el esquema DiagramOperation");
        }
    }

    private static AttributeDefinition attribute(Object raw) {
        Map<?, ?> a = objectWithOptional(raw,
                Set.of("name", "dataType", "primaryKey", "nullable"),
                Set.of("visibility", "static"));
        if (!(a.get("primaryKey") instanceof Boolean pk) || !(a.get("nullable") instanceof Boolean nullable))
            throw new IllegalArgumentException();
        String vis = a.get("visibility") == null ? null : visibility(a.get("visibility"));
        Boolean isStatic = optionalBoolean(a.get("static"));
        return new AttributeDefinition(
                text(a.get("name")),
                DiagramDataType.valueOf(text(a.get("dataType"))),
                pk,
                nullable,
                vis,
                isStatic);
    }

    private static List<MethodDefinition> parseMethods(Object raw) {
        List<MethodDefinition> methods = new ArrayList<>();
        for (Object item : list(raw, 100)) {
            methods.add(parseMethod(item));
        }
        return List.copyOf(methods);
    }

    private static MethodDefinition parseMethod(Object raw) {
        Map<?, ?> m = objectWithOptional(raw,
                Set.of("name"),
                Set.of("returnType", "visibility", "static", "abstract", "parameters"));
        String name = text(m.get("name"));
        String returnType = m.get("returnType") == null ? "void" : text(m.get("returnType"));
        String vis = m.get("visibility") == null ? "public" : visibility(m.get("visibility"));
        Boolean isStatic = optionalBoolean(m.get("static"));
        Boolean isAbstract = optionalBoolean(m.get("abstract"));
        if (Boolean.TRUE.equals(isStatic) && Boolean.TRUE.equals(isAbstract)) {
            throw new IllegalArgumentException();
        }
        List<ParameterDefinition> params = new ArrayList<>();
        if (m.get("parameters") != null) {
            for (Object pRaw : list(m.get("parameters"), 20)) {
                params.add(parseParameter(pRaw));
            }
        }
        return new MethodDefinition(name, returnType, vis, isStatic, isAbstract, List.copyOf(params));
    }

    private static ParameterDefinition parseParameter(Object raw) {
        Map<?, ?> p = objectWithOptional(raw, Set.of("name"), Set.of("type", "dataType"));
        String name = text(p.get("name"));
        Object rawType = p.get("type") != null ? p.get("type") : p.get("dataType");
        String type = rawType == null ? "String" : text(rawType);
        return new ParameterDefinition(name, type);
    }

    private static List<String> parseStringList(Object raw, int max) {
        List<String> items = new ArrayList<>();
        for (Object item : list(raw, max)) {
            items.add(text(item));
        }
        return List.copyOf(items);
    }

    private static String optionalClassifierKind(Object raw) {
        if (raw == null) return null;
        String kind = text(raw);
        if (!SUPPORTED_CLASSIFIER_KINDS.contains(kind)) throw new IllegalArgumentException();
        return kind;
    }

    private static String optionalRelationKind(Map<?, ?> rel) {
        Object rawKind = rel.get("kind") != null ? rel.get("kind") : rel.get("relationKind");
        if (rawKind == null) return null;
        String kind = text(rawKind);
        if (!SUPPORTED_RELATION_KINDS.contains(kind)) throw new IllegalArgumentException();
        return kind;
    }

    private static String optionalAggregationEnd(Object raw) {
        if (raw == null) return null;
        String end = text(raw);
        if (!SUPPORTED_AGGREGATION_ENDS.contains(end)) throw new IllegalArgumentException();
        return end;
    }

    private static String visibility(Object raw) {
        String vis = text(raw);
        if (!SUPPORTED_VISIBILITIES.contains(vis.toLowerCase(Locale.ROOT))) throw new IllegalArgumentException();
        return vis;
    }

    private static Boolean optionalBoolean(Object raw) {
        if (raw == null) return null;
        if (!(raw instanceof Boolean b)) throw new IllegalArgumentException();
        return b;
    }

    private static DiagramOperation operation(DiagramOperationType type, String entityName, String attributeName,
            DiagramDataType dataType, String newName, Boolean value,
            RelationshipDefinition relationship,
            AssociationCreationDefinition association) {
        return new DiagramOperation(type, null, entityName, null, relationship, null, attributeName, newName, dataType,
                value, association, null);
    }

    private static Map<?, ?> object(Object raw, String... keys) {
        if (!(raw instanceof Map<?, ?> map) || !map.keySet().equals(Set.of(keys))) throw new IllegalArgumentException();
        return map;
    }
    private static Map<?, ?> objectWithOptional(Object raw, Set<String> required, Set<String> optional) {
        if (!(raw instanceof Map<?, ?> map) || !map.keySet().containsAll(required)
                || map.keySet().stream().anyMatch(key -> !(key instanceof String text)
                || !required.contains(text) && !optional.contains(text))) throw new IllegalArgumentException();
        return map;
    }

    private static List<?> list(Object raw, int max) {
        if (!(raw instanceof List<?> list) || list.size() > max) throw new IllegalArgumentException();
        return list;
    }

    private static String text(Object raw) {
        if (!(raw instanceof String s) || s.isBlank() || !s.equals(s.strip()) || s.length() > 100)
            throw new IllegalArgumentException();
        return s;
    }
    private static String optionalText(Object raw) { return raw == null ? null : text(raw); }
}
