package com.sw1.backend.ai.diagram.validation;

import com.sw1.backend.ai.client.AiServiceException;
import com.sw1.backend.ai.client.AiServiceException.ErrorCategory;
import com.sw1.backend.ai.diagram.dto.*;
import com.sw1.backend.ai.diagram.model.*;
import java.util.*;
import org.springframework.http.HttpStatus;

public final class DiagramContextMapper {
    private static final Set<String> MODIFIABLE_CLASS_KINDS = Set.of("ENTITY", "CLASS", "ASSOCIATION_CLASS");
    private static final Set<String> READ_ONLY_CLASSIFIER_KINDS = Set.of("INTERFACE", "ENUMERATION");
    private static final Set<String> SUPPORTED_NODE_KINDS = Set.of(
            "ENTITY", "CLASS", "INTERFACE", "ENUMERATION", "ASSOCIATION_CLASS");
    private static final Set<String> MULTIPLICATIVE_RELATION_KINDS = Set.of(
            "ASSOCIATION", "AGGREGATION", "COMPOSITION");
    private static final Set<String> SUPPORTED_RELATION_KINDS = Set.of(
            "ASSOCIATION", "GENERALIZATION", "REALIZATION", "AGGREGATION", "COMPOSITION", "DEPENDENCY");

    private DiagramContextMapper() {}

    public record SemanticNode(
            String id,
            String name,
            String kind,
            boolean modifiable,
            List<AttributeDefinition> attributes) {}

    public record SemanticRelationship(
            String id,
            String sourceNodeId,
            String targetNodeId,
            String sourceEntity,
            String targetEntity,
            String relationKind,
            DiagramCardinality sourceCardinality,
            DiagramCardinality targetCardinality,
            String name,
            String joinTableName) {}

    public record ReadOnlyUmlElement(
            String id,
            String name,
            String kind,
            List<String> literals,
            List<String> methodNames) {}

    public record ReadOnlyUmlRelation(
            String id,
            String relationKind,
            String sourceNodeId,
            String targetNodeId,
            String sourceName,
            String targetName) {}

    public record DiagramSemanticSnapshot(
            int version,
            DiagramContext diagramContext,
            List<SemanticNode> semanticNodes,
            List<SemanticRelationship> semanticRelationships,
            List<ReadOnlyUmlElement> readOnlyElements,
            List<ReadOnlyUmlRelation> readOnlyRelations,
            Set<String> readOnlyElementNames) {}

    public static DiagramContext fromDocument(Map<String, Object> document) {
        return inspectDocument(document).diagramContext();
    }

    public static DiagramSemanticSnapshot inspectDocument(Map<String, Object> document) {
        try {
            if (document == null) {
                throw new InvalidDocumentDetailException("documento UML nulo");
            }
            Object rawVersion = document.get("version");
            if (!(Integer.valueOf(1).equals(rawVersion) || Integer.valueOf(2).equals(rawVersion))) {
                throw new InvalidDocumentDetailException("version de documento no soportada: " + rawVersion);
            }
            int version = (Integer) rawVersion;
            boolean umlV2 = version == 2;

            List<?> rawNodes = list(document.get("nodes"), "nodes");
            List<?> rawEdges = list(document.get("edges"), "edges");

            Map<String, String> allNodeNamesById = new LinkedHashMap<>();
            Map<String, String> allNodeKindsById = new LinkedHashMap<>();
            Set<String> allClassifierNameKeys = new HashSet<>();

            Map<String, SemanticNode> modifiableNodesById = new LinkedHashMap<>();
            List<SemanticNode> semanticNodes = new ArrayList<>();
            List<EntityDefinition> entities = new ArrayList<>();
            List<ReadOnlyUmlElement> readOnlyElements = new ArrayList<>();
            Set<String> readOnlyNames = new LinkedHashSet<>();

            for (Object raw : rawNodes) {
                Map<?, ?> node = map(raw, "node");
                String id = text(node.get("id"), "node.id");
                Map<?, ?> data = map(node.get("data"), "node.data");
                String dataId = optionalText(data.get("id"), "node.data.id");
                if (dataId != null && !id.equals(dataId)) {
                    throw new InvalidDocumentDetailException("id visual y semantico de nodo no coinciden: " + id);
                }
                String name = text(data.get("name"), "node.data.name");
                String kind = resolveNodeKind(umlV2, data);
                if (allNodeNamesById.putIfAbsent(id, name) != null) {
                    throw new InvalidDocumentDetailException("id de nodo duplicado: " + id);
                }
                if (!allClassifierNameKeys.add(key(name))) {
                    throw new InvalidDocumentDetailException("nombre de elemento UML duplicado: " + name);
                }
                allNodeKindsById.put(id, kind);

                if (READ_ONLY_CLASSIFIER_KINDS.contains(kind)) {
                    if (data.get("association") != null) {
                        throw new InvalidDocumentDetailException(
                                "clasificador " + kind + " no puede declarar metadatos asociativos: " + name);
                    }
                    List<String> literals = extractLiterals(data.get("literals"));
                    List<String> methodNames = extractMethodNames(data.get("methods"));
                    readOnlyElements.add(new ReadOnlyUmlElement(id, name, kind, literals, methodNames));
                    readOnlyNames.add(name);
                    continue;
                }

                List<AttributeDefinition> attributes = new ArrayList<>();
                Set<String> attributeNames = new HashSet<>();
                for (Object value : list(data.get("attributes"), "node.data.attributes")) {
                    Map<?, ?> a = map(value, "attribute");
                    String attributeName = text(a.get("name"), "attribute.name");
                    if (!attributeNames.add(key(attributeName))) {
                        throw new InvalidDocumentDetailException(
                                "atributo duplicado en " + name + ": " + attributeName);
                    }
                    boolean pk = bool(a.get("primaryKey"), "attribute.primaryKey");
                    boolean nullable = a.containsKey("nullable")
                            ? bool(a.get("nullable"), "attribute.nullable")
                            : !pk;
                    attributes.add(new AttributeDefinition(
                            attributeName, dataType(text(a.get("type"), "attribute.type")), pk, nullable));
                }
                List<AttributeDefinition> immutableAttributes = List.copyOf(attributes);
                SemanticNode semanticNode = new SemanticNode(id, name, kind, true, immutableAttributes);
                modifiableNodesById.put(id, semanticNode);
                semanticNodes.add(semanticNode);
                entities.add(new EntityDefinition(name, immutableAttributes));
            }

            List<RelationshipDefinition> relationships = new ArrayList<>();
            List<SemanticRelationship> semanticRelationships = new ArrayList<>();
            List<ReadOnlyUmlRelation> readOnlyRelations = new ArrayList<>();
            Set<String> seenEdgeIds = new HashSet<>();

            for (Object raw : rawEdges) {
                Map<?, ?> edge = map(raw, "edge");
                // Older persisted diagrams did not require edge IDs. Keep them valid for
                // ordinary contextual operations, while exposing the real ID when present
                // so association conversion can require it explicitly.
                String relationshipId = optionalText(edge.get("id"), "edge.id");
                if (relationshipId != null && !seenEdgeIds.add(relationshipId)) {
                    throw new InvalidDocumentDetailException("id de relacion duplicado: " + relationshipId);
                }
                String sourceId = text(edge.get("source"), "edge.source");
                String targetId = text(edge.get("target"), "edge.target");
                String sourceAnyName = allNodeNamesById.get(sourceId);
                String targetAnyName = allNodeNamesById.get(targetId);
                if (sourceAnyName == null || targetAnyName == null) {
                    throw new InvalidDocumentDetailException(
                            "relacion referencia extremo inexistente: " + sourceId + " -> " + targetId);
                }

                // React Flow edge.type is a visual style, never a cardinality.
                Map<?, ?> data = edge.get("data") == null ? Map.of() : map(edge.get("data"), "edge.data");
                String relationKind = resolveRelationKind(umlV2, data);

                SemanticNode sourceClassNode = modifiableNodesById.get(sourceId);
                SemanticNode targetClassNode = modifiableNodesById.get(targetId);

                if (sourceClassNode == null
                        || targetClassNode == null
                        || (umlV2 && !MULTIPLICATIVE_RELATION_KINDS.contains(relationKind))) {
                    readOnlyRelations.add(new ReadOnlyUmlRelation(
                            relationshipId, relationKind, sourceId, targetId, sourceAnyName, targetAnyName));
                    continue;
                }

                DiagramCardinality sourceCardinality;
                DiagramCardinality targetCardinality;
                String relName;
                String joinTableName;

                if (data.containsKey("sourceCardinality") || data.containsKey("targetCardinality")) {
                    sourceCardinality = parseCardinality(text(data.get("sourceCardinality"), "sourceCardinality"));
                    targetCardinality = parseCardinality(text(data.get("targetCardinality"), "targetCardinality"));
                    relName = optionalText(data.get("name"), "edge.data.name");
                    joinTableName = optionalText(data.get("joinTableName"), "edge.data.joinTableName");
                } else {
                    Object legacyKind = data.get("relationshipType");
                    RelationshipType type = legacyKind == null ? null : parseLegacyType(text(legacyKind, "relationshipType"));
                    RelationshipDefinition legacy = RelationshipDefinition.fromLegacy(
                            sourceClassNode.name(), targetClassNode.name(), type);
                    sourceCardinality = legacy.sourceCardinality();
                    targetCardinality = legacy.targetCardinality();
                    relName = optionalText(data.get("name"), "edge.data.name");
                    joinTableName = optionalText(data.get("joinTableName"), "edge.data.joinTableName");
                }

                relationships.add(new RelationshipDefinition(
                        sourceClassNode.name(),
                        targetClassNode.name(),
                        sourceCardinality,
                        targetCardinality,
                        relName,
                        joinTableName,
                        relationshipId));
                semanticRelationships.add(new SemanticRelationship(
                        relationshipId,
                        sourceId,
                        targetId,
                        sourceClassNode.name(),
                        targetClassNode.name(),
                        relationKind,
                        sourceCardinality,
                        targetCardinality,
                        relName,
                        joinTableName));
            }

            List<DiagramAssociationContext> associations = new ArrayList<>();
            for (Object raw : rawNodes) {
                Map<?, ?> node = map(raw, "node");
                String nodeId = text(node.get("id"), "node.id");
                Map<?, ?> data = map(node.get("data"), "node.data");
                if (data.get("association") == null) {
                    continue;
                }
                SemanticNode associationNode = modifiableNodesById.get(nodeId);
                if (associationNode == null) {
                    throw new InvalidDocumentDetailException(
                            "nodo asociativo no pertenece a una clase o entidad modificable: " + nodeId);
                }
                Map<?, ?> association = map(data.get("association"), "node.data.association");
                if (!"MANY_TO_MANY_ASSOCIATION".equals(text(association.get("kind"), "association.kind"))
                        || !bool(association.get("uniquePair"), "association.uniquePair")) {
                    throw new InvalidDocumentDetailException(
                            "metadatos de clase asociativa no soportados en " + associationNode.name());
                }
                List<String> endpointNames = new ArrayList<>();
                List<String> relationshipIds = new ArrayList<>();
                Set<String> endpointEntityIds = new HashSet<>();
                for (Object endpointRaw : list(association.get("endpoints"), "association.endpoints")) {
                    Map<?, ?> endpoint = map(endpointRaw, "association.endpoint");
                    String endpointEntityId = text(endpoint.get("entityId"), "association.endpoint.entityId");
                    if (endpointEntityId.equals(nodeId) || !endpointEntityIds.add(endpointEntityId)) {
                        throw new InvalidDocumentDetailException(
                                "extremo de clase asociativa duplicado o autorreferenciado: " + endpointEntityId);
                    }
                    SemanticNode endpointNode = modifiableNodesById.get(endpointEntityId);
                    if (endpointNode == null) {
                        throw new InvalidDocumentDetailException(
                                "extremo de clase asociativa inexistente o no compatible: " + endpointEntityId);
                    }
                    String structuralRelId = text(endpoint.get("relationshipId"), "association.endpoint.relationshipId");
                    if (!seenEdgeIds.contains(structuralRelId)) {
                        throw new InvalidDocumentDetailException(
                                "relacion estructural de clase asociativa inexistente: " + structuralRelId);
                    }
                    endpointNames.add(endpointNode.name());
                    relationshipIds.add(structuralRelId);
                }
                if (endpointNames.size() != 2) {
                    throw new InvalidDocumentDetailException(
                            "la clase asociativa debe declarar exactamente 2 extremos: " + associationNode.name());
                }
                associations.add(new DiagramAssociationContext(
                        associationNode.name(),
                        text(association.get("tableName"), "association.tableName"),
                        List.copyOf(endpointNames),
                        List.copyOf(relationshipIds)));
            }

            Set<String> immutableReadOnlyNames = Set.copyOf(readOnlyNames);
            DiagramContext diagramContext = new DiagramContext(
                    List.copyOf(entities),
                    List.copyOf(relationships),
                    List.copyOf(associations),
                    immutableReadOnlyNames);

            return new DiagramSemanticSnapshot(
                    version,
                    diagramContext,
                    List.copyOf(semanticNodes),
                    List.copyOf(semanticRelationships),
                    List.copyOf(readOnlyElements),
                    List.copyOf(readOnlyRelations),
                    immutableReadOnlyNames);
        } catch (InvalidDocumentDetailException e) {
            throw new AiServiceException(
                    HttpStatus.CONFLICT,
                    ErrorCategory.INVALID_UML_DOCUMENT,
                    "El diagrama guardado no es valido para interpretacion contextual",
                    e.getMessage());
        }
    }

    public static String key(String value) {
        return value.toLowerCase(Locale.ROOT);
    }

    private static String resolveNodeKind(boolean umlV2, Map<?, ?> data) {
        if (!umlV2 || data.get("kind") == null) {
            return data.get("association") != null ? "ASSOCIATION_CLASS" : "ENTITY";
        }
        String kind = text(data.get("kind"), "node.data.kind");
        if (!SUPPORTED_NODE_KINDS.contains(kind)) {
            throw new InvalidDocumentDetailException("tipo de nodo UML no soportado: " + kind);
        }
        if (!MODIFIABLE_CLASS_KINDS.contains(kind) && !READ_ONLY_CLASSIFIER_KINDS.contains(kind)) {
            throw new InvalidDocumentDetailException("categoria UML no reconocida: " + kind);
        }
        return kind;
    }

    private static String resolveRelationKind(boolean umlV2, Map<?, ?> data) {
        if (!umlV2 || data.get("relationKind") == null) {
            return "ASSOCIATION";
        }
        String kind = text(data.get("relationKind"), "edge.data.relationKind");
        if (!SUPPORTED_RELATION_KINDS.contains(kind)) {
            throw new InvalidDocumentDetailException("tipo de relacion UML no soportado: " + kind);
        }
        return kind;
    }

    private static List<String> extractLiterals(Object rawLiterals) {
        if (rawLiterals == null) {
            return List.of();
        }
        List<String> literals = new ArrayList<>();
        for (Object item : list(rawLiterals, "literals")) {
            literals.add(text(item, "literal"));
        }
        return List.copyOf(literals);
    }

    private static List<String> extractMethodNames(Object rawMethods) {
        if (rawMethods == null) {
            return List.of();
        }
        List<String> methods = new ArrayList<>();
        for (Object item : list(rawMethods, "methods")) {
            Map<?, ?> method = map(item, "method");
            methods.add(text(method.get("name"), "method.name"));
        }
        return List.copyOf(methods);
    }

    private static DiagramCardinality parseCardinality(String raw) {
        try {
            return DiagramCardinality.valueOf(raw);
        } catch (IllegalArgumentException ex) {
            throw new InvalidDocumentDetailException("cardinalidad no soportada: " + raw);
        }
    }

    private static RelationshipType parseLegacyType(String raw) {
        try {
            return RelationshipType.valueOf(raw);
        } catch (IllegalArgumentException ex) {
            throw new InvalidDocumentDetailException("tipo de relacion legacy no soportado: " + raw);
        }
    }

    private static DiagramDataType dataType(String value) {
        return switch (value.toUpperCase(Locale.ROOT)) {
            case "VARCHAR", "TEXT", "STRING" -> DiagramDataType.String;
            case "BIGINT", "LONG" -> DiagramDataType.Long;
            case "INTEGER", "INT" -> DiagramDataType.Integer;
            case "DECIMAL", "DOUBLE", "FLOAT" -> DiagramDataType.Double;
            case "BOOLEAN", "BOOL" -> DiagramDataType.Boolean;
            case "DATE" -> DiagramDataType.Date;
            case "TIMESTAMP", "DATETIME" -> DiagramDataType.DateTime;
            default -> throw new InvalidDocumentDetailException("tipo de atributo no soportado: " + value);
        };
    }

    private static Map<?, ?> map(Object value, String field) {
        if (!(value instanceof Map<?, ?> m)) {
            throw new InvalidDocumentDetailException("estructura invalida en " + field);
        }
        return m;
    }

    private static List<?> list(Object value, String field) {
        if (!(value instanceof List<?> l)) {
            throw new InvalidDocumentDetailException("lista invalida o ausente en " + field);
        }
        return l;
    }

    private static String text(Object value, String field) {
        if (!(value instanceof String s) || s.isBlank() || !s.equals(s.strip()) || s.length() > 100) {
            throw new InvalidDocumentDetailException("texto invalido en " + field);
        }
        return s;
    }

    private static boolean bool(Object value, String field) {
        if (!(value instanceof Boolean b)) {
            throw new InvalidDocumentDetailException("booleano invalido en " + field);
        }
        return b;
    }

    private static String optionalText(Object value, String field) {
        return value == null ? null : text(value, field);
    }

    private static final class InvalidDocumentDetailException extends RuntimeException {
        InvalidDocumentDetailException(String detail) {
            super(detail);
        }
    }
}
