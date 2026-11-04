package com.sw1.backend.ai.conversation.service;

import com.sw1.backend.ai.diagram.dto.AssociationConversionDefinition;
import com.sw1.backend.ai.diagram.dto.AssociationCreationDefinition;
import com.sw1.backend.ai.diagram.dto.AttributeDefinition;
import com.sw1.backend.ai.diagram.dto.DiagramOperation;
import com.sw1.backend.ai.diagram.dto.EntityDefinition;
import com.sw1.backend.ai.diagram.dto.MethodDefinition;
import com.sw1.backend.ai.diagram.dto.ParameterDefinition;
import com.sw1.backend.ai.diagram.dto.RelationshipDefinition;
import com.sw1.backend.ai.diagram.model.DiagramCardinality;
import com.sw1.backend.ai.diagram.model.DiagramDataType;
import com.sw1.backend.diagrama.service.DiagramThreeWayMerge;
import com.sw1.backend.diagrama.validation.DiagramDocumentValidator;
import com.sw1.backend.diagrama.validation.DiagramValidationException;

import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Server-authoritative deterministic applier for all validated {@link DiagramOperation} types.
 *
 * <p>Preserves UML v1 and UML v2 documents, non-entity UML nodes ({@code CLASS},
 * {@code INTERFACE}, {@code ENUMERATION}), methods, parameters, literals, UML relationships, and
 * Enterprise Architect external metadata while enforcing {@link DiagramDocumentValidator}
 * invariants before persistence.
 */
public final class ServerDiagramOperationApplier {
    private static final int MAX_DOCUMENT_BYTES = 1_000_000;
    private static final Map<DiagramDataType, String> AI_TYPE_MAP = Map.of(
            DiagramDataType.String, "VARCHAR",
            DiagramDataType.Long, "BIGINT",
            DiagramDataType.Integer, "INTEGER",
            DiagramDataType.Double, "DECIMAL",
            DiagramDataType.Boolean, "BOOLEAN",
            DiagramDataType.Date, "DATE",
            DiagramDataType.DateTime, "TIMESTAMP");
    private static final Map<String, List<String>> LEGACY_CARDINALITIES = Map.of(
            "ONE_TO_ONE", List.of("ONE_ONE", "ONE_ONE"),
            "ONE_TO_MANY", List.of("ONE_ONE", "ZERO_MANY"),
            "MANY_TO_ONE", List.of("ZERO_MANY", "ONE_ONE"),
            "MANY_TO_MANY", List.of("ZERO_MANY", "ZERO_MANY"));
    private static final Set<String> MULTIPLICATIVE_RELATION_KINDS = Set.of(
            "ASSOCIATION", "AGGREGATION", "COMPOSITION");
    private static final Set<String> NON_MULTIPLICATIVE_RELATION_KINDS = Set.of(
            "GENERALIZATION", "REALIZATION", "DEPENDENCY");

    private ServerDiagramOperationApplier() {
    }

    public record ApplierResult(
            int version,
            Map<String, Object> document,
            boolean changed,
            List<String> affectedElementIds,
            Set<String> lockProtectedElementIds,
            String summary) {
    }

    public static Map<String, Object> emptyDocument() {
        Map<String, Object> doc = new LinkedHashMap<>();
        doc.put("version", 1);
        doc.put("nodes", List.of());
        doc.put("edges", List.of());
        return doc;
    }

    public static ApplierResult apply(
            Map<String, Object> currentDocument,
            List<DiagramOperation> operations) {
        return apply(currentDocument, operations, () -> UUID.randomUUID().toString());
    }

    public static ApplierResult apply(
            Map<String, Object> currentDocument,
            List<DiagramOperation> operations,
            Supplier<String> idSupplier) {
        if (operations == null || operations.isEmpty()) {
            throw new DiagramValidationException("La propuesta no contiene operaciones UML para aplicar.");
        }
        Map<String, Object> baseDoc = currentDocument != null ? deepCopyMap(currentDocument) : emptyDocument();
        int currentVersion = baseDoc.get("version") instanceof Number n ? n.intValue() : 1;
        if (currentVersion != 1 && currentVersion != 2) {
            throw new DiagramValidationException("La versión del documento del diagrama no está soportada.");
        }

        List<Map<String, Object>> nodes = cloneObjectList(baseDoc.get("nodes"), "nodes");
        List<Map<String, Object>> edges = cloneObjectList(baseDoc.get("edges"), "edges");
        // Normalize legacy relationshipType edges on a deep copy while preserving UML v2 fields
        List<Map<String, Object>> normalizedBaseEdges = new ArrayList<>();
        for (Map<String, Object> edge : edges) {
            normalizeEdgeInPlace(edge);
            normalizedBaseEdges.add(deepCopyMap(edge));
        }

        Set<String> usedIds = collectUsedIds(nodes, edges);
        int nextVersion = currentVersion;

        for (DiagramOperation op : operations) {
            switch (op.type()) {
                case ADD_ENTITY -> {
                    boolean requiresV2 = applyAddEntity(nodes, op.entity(), usedIds, idSupplier);
                    if (requiresV2) {
                        nextVersion = 2;
                    }
                }
                case DELETE_ENTITY -> applyDeleteEntity(nodes, edges, op.entityName());
                case RENAME_ENTITY -> applyRenameEntity(nodes, op.entityName(), op.newName());
                case UPDATE_CLASSIFIER -> {
                    applyUpdateClassifier(nodes, op.entityName(), op.kind(), op.abstractValue());
                    nextVersion = 2;
                }
                case ADD_ATTRIBUTE -> {
                    boolean requiresV2 = applyAddAttribute(nodes, op.entityName(), op.attribute(), usedIds, idSupplier);
                    if (requiresV2) {
                        nextVersion = 2;
                    }
                }
                case DELETE_ATTRIBUTE -> applyDeleteAttribute(nodes, op.entityName(), op.attributeName());
                case RENAME_ATTRIBUTE -> applyRenameAttribute(nodes, op.entityName(), op.attributeName(), op.newName());
                case CHANGE_ATTRIBUTE_TYPE -> applyChangeAttributeType(
                        nodes, op.entityName(), op.attributeName(), op.dataType());
                case SET_ATTRIBUTE_PRIMARY_KEY -> applySetAttributePrimaryKey(
                        nodes, op.entityName(), op.attributeName(), Boolean.TRUE.equals(op.value()));
                case SET_ATTRIBUTE_NULLABLE -> applySetAttributeNullable(
                        nodes, op.entityName(), op.attributeName(), Boolean.TRUE.equals(op.value()));
                case SET_ATTRIBUTE_VISIBILITY -> {
                    applySetAttributeVisibility(nodes, op.entityName(), op.attributeName(), op.visibility());
                    nextVersion = 2;
                }
                case SET_ATTRIBUTE_STATIC -> {
                    boolean val = op.value() != null ? op.value() : Boolean.TRUE.equals(op.staticValue());
                    applySetAttributeStatic(nodes, op.entityName(), op.attributeName(), val);
                    nextVersion = 2;
                }
                case ADD_METHOD -> {
                    applyAddMethod(nodes, op.entityName(), op.method(), usedIds, idSupplier);
                    nextVersion = 2;
                }
                case UPDATE_METHOD -> {
                    applyUpdateMethod(nodes, op, usedIds, idSupplier);
                    nextVersion = 2;
                }
                case DELETE_METHOD -> {
                    applyDeleteMethod(nodes, op.entityName(), op.methodName(), op.parameterTypes());
                    nextVersion = 2;
                }
                case ADD_METHOD_PARAMETER -> {
                    applyAddMethodParameter(
                            nodes, op.entityName(), op.methodName(), op.parameterTypes(),
                            op.parameter(), usedIds, idSupplier);
                    nextVersion = 2;
                }
                case UPDATE_METHOD_PARAMETER -> {
                    applyUpdateMethodParameter(nodes, op);
                    nextVersion = 2;
                }
                case DELETE_METHOD_PARAMETER -> {
                    applyDeleteMethodParameter(
                            nodes, op.entityName(), op.methodName(), op.parameterTypes(), op.parameterName());
                    nextVersion = 2;
                }
                case ADD_ENUM_LITERAL -> {
                    applyAddEnumLiteral(nodes, op.entityName(), op.literal());
                    nextVersion = 2;
                }
                case RENAME_ENUM_LITERAL -> {
                    String newLit = op.newLiteral() != null ? op.newLiteral() : op.newName();
                    applyRenameEnumLiteral(nodes, op.entityName(), op.literal(), newLit);
                    nextVersion = 2;
                }
                case DELETE_ENUM_LITERAL -> {
                    applyDeleteEnumLiteral(nodes, op.entityName(), op.literal());
                    nextVersion = 2;
                }
                case ADD_RELATIONSHIP -> {
                    boolean requiresV2 = applyAddRelationship(nodes, edges, op.relationship(), usedIds, idSupplier);
                    if (requiresV2) {
                        nextVersion = 2;
                    }
                }
                case DELETE_RELATIONSHIP -> applyDeleteRelationship(nodes, edges, op.relationship());
                case UPDATE_RELATIONSHIP -> {
                    boolean requiresV2 = applyUpdateRelationship(nodes, edges, op.relationship());
                    if (requiresV2) {
                        nextVersion = 2;
                    }
                }
                case CREATE_ASSOCIATION -> {
                    applyCreateAssociation(nodes, edges, op.association(), usedIds, idSupplier);
                    nextVersion = 2;
                }
                case DELETE_ASSOCIATION -> applyDeleteAssociation(nodes, edges, op.associationEntityName());
                case CONVERT_MANY_TO_MANY_ASSOCIATION -> {
                    applyConvertManyToMany(nodes, edges, op.conversion(), usedIds, idSupplier);
                    nextVersion = 2;
                }
            }
        }

        Map<String, Object> resultDoc = new LinkedHashMap<>();
        resultDoc.put("version", nextVersion);
        resultDoc.put("nodes", nodes);
        resultDoc.put("edges", edges);

        if (resultDoc.toString().getBytes(StandardCharsets.UTF_8).length > MAX_DOCUMENT_BYTES) {
            throw new DiagramValidationException("El documento resultante supera el tamaño máximo permitido.");
        }
        DiagramDocumentValidator.validate(resultDoc);

        Map<String, Object> normalizedBaseline = new LinkedHashMap<>();
        normalizedBaseline.put("version", currentVersion);
        normalizedBaseline.put("nodes", cloneObjectList(baseDoc.get("nodes"), "nodes"));
        normalizedBaseline.put("edges", normalizedBaseEdges);

        DiagramThreeWayMerge.TouchedElements diff =
                DiagramThreeWayMerge.diffElements(normalizedBaseline, resultDoc);
        Set<String> lockIds = diff.lockProtectedElementIds();
        boolean changed = !lockIds.isEmpty() || nextVersion != currentVersion;
        List<String> affectedSorted = lockIds.stream().sorted().toList();
        String summary = summarizeOperations(operations);

        return new ApplierResult(nextVersion, resultDoc, changed, affectedSorted, lockIds, summary);
    }

    public static String summarizeOperations(List<DiagramOperation> operations) {
        if (operations == null || operations.isEmpty()) {
            return "";
        }
        List<String> labels = operations.stream().map(ServerDiagramOperationApplier::operationLabel).toList();
        String joined = String.join("; ", labels);
        return joined.length() <= 490 ? joined : joined.substring(0, 487) + "...";
    }

    public static String operationLabel(DiagramOperation op) {
        return switch (op.type()) {
            case ADD_ENTITY -> {
                String kind = op.entity().kind();
                String prefix = "CLASS".equals(kind) ? "Crear clase "
                        : "INTERFACE".equals(kind) ? "Crear interfaz "
                        : "ENUMERATION".equals(kind) ? "Crear enumeración "
                        : "Crear entidad ";
                yield prefix + op.entity().name();
            }
            case DELETE_ENTITY -> "Eliminar elemento " + op.entityName();
            case RENAME_ENTITY -> "Renombrar " + op.entityName() + " como " + op.newName();
            case UPDATE_CLASSIFIER -> "Actualizar clasificador " + op.entityName();
            case ADD_ATTRIBUTE -> "Agregar " + op.attribute().name() + " " + op.attribute().dataType()
                    + " a " + op.entityName();
            case DELETE_ATTRIBUTE -> "Eliminar " + op.attributeName() + " de " + op.entityName();
            case RENAME_ATTRIBUTE -> "Renombrar " + op.attributeName() + " como " + op.newName()
                    + " en " + op.entityName();
            case CHANGE_ATTRIBUTE_TYPE -> "Cambiar " + op.attributeName() + " de " + op.entityName()
                    + " a " + op.dataType();
            case SET_ATTRIBUTE_PRIMARY_KEY -> (Boolean.TRUE.equals(op.value()) ? "Marcar" : "Quitar")
                    + " PK en " + op.entityName() + "." + op.attributeName();
            case SET_ATTRIBUTE_NULLABLE -> (Boolean.TRUE.equals(op.value()) ? "Permitir" : "Impedir")
                    + " null en " + op.entityName() + "." + op.attributeName();
            case SET_ATTRIBUTE_VISIBILITY -> "Cambiar visibilidad de " + op.entityName() + "." + op.attributeName()
                    + " a " + op.visibility();
            case SET_ATTRIBUTE_STATIC -> (Boolean.TRUE.equals(op.value()) ? "Marcar" : "Quitar")
                    + " static en " + op.entityName() + "." + op.attributeName();
            case ADD_METHOD -> "Agregar método " + op.method().name() + " a " + op.entityName();
            case UPDATE_METHOD -> "Actualizar método " + op.methodName() + " en " + op.entityName();
            case DELETE_METHOD -> "Eliminar método " + op.methodName() + " de " + op.entityName();
            case ADD_METHOD_PARAMETER -> "Agregar parámetro " + op.parameter().name()
                    + " a " + op.entityName() + "." + op.methodName();
            case UPDATE_METHOD_PARAMETER -> "Actualizar parámetro " + op.parameterName()
                    + " en " + op.entityName() + "." + op.methodName();
            case DELETE_METHOD_PARAMETER -> "Eliminar parámetro " + op.parameterName()
                    + " de " + op.entityName() + "." + op.methodName();
            case ADD_ENUM_LITERAL -> "Agregar literal " + op.literal() + " a " + op.entityName();
            case RENAME_ENUM_LITERAL -> "Renombrar literal " + op.literal() + " como "
                    + (op.newLiteral() != null ? op.newLiteral() : op.newName()) + " en " + op.entityName();
            case DELETE_ENUM_LITERAL -> "Eliminar literal " + op.literal() + " de " + op.entityName();
            case ADD_RELATIONSHIP -> {
                String rk = op.relationship().kind();
                String label = rk != null && !"ASSOCIATION".equals(rk)
                        ? "Crear relación " + rk + " "
                        : "Crear relación ";
                yield label + op.relationship().sourceEntity() + " - " + op.relationship().targetEntity();
            }
            case DELETE_RELATIONSHIP -> "Eliminar relación " + op.relationship().sourceEntity()
                    + " - " + op.relationship().targetEntity();
            case UPDATE_RELATIONSHIP -> "Actualizar relación " + op.relationship().sourceEntity()
                    + " - " + op.relationship().targetEntity();
            case CREATE_ASSOCIATION -> "Crear asociación " + op.association().associationEntityName()
                    + " entre " + op.association().sourceEntity() + " y " + op.association().targetEntity();
            case DELETE_ASSOCIATION -> "Eliminar asociación " + op.associationEntityName();
            case CONVERT_MANY_TO_MANY_ASSOCIATION -> "Convertir la relación en "
                    + op.conversion().associationEntityName();
        };
    }

    private static boolean applyAddEntity(
            List<Map<String, Object>> nodes,
            EntityDefinition entity,
            Set<String> usedIds,
            Supplier<String> idSupplier) {
        if (nodes.stream().anyMatch(n -> key(nodeName(n)).equals(key(entity.name())))) {
            fail("Ya existe la entidad \"" + entity.name() + "\"");
        }
        String kind = entity.kind();
        if ("ENUMERATION".equals(kind) && !entity.attributes().isEmpty()) {
            fail("La enumeración \"" + entity.name() + "\" debe usar literales, no atributos.");
        }
        if ("INTERFACE".equals(kind) && !entity.attributes().isEmpty()) {
            fail("La interfaz \"" + entity.name() + "\" no admite atributos persistentes.");
        }
        List<Map<String, Object>> attributes = new ArrayList<>();
        for (AttributeDefinition attr : entity.attributes()) {
            Map<String, Object> existing = attributes.stream()
                    .filter(item -> key(stringField(item, "name")).equals(key(attr.name())))
                    .findFirst()
                    .orElse(null);
            if (existing != null) {
                if (!equivalentAttribute(existing, attr)) {
                    fail("El atributo \"" + attr.name() + "\" tiene definiciones incompatibles");
                }
            } else {
                attributes.add(createAttributeMap(attr, usedIds, idSupplier));
            }
        }
        double y = 180.0;
        for (Map<String, Object> n : nodes) {
            double posY = positionCoord(n, "y");
            int attrCount = nodeAttributes(n).size();
            double candidateBottom = posY + (100 + attrCount * 32) + 60;
            if (candidateBottom > y) {
                y = candidateBottom;
            }
        }
        String id = uniqueId("entity", usedIds, idSupplier);
        Map<String, Object> position = new LinkedHashMap<>();
        position.put("x", 180.0);
        position.put("y", y);

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("id", id);
        data.put("name", entity.name());
        data.put("attributes", attributes);

        boolean requiresV2 = false;
        if (kind != null) {
            data.put("kind", kind);
            if (!"ENTITY".equals(kind)) {
                requiresV2 = true;
            }
        }
        if (entity.isAbstract() != null) {
            if ("ENUMERATION".equals(kind)) {
                if (Boolean.TRUE.equals(entity.isAbstract())) {
                    fail("La enumeración \"" + entity.name() + "\" no puede ser abstracta.");
                }
            } else {
                data.put("abstract", entity.isAbstract());
                requiresV2 = true;
            }
        }
        if (entity.methods() != null) {
            if ("ENUMERATION".equals(kind) && !entity.methods().isEmpty()) {
                fail("La enumeración \"" + entity.name() + "\" no admite métodos.");
            }
            List<Map<String, Object>> methods = new ArrayList<>();
            for (MethodDefinition method : entity.methods()) {
                validateMethodAgainstList(methods, method, -1, entity.name());
                methods.add(createMethodMap(method, usedIds, idSupplier));
            }
            data.put("methods", methods);
            if (!methods.isEmpty()) {
                requiresV2 = true;
            }
        }
        if (entity.literals() != null) {
            if (!"ENUMERATION".equals(kind) && !entity.literals().isEmpty()) {
                fail("Solo una enumeración puede declarar literales.");
            }
            List<String> literals = new ArrayList<>();
            Set<String> seenLiterals = new HashSet<>();
            for (String lit : entity.literals()) {
                if (!seenLiterals.add(key(lit))) {
                    fail("La enumeración \"" + entity.name() + "\" contiene literales duplicados.");
                }
                literals.add(lit);
            }
            data.put("literals", literals);
            requiresV2 = true;
        } else if ("ENUMERATION".equals(kind)) {
            data.put("literals", new ArrayList<String>());
            requiresV2 = true;
        }

        Map<String, Object> node = new LinkedHashMap<>();
        node.put("id", id);
        node.put("type", "entity");
        node.put("position", position);
        node.put("data", data);
        nodes.add(node);
        return requiresV2;
    }

    private static void applyDeleteEntity(
            List<Map<String, Object>> nodes,
            List<Map<String, Object>> edges,
            String entityName) {
        Map<String, Object> target = findAnyClassifierNode(nodes, entityName);
        if (nodeData(target).get("association") instanceof Map<?, ?>) {
            deleteAssociationNode(nodes, edges, target);
            return;
        }
        String targetId = stringField(target, "id");
        Set<String> removedNodeIds = new HashSet<>();
        removedNodeIds.add(targetId);
        Set<String> removedEdgeIds = new HashSet<>();

        for (Map<String, Object> candidate : nodes) {
            if (nodeData(candidate).get("association") instanceof Map<?, ?> assoc
                    && assoc.get("endpoints") instanceof List<?> endpoints) {
                boolean referencesTarget = false;
                for (Object epRaw : endpoints) {
                    if (epRaw instanceof Map<?, ?> ep && targetId.equals(ep.get("entityId"))) {
                        referencesTarget = true;
                    }
                }
                if (referencesTarget) {
                    removedNodeIds.add(stringField(candidate, "id"));
                    for (Object epRaw : endpoints) {
                        if (epRaw instanceof Map<?, ?> ep && ep.get("relationshipId") instanceof String relId) {
                            removedEdgeIds.add(relId);
                        }
                    }
                }
            }
        }

        nodes.removeIf(n -> removedNodeIds.contains(stringField(n, "id")));
        edges.removeIf(e -> removedEdgeIds.contains(optionalStringField(e, "id"))
                || removedNodeIds.contains(stringField(e, "source"))
                || removedNodeIds.contains(stringField(e, "target")));
    }

    private static void applyRenameEntity(
            List<Map<String, Object>> nodes,
            String entityName,
            String newName) {
        Map<String, Object> node = findAnyClassifierNode(nodes, entityName);
        String nodeId = stringField(node, "id");
        boolean collision = nodes.stream()
                .anyMatch(candidate -> !nodeId.equals(stringField(candidate, "id"))
                        && key(nodeName(candidate)).equals(key(newName)));
        if (collision) {
            fail("Ya existe la entidad \"" + newName + "\"");
        }
        nodeData(node).put("name", newName);
    }

    private static void applyUpdateClassifier(
            List<Map<String, Object>> nodes,
            String entityName,
            String requestedKind,
            Boolean abstractValue) {
        Map<String, Object> node = findAnyClassifierNode(nodes, entityName);
        String currentKind = nodeKind(node);
        if (requestedKind != null && !requestedKind.equalsIgnoreCase(currentKind)) {
            fail("No se puede cambiar automáticamente el tipo del clasificador \"" + nodeName(node)
                    + "\" de " + currentKind + " a " + requestedKind + " porque tiene implicaciones estructurales.");
        }
        if (abstractValue != null) {
            if ("ENUMERATION".equals(currentKind)) {
                fail("La enumeración \"" + nodeName(node) + "\" no puede ser abstracta.");
            }
            nodeData(node).put("abstract", abstractValue);
        }
    }

    private static boolean applyAddAttribute(
            List<Map<String, Object>> nodes,
            String entityName,
            AttributeDefinition attribute,
            Set<String> usedIds,
            Supplier<String> idSupplier) {
        Map<String, Object> node = findAttributeBearingNode(nodes, entityName);
        verifyNotStructuralAssociationAttributeMutation(node, attribute.name(), false, true);
        List<Map<String, Object>> attributes = nodeAttributes(node);
        Map<String, Object> existing = attributes.stream()
                .filter(a -> key(stringField(a, "name")).equals(key(attribute.name())))
                .findFirst()
                .orElse(null);
        if (existing != null) {
            if (!equivalentAttribute(existing, attribute)) {
                fail("El atributo \"" + attribute.name() + "\" ya existe con otra definición");
            }
            return false;
        }
        attributes.add(createAttributeMap(attribute, usedIds, idSupplier));
        return attribute.visibility() != null || attribute.isStatic() != null;
    }

    private static void applyDeleteAttribute(
            List<Map<String, Object>> nodes,
            String entityName,
            String attributeName) {
        Map<String, Object> node = findAttributeBearingNode(nodes, entityName);
        verifyNotStructuralAssociationAttributeMutation(node, attributeName, true, false);
        FoundAttribute found = findAttribute(nodes, entityName, attributeName);
        String attrId = stringField(found.attribute(), "id");
        nodeAttributes(found.node()).removeIf(item -> attrId.equals(stringField(item, "id")));
    }

    private static void applyRenameAttribute(
            List<Map<String, Object>> nodes,
            String entityName,
            String attributeName,
            String newName) {
        Map<String, Object> node = findAttributeBearingNode(nodes, entityName);
        verifyNotStructuralAssociationAttributeMutation(node, attributeName, false, false);
        verifyNotStructuralAssociationAttributeMutation(node, newName, false, true);
        FoundAttribute found = findAttribute(nodes, entityName, attributeName);
        String attrId = stringField(found.attribute(), "id");
        boolean duplicate = nodeAttributes(found.node()).stream()
                .anyMatch(item -> !attrId.equals(stringField(item, "id"))
                        && key(stringField(item, "name")).equals(key(newName)));
        if (duplicate) {
            fail("La entidad \"" + nodeName(found.node()) + "\" ya contiene el atributo \"" + newName + "\"");
        }
        found.attribute().put("name", newName);
    }

    private static void applyChangeAttributeType(
            List<Map<String, Object>> nodes,
            String entityName,
            String attributeName,
            DiagramDataType dataType) {
        Map<String, Object> node = findAttributeBearingNode(nodes, entityName);
        verifyNotStructuralAssociationAttributeMutation(node, attributeName, false, false);
        FoundAttribute found = findAttribute(nodes, entityName, attributeName);
        found.attribute().put("type", AI_TYPE_MAP.get(dataType));
    }

    private static void applySetAttributePrimaryKey(
            List<Map<String, Object>> nodes,
            String entityName,
            String attributeName,
            boolean value) {
        Map<String, Object> node = findAttributeBearingNode(nodes, entityName);
        verifyNotStructuralAssociationAttributeMutation(node, attributeName, !value, false);
        FoundAttribute found = findAttribute(nodes, entityName, attributeName);
        found.attribute().put("primaryKey", value);
        if (value) {
            found.attribute().put("nullable", false);
        }
    }

    private static void applySetAttributeNullable(
            List<Map<String, Object>> nodes,
            String entityName,
            String attributeName,
            boolean value) {
        Map<String, Object> node = findAttributeBearingNode(nodes, entityName);
        verifyNotStructuralAssociationAttributeMutation(node, attributeName, false, false);
        FoundAttribute found = findAttribute(nodes, entityName, attributeName);
        if (value && Boolean.TRUE.equals(found.attribute().get("primaryKey"))) {
            fail("El atributo PK \"" + stringField(found.attribute(), "name") + "\" no puede aceptar null");
        }
        found.attribute().put("nullable", value);
    }

    private static void applySetAttributeVisibility(
            List<Map<String, Object>> nodes,
            String entityName,
            String attributeName,
            String visibility) {
        Map<String, Object> node = findAttributeBearingNode(nodes, entityName);
        verifyNotStructuralAssociationAttributeMutation(node, attributeName, false, false);
        FoundAttribute found = findAttribute(nodes, entityName, attributeName);
        found.attribute().put("visibility", visibility.toLowerCase(Locale.ROOT));
    }

    private static void applySetAttributeStatic(
            List<Map<String, Object>> nodes,
            String entityName,
            String attributeName,
            boolean isStatic) {
        Map<String, Object> node = findAttributeBearingNode(nodes, entityName);
        verifyNotStructuralAssociationAttributeMutation(node, attributeName, false, false);
        FoundAttribute found = findAttribute(nodes, entityName, attributeName);
        found.attribute().put("static", isStatic);
    }

    private static void applyAddMethod(
            List<Map<String, Object>> nodes,
            String entityName,
            MethodDefinition method,
            Set<String> usedIds,
            Supplier<String> idSupplier) {
        Map<String, Object> node = findMethodBearingNode(nodes, entityName);
        List<Map<String, Object>> methods = nodeMethods(node);
        validateMethodAgainstList(methods, method, -1, nodeName(node));
        methods.add(createMethodMap(method, usedIds, idSupplier));
    }

    private static void applyUpdateMethod(
            List<Map<String, Object>> nodes,
            DiagramOperation op,
            Set<String> usedIds,
            Supplier<String> idSupplier) {
        Map<String, Object> node = findMethodBearingNode(nodes, op.entityName());
        List<Map<String, Object>> methods = nodeMethods(node);
        int idx = findTargetMethodIndex(methods, op.methodName(), op.parameterTypes(), null, nodeName(node));
        Map<String, Object> current = methods.get(idx);

        MethodDefinition replacement;
        if (op.method() != null) {
            replacement = op.method();
        } else {
            String newName = op.newName() != null ? op.newName() : stringField(current, "name");
            String returnType = op.returnType() != null ? op.returnType() : stringField(current, "returnType");
            String visibility = op.visibility() != null ? op.visibility() : stringField(current, "visibility");
            Boolean isStatic = op.staticValue() != null
                    ? op.staticValue()
                    : (current.get("static") instanceof Boolean b ? b : null);
            Boolean isAbstract = op.abstractValue() != null
                    ? op.abstractValue()
                    : (current.get("abstract") instanceof Boolean b ? b : null);
            List<ParameterDefinition> currentParams = extractParametersFromMethodMap(current);
            replacement = new MethodDefinition(newName, returnType, visibility, isStatic, isAbstract, currentParams);
        }

        validateMethodAgainstList(methods, replacement, idx, nodeName(node));
        current.put("name", replacement.name());
        current.put("returnType", replacement.returnType() != null ? replacement.returnType() : "void");
        current.put("visibility", replacement.visibility() != null
                ? replacement.visibility().toLowerCase(Locale.ROOT)
                : "public");
        if (replacement.isStatic() != null) {
            current.put("static", replacement.isStatic());
        } else {
            current.remove("static");
        }
        if (replacement.isAbstract() != null) {
            current.put("abstract", replacement.isAbstract());
        } else {
            current.remove("abstract");
        }
        if (op.method() != null && op.method().parameters() != null) {
            List<Map<String, Object>> newParams = new ArrayList<>();
            for (ParameterDefinition p : op.method().parameters()) {
                newParams.add(createParameterMap(p, usedIds, idSupplier));
            }
            current.put("parameters", newParams);
        }
    }

    private static void applyDeleteMethod(
            List<Map<String, Object>> nodes,
            String entityName,
            String methodName,
            List<String> parameterTypes) {
        Map<String, Object> node = findMethodBearingNode(nodes, entityName);
        List<Map<String, Object>> methods = nodeMethods(node);
        int idx = findTargetMethodIndex(methods, methodName, parameterTypes, null, nodeName(node));
        methods.remove(idx);
    }

    private static void applyAddMethodParameter(
            List<Map<String, Object>> nodes,
            String entityName,
            String methodName,
            List<String> parameterTypes,
            ParameterDefinition parameter,
            Set<String> usedIds,
            Supplier<String> idSupplier) {
        Map<String, Object> node = findMethodBearingNode(nodes, entityName);
        List<Map<String, Object>> methods = nodeMethods(node);
        int idx = findTargetMethodIndex(methods, methodName, parameterTypes, null, nodeName(node));
        Map<String, Object> current = methods.get(idx);
        List<Map<String, Object>> params = methodParameters(current);
        for (Map<String, Object> p : params) {
            if (key(stringField(p, "name")).equals(key(parameter.name()))) {
                fail("El método \"" + stringField(current, "name")
                        + "\" ya tiene un parámetro llamado \"" + parameter.name() + "\"");
            }
        }
        List<ParameterDefinition> candidateParams = new ArrayList<>(extractParametersFromMethodMap(current));
        candidateParams.add(parameter);
        MethodDefinition candidate = new MethodDefinition(
                stringField(current, "name"),
                stringField(current, "returnType"),
                stringField(current, "visibility"),
                current.get("static") instanceof Boolean b ? b : null,
                current.get("abstract") instanceof Boolean b ? b : null,
                candidateParams);
        validateMethodAgainstList(methods, candidate, idx, nodeName(node));
        params.add(createParameterMap(parameter, usedIds, idSupplier));
    }

    private static void applyUpdateMethodParameter(
            List<Map<String, Object>> nodes,
            DiagramOperation op) {
        Map<String, Object> node = findMethodBearingNode(nodes, op.entityName());
        List<Map<String, Object>> methods = nodeMethods(node);
        int idx = findTargetMethodIndex(
                methods, op.methodName(), op.parameterTypes(), op.parameterName(), nodeName(node));
        Map<String, Object> current = methods.get(idx);
        List<Map<String, Object>> params = methodParameters(current);
        int paramIdx = -1;
        for (int i = 0; i < params.size(); i++) {
            if (key(stringField(params.get(i), "name")).equals(key(op.parameterName()))) {
                paramIdx = i;
                break;
            }
        }
        if (paramIdx < 0) {
            fail("El parámetro \"" + op.parameterName() + "\" no existe en el método \""
                    + stringField(current, "name") + "\"");
        }
        Map<String, Object> targetParam = params.get(paramIdx);
        String nextName = op.parameter() != null
                ? op.parameter().name()
                : (op.newName() != null ? op.newName() : stringField(targetParam, "name"));
        String nextType = op.parameter() != null && op.parameter().type() != null
                ? op.parameter().type()
                : (op.newType() != null ? op.newType() : stringField(targetParam, "type"));
        for (int i = 0; i < params.size(); i++) {
            if (i != paramIdx && key(stringField(params.get(i), "name")).equals(key(nextName))) {
                fail("El método \"" + stringField(current, "name")
                        + "\" ya contiene un parámetro llamado \"" + nextName + "\"");
            }
        }
        List<ParameterDefinition> candidateParams = new ArrayList<>(extractParametersFromMethodMap(current));
        candidateParams.set(paramIdx, new ParameterDefinition(nextName, nextType));
        MethodDefinition candidate = new MethodDefinition(
                stringField(current, "name"),
                stringField(current, "returnType"),
                stringField(current, "visibility"),
                current.get("static") instanceof Boolean b ? b : null,
                current.get("abstract") instanceof Boolean b ? b : null,
                candidateParams);
        validateMethodAgainstList(methods, candidate, idx, nodeName(node));
        targetParam.put("name", nextName);
        targetParam.put("type", nextType);
    }

    private static void applyDeleteMethodParameter(
            List<Map<String, Object>> nodes,
            String entityName,
            String methodName,
            List<String> parameterTypes,
            String parameterName) {
        Map<String, Object> node = findMethodBearingNode(nodes, entityName);
        List<Map<String, Object>> methods = nodeMethods(node);
        int idx = findTargetMethodIndex(methods, methodName, parameterTypes, parameterName, nodeName(node));
        Map<String, Object> current = methods.get(idx);
        List<Map<String, Object>> params = methodParameters(current);
        int paramIdx = -1;
        for (int i = 0; i < params.size(); i++) {
            if (key(stringField(params.get(i), "name")).equals(key(parameterName))) {
                paramIdx = i;
                break;
            }
        }
        if (paramIdx < 0) {
            fail("El parámetro \"" + parameterName + "\" no existe en el método \""
                    + stringField(current, "name") + "\"");
        }
        List<ParameterDefinition> candidateParams = new ArrayList<>(extractParametersFromMethodMap(current));
        candidateParams.remove(paramIdx);
        MethodDefinition candidate = new MethodDefinition(
                stringField(current, "name"),
                stringField(current, "returnType"),
                stringField(current, "visibility"),
                current.get("static") instanceof Boolean b ? b : null,
                current.get("abstract") instanceof Boolean b ? b : null,
                candidateParams);
        validateMethodAgainstList(methods, candidate, idx, nodeName(node));
        params.remove(paramIdx);
    }

    private static void applyAddEnumLiteral(
            List<Map<String, Object>> nodes,
            String entityName,
            String literal) {
        Map<String, Object> node = findEnumerationNode(nodes, entityName);
        List<String> literals = nodeLiterals(node);
        if (literals.stream().anyMatch(existing -> key(existing).equals(key(literal)))) {
            fail("La enumeración \"" + nodeName(node) + "\" ya contiene el literal \"" + literal + "\"");
        }
        literals.add(literal);
    }

    private static void applyRenameEnumLiteral(
            List<Map<String, Object>> nodes,
            String entityName,
            String literal,
            String newLiteral) {
        Map<String, Object> node = findEnumerationNode(nodes, entityName);
        List<String> literals = nodeLiterals(node);
        int idx = -1;
        for (int i = 0; i < literals.size(); i++) {
            if (key(literals.get(i)).equals(key(literal))) {
                idx = i;
                break;
            }
        }
        if (idx < 0) {
            fail("El literal \"" + literal + "\" no existe en la enumeración \"" + nodeName(node) + "\"");
        }
        for (int i = 0; i < literals.size(); i++) {
            if (i != idx && key(literals.get(i)).equals(key(newLiteral))) {
                fail("La enumeración \"" + nodeName(node) + "\" ya contiene el literal \"" + newLiteral + "\"");
            }
        }
        literals.set(idx, newLiteral);
    }

    private static void applyDeleteEnumLiteral(
            List<Map<String, Object>> nodes,
            String entityName,
            String literal) {
        Map<String, Object> node = findEnumerationNode(nodes, entityName);
        List<String> literals = nodeLiterals(node);
        boolean removed = literals.removeIf(existing -> key(existing).equals(key(literal)));
        if (!removed) {
            fail("El literal \"" + literal + "\" no existe en la enumeración \"" + nodeName(node) + "\"");
        }
    }

    private static boolean applyAddRelationship(
            List<Map<String, Object>> nodes,
            List<Map<String, Object>> edges,
            RelationshipDefinition rel,
            Set<String> usedIds,
            Supplier<String> idSupplier) {
        String relKind = rel.kind() == null ? "ASSOCIATION" : rel.kind();
        boolean nonMultiplicative = NON_MULTIPLICATIVE_RELATION_KINDS.contains(relKind);
        Map<String, Object> source = nonMultiplicative
                ? findAnyClassifierNode(nodes, rel.sourceEntity())
                : findRelationEndpointNode(nodes, rel.sourceEntity());
        Map<String, Object> target = nonMultiplicative
                ? findAnyClassifierNode(nodes, rel.targetEntity())
                : findRelationEndpointNode(nodes, rel.targetEntity());
        String sourceId = stringField(source, "id");
        String targetId = stringField(target, "id");
        if (sourceId.equals(targetId)) {
            fail("Las autorrelaciones nuevas no están habilitadas en este MVP");
        }

        if (nonMultiplicative) {
            String srcKind = nodeKind(source);
            String tgtKind = nodeKind(target);
            if ("REALIZATION".equals(relKind) && (!isClassLike(srcKind) || !"INTERFACE".equals(tgtKind))) {
                fail("Una realización debe partir de una clase o entidad y terminar en una interfaz.");
            }
            if ("GENERALIZATION".equals(relKind)
                    && !((isClassLike(srcKind) && isClassLike(tgtKind))
                    || ("INTERFACE".equals(srcKind) && "INTERFACE".equals(tgtKind)))) {
                fail("Una generalización debe conectar clases entre sí o interfaces entre sí.");
            }
            if (rel.sourceMultiplicity() != null || rel.targetMultiplicity() != null) {
                fail("La relación " + relKind + " no admite multiplicidades.");
            }
            for (Map<String, Object> existing : edges) {
                if (sourceId.equals(stringField(existing, "source"))
                        && targetId.equals(stringField(existing, "target"))
                        && relKind.equals(edgeRelationKind(existing))) {
                    return true;
                }
            }
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("relationKind", relKind);
            data.put("sourceCardinality", "ONE_ONE");
            data.put("targetCardinality", "ONE_ONE");
            if (rel.name() != null) {
                data.put("name", rel.name());
            }
            Map<String, Object> edge = new LinkedHashMap<>();
            edge.put("id", uniqueId("edge", usedIds, idSupplier));
            edge.put("type", "relationship");
            edge.put("source", sourceId);
            edge.put("target", targetId);
            edge.put("data", data);
            edges.add(edge);
            return true;
        }

        if (rel.sourceCardinality() == null || rel.targetCardinality() == null) {
            fail("La relación debe declarar ambas cardinalidades.");
        }
        Map<String, Object> data = new LinkedHashMap<>();
        if (rel.kind() != null) {
            data.put("relationKind", relKind);
        }
        data.put("sourceCardinality", rel.sourceCardinality().name());
        data.put("targetCardinality", rel.targetCardinality().name());
        if (rel.name() != null) {
            data.put("name", rel.name());
        }
        if (rel.joinTableName() != null) {
            data.put("joinTableName", rel.joinTableName());
        }
        if (rel.sourceMultiplicity() != null) {
            data.put("sourceMultiplicity", rel.sourceMultiplicity());
        }
        if (rel.targetMultiplicity() != null) {
            data.put("targetMultiplicity", rel.targetMultiplicity());
        }
        if (rel.aggregationEnd() != null) {
            if (!"AGGREGATION".equals(relKind) && !"COMPOSITION".equals(relKind)) {
                fail("El extremo de agregación no coincide con el tipo de relación.");
            }
            data.put("aggregationEnd", rel.aggregationEnd());
        }
        Map<String, Object> candidate = new LinkedHashMap<>();
        candidate.put("id", "");
        candidate.put("type", "relationship");
        candidate.put("source", sourceId);
        candidate.put("target", targetId);
        candidate.put("data", data);

        for (Map<String, Object> existing : edges) {
            if (isMultiplicativeRelationshipEdge(existing)
                    && edgeRelationKind(existing).equals(relKind)
                    && equivalentRelationship(existing, candidate)) {
                return rel.kind() != null && !"ASSOCIATION".equals(relKind);
            }
        }
        candidate.put("id", uniqueId("edge", usedIds, idSupplier));
        edges.add(candidate);
        return (rel.kind() != null && !"ASSOCIATION".equals(relKind))
                || rel.sourceMultiplicity() != null
                || rel.targetMultiplicity() != null
                || rel.aggregationEnd() != null;
    }

    private static void applyDeleteRelationship(
            List<Map<String, Object>> nodes,
            List<Map<String, Object>> edges,
            RelationshipDefinition reference) {
        verifyNotStructuralAssociationEdgeDeletion(nodes, reference.sourceEntity(), reference.targetEntity());
        FoundRelationship found = findRelationship(nodes, edges, reference);
        edges.remove(found.edge());
    }

    private static boolean applyUpdateRelationship(
            List<Map<String, Object>> nodes,
            List<Map<String, Object>> edges,
            RelationshipDefinition update) {
        Map<String, Object> assocNode = findAssociationNodeByEndpoints(
                nodes, update.sourceEntity(), update.targetEntity());
        if (assocNode != null && hasNoStandaloneEdgeBetween(nodes, edges, update.sourceEntity(), update.targetEntity())) {
            updateAssociationClassMultiplicities(nodes, edges, assocNode, update);
            return true;
        }
        FoundRelationship found = findRelationship(nodes, edges, update);
        boolean direct = stringField(found.edge(), "source").equals(stringField(found.source(), "id"));
        Map<String, Object> data = edgeData(found.edge());
        data.remove("relationshipType");
        String targetKind = update.kind() != null ? update.kind() : edgeRelationKind(found.edge());
        boolean requiresV2 = update.kind() != null && !"ASSOCIATION".equals(update.kind());
        if (update.kind() != null) {
            data.put("relationKind", update.kind());
        }
        if (NON_MULTIPLICATIVE_RELATION_KINDS.contains(targetKind)) {
            data.put("sourceCardinality", "ONE_ONE");
            data.put("targetCardinality", "ONE_ONE");
            data.remove("sourceMultiplicity");
            data.remove("targetMultiplicity");
            data.remove("aggregationEnd");
            requiresV2 = true;
        } else {
            if (update.sourceCardinality() != null && update.targetCardinality() != null) {
                data.put("sourceCardinality",
                        (direct ? update.sourceCardinality() : update.targetCardinality()).name());
                data.put("targetCardinality",
                        (direct ? update.targetCardinality() : update.sourceCardinality()).name());
            }
            if (update.sourceMultiplicity() != null) {
                data.put(direct ? "sourceMultiplicity" : "targetMultiplicity", update.sourceMultiplicity());
                requiresV2 = true;
            }
            if (update.targetMultiplicity() != null) {
                data.put(direct ? "targetMultiplicity" : "sourceMultiplicity", update.targetMultiplicity());
                requiresV2 = true;
            }
            if (update.aggregationEnd() != null) {
                data.put("aggregationEnd", update.aggregationEnd());
                requiresV2 = true;
            }
        }
        found.edge().put("type", "relationship");
        return requiresV2;
    }

    private static void applyCreateAssociation(
            List<Map<String, Object>> nodes,
            List<Map<String, Object>> edges,
            AssociationCreationDefinition association,
            Set<String> usedIds,
            Supplier<String> idSupplier) {
        Map<String, Object> source = findRelationEndpointNode(nodes, association.sourceEntity());
        Map<String, Object> target = findRelationEndpointNode(nodes, association.targetEntity());
        String sourceId = stringField(source, "id");
        String targetId = stringField(target, "id");
        if (sourceId.equals(targetId)) {
            fail("Las autorrelaciones nuevas no están habilitadas en este MVP");
        }
        Set<String> structural = structuralRelationshipIds(nodes);
        List<Map<String, Object>> nmCandidates = new ArrayList<>();
        for (Map<String, Object> edge : edges) {
            String edgeId = optionalStringField(edge, "id");
            if (edgeId != null && structural.contains(edgeId)) {
                continue;
            }
            if (!isAssociationEdge(edge)) {
                continue;
            }
            String eSrc = stringField(edge, "source");
            String eTgt = stringField(edge, "target");
            boolean samePair = (eSrc.equals(sourceId) && eTgt.equals(targetId))
                    || (eSrc.equals(targetId) && eTgt.equals(sourceId));
            if (!samePair) {
                continue;
            }
            String[] cards = edgeCardinalities(edge);
            if (isMany(cards[0]) && isMany(cards[1])) {
                nmCandidates.add(edge);
            }
        }
        if (nmCandidates.size() > 1) {
            fail("Encontré más de una relación N:M entre " + nodeName(source) + " y " + nodeName(target) + ".");
        }
        String relationshipId;
        if (nmCandidates.isEmpty()) {
            relationshipId = uniqueId("edge", usedIds, idSupplier);
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("sourceCardinality", DiagramCardinality.ZERO_MANY.name());
            data.put("targetCardinality", DiagramCardinality.ZERO_MANY.name());
            Map<String, Object> synthEdge = new LinkedHashMap<>();
            synthEdge.put("id", relationshipId);
            synthEdge.put("type", "relationship");
            synthEdge.put("source", sourceId);
            synthEdge.put("target", targetId);
            synthEdge.put("data", data);
            edges.add(synthEdge);
        } else {
            Map<String, Object> nm = nmCandidates.getFirst();
            relationshipId = optionalStringField(nm, "id");
            if (relationshipId == null) {
                relationshipId = uniqueId("edge", usedIds, idSupplier);
                nm.put("id", relationshipId);
            }
        }
        convertManyToManyInPlace(
                nodes,
                edges,
                relationshipId,
                association.associationEntityName(),
                association.attributes(),
                usedIds,
                idSupplier);
    }

    private static void applyDeleteAssociation(
            List<Map<String, Object>> nodes,
            List<Map<String, Object>> edges,
            String associationEntityName) {
        Map<String, Object> association = findEditableEntityNode(nodes, associationEntityName);
        deleteAssociationNode(nodes, edges, association);
    }

    private static void applyConvertManyToMany(
            List<Map<String, Object>> nodes,
            List<Map<String, Object>> edges,
            AssociationConversionDefinition conversion,
            Set<String> usedIds,
            Supplier<String> idSupplier) {
        Map<String, Object> original = edges.stream()
                .filter(e -> conversion.relationshipId().equals(optionalStringField(e, "id")))
                .findFirst()
                .orElse(null);
        if (original == null) {
            fail("La relación N:M indicada no existe o ya fue convertida");
        }
        Map<String, Object> source = findRelationEndpointNode(nodes, conversion.sourceEntity());
        Map<String, Object> target = findRelationEndpointNode(nodes, conversion.targetEntity());
        String origSrc = stringField(original, "source");
        String origTgt = stringField(original, "target");
        Set<String> endpoints = Set.of(origSrc, origTgt);
        if (origSrc.equals(origTgt)
                || !endpoints.contains(stringField(source, "id"))
                || !endpoints.contains(stringField(target, "id"))) {
            fail("Los extremos de la conversión no coinciden con la relación indicada");
        }
        Set<String> attrNames = new HashSet<>();
        for (AttributeDefinition attr : conversion.attributes()) {
            if (!attrNames.add(key(attr.name()))) {
                fail("El atributo \"" + attr.name() + "\" está duplicado");
            }
        }
        convertManyToManyInPlace(
                nodes,
                edges,
                conversion.relationshipId(),
                conversion.associationEntityName(),
                conversion.attributes(),
                usedIds,
                idSupplier);
    }

    private static void convertManyToManyInPlace(
            List<Map<String, Object>> nodes,
            List<Map<String, Object>> edges,
            String relationshipId,
            String requestedName,
            List<AttributeDefinition> ownAttributes,
            Set<String> usedIds,
            Supplier<String> idSupplier) {
        String entityName = requestedName == null ? "" : requestedName.strip();
        if (entityName.isEmpty() || entityName.length() > 100) {
            fail("Indica un nombre válido para la entidad asociativa.");
        }
        Map<String, Object> original = edges.stream()
                .filter(e -> relationshipId.equals(optionalStringField(e, "id")))
                .findFirst()
                .orElse(null);
        if (original == null) {
            fail("La relación N:M seleccionada ya no existe.");
        }
        String[] cards = edgeCardinalities(original);
        if (!isMany(cards[0]) || !isMany(cards[1])) {
            fail("La relación seleccionada no es N:M.");
        }
        String origSrcId = stringField(original, "source");
        String origTgtId = stringField(original, "target");
        Map<String, Object> source = nodes.stream()
                .filter(n -> origSrcId.equals(stringField(n, "id")))
                .findFirst()
                .orElse(null);
        Map<String, Object> target = nodes.stream()
                .filter(n -> origTgtId.equals(stringField(n, "id")))
                .findFirst()
                .orElse(null);
        if (source == null || target == null || origSrcId.equals(origTgtId)) {
            fail("La relación N:M tiene extremos inválidos.");
        }
        String entityTech = technicalName(entityName);
        if (entityTech.isEmpty() || nodes.stream()
                .anyMatch(n -> key(technicalName(nodeName(n))).equals(key(entityTech)))) {
            fail("Ya existe una entidad con el nombre '" + entityName + "'.");
        }
        String joinTableName = deriveJoinTableName(original, nodeName(source), nodeName(target));
        if (joinTableName.isEmpty() || joinTableName.length() > 55) {
            fail("El nombre físico de la tabla intermedia no es válido.");
        }
        String fkSource = sqlName(technicalName(nodeName(source)) + "Id");
        String fkTarget = sqlName(technicalName(nodeName(target)) + "Id");

        Set<String> normalizedAttrNames = new HashSet<>();
        normalizedAttrNames.add("id");
        for (AttributeDefinition attr : ownAttributes) {
            if (attr.primaryKey()) {
                fail("Los atributos propios no pueden reemplazar la PK generada");
            }
            String attrTech = key(technicalName(attr.name()));
            if (attrTech.isEmpty() || !normalizedAttrNames.add(attrTech)) {
                fail("El atributo propio '" + attr.name() + "' no tiene un nombre único válido.");
            }
        }

        String associationId = uniqueId("entity", usedIds, idSupplier);
        String pkAttrId = uniqueId("attribute", usedIds, idSupplier);
        String sourceRelId = uniqueId("relationship", usedIds, idSupplier);
        String targetRelId = uniqueId("relationship", usedIds, idSupplier);

        List<Map<String, Object>> attributes = new ArrayList<>();
        Map<String, Object> pkAttr = new LinkedHashMap<>();
        pkAttr.put("id", pkAttrId);
        pkAttr.put("name", "id");
        pkAttr.put("type", "INTEGER");
        pkAttr.put("primaryKey", true);
        pkAttr.put("nullable", false);
        attributes.add(pkAttr);

        for (AttributeDefinition own : ownAttributes) {
            Map<String, Object> ownMap = createAttributeMap(own, usedIds, idSupplier);
            ownMap.put("primaryKey", false);
            attributes.add(ownMap);
        }

        Map<String, Object> epSource = new LinkedHashMap<>();
        epSource.put("role", "SOURCE");
        epSource.put("entityId", origSrcId);
        epSource.put("relationshipId", sourceRelId);
        epSource.put("foreignKeyName", fkSource);

        Map<String, Object> epTarget = new LinkedHashMap<>();
        epTarget.put("role", "TARGET");
        epTarget.put("entityId", origTgtId);
        epTarget.put("relationshipId", targetRelId);
        epTarget.put("foreignKeyName", fkTarget);

        Map<String, Object> associationMeta = new LinkedHashMap<>();
        associationMeta.put("kind", "MANY_TO_MANY_ASSOCIATION");
        associationMeta.put("tableName", joinTableName);
        associationMeta.put("uniquePair", true);
        associationMeta.put("sourceMultiplicity", cards[0]);
        associationMeta.put("targetMultiplicity", cards[1]);
        associationMeta.put("endpoints", List.of(epSource, epTarget));

        Map<String, Object> assocData = new LinkedHashMap<>();
        assocData.put("id", associationId);
        assocData.put("name", entityName);
        assocData.put("attributes", attributes);
        assocData.put("kind", "ASSOCIATION_CLASS");
        assocData.put("methods", new ArrayList<>());
        assocData.put("association", associationMeta);

        Map<String, Object> assocPos = new LinkedHashMap<>();
        assocPos.put("x", (positionCoord(source, "x") + positionCoord(target, "x")) / 2.0);
        assocPos.put("y", (positionCoord(source, "y") + positionCoord(target, "y")) / 2.0 + 140.0);

        Map<String, Object> assocNode = new LinkedHashMap<>();
        assocNode.put("id", associationId);
        assocNode.put("type", "entity");
        assocNode.put("position", assocPos);
        assocNode.put("data", assocData);

        Map<String, Object> structEdge1 = createNormalizedEdge(sourceRelId, origSrcId, associationId, "ONE_ONE", cards[1]);
        Map<String, Object> structEdge2 = createNormalizedEdge(targetRelId, origTgtId, associationId, "ONE_ONE", cards[0]);

        edges.removeIf(e -> relationshipId.equals(optionalStringField(e, "id")));
        nodes.add(assocNode);
        edges.add(structEdge1);
        edges.add(structEdge2);
    }

    private static void deleteAssociationNode(
            List<Map<String, Object>> nodes,
            List<Map<String, Object>> edges,
            Map<String, Object> associationNode) {
        Object assocRaw = nodeData(associationNode).get("association");
        if (!(assocRaw instanceof Map<?, ?> assoc) || !(assoc.get("endpoints") instanceof List<?> endpoints)) {
            fail("La entidad \"" + nodeName(associationNode) + "\" no es asociativa");
            return;
        }
        String assocId = stringField(associationNode, "id");
        Set<String> removedEdges = new HashSet<>();
        for (Object epRaw : endpoints) {
            if (epRaw instanceof Map<?, ?> ep && ep.get("relationshipId") instanceof String relId) {
                removedEdges.add(relId);
            }
        }
        nodes.removeIf(n -> assocId.equals(stringField(n, "id")));
        edges.removeIf(e -> removedEdges.contains(optionalStringField(e, "id"))
                || assocId.equals(stringField(e, "source"))
                || assocId.equals(stringField(e, "target")));
    }

    private static Map<String, Object> createNormalizedEdge(
            String id,
            String source,
            String target,
            String sourceCardinality,
            String targetCardinality) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("sourceCardinality", sourceCardinality);
        data.put("targetCardinality", targetCardinality);
        Map<String, Object> edge = new LinkedHashMap<>();
        edge.put("id", id);
        edge.put("type", "relationship");
        edge.put("source", source);
        edge.put("target", target);
        edge.put("data", data);
        return edge;
    }

    private static FoundRelationship findRelationship(
            List<Map<String, Object>> nodes,
            List<Map<String, Object>> edges,
            RelationshipDefinition ref) {
        String requestedKind = ref.kind();
        boolean nonMultRequested = requestedKind != null && NON_MULTIPLICATIVE_RELATION_KINDS.contains(requestedKind);
        Map<String, Object> source = nonMultRequested || requestedKind == null
                ? findAnyClassifierNode(nodes, ref.sourceEntity())
                : findRelationEndpointNode(nodes, ref.sourceEntity());
        Map<String, Object> target = nonMultRequested || requestedKind == null
                ? findAnyClassifierNode(nodes, ref.targetEntity())
                : findRelationEndpointNode(nodes, ref.targetEntity());
        String sourceId = stringField(source, "id");
        String targetId = stringField(target, "id");
        Set<String> structural = structuralRelationshipIds(nodes);
        String expectedName = ref.name() == null ? null : key(ref.name());

        List<Map<String, Object>> matches = new ArrayList<>();
        for (Map<String, Object> edge : edges) {
            String edgeId = optionalStringField(edge, "id");
            if (edgeId != null && structural.contains(edgeId)) {
                continue;
            }
            if (ref.id() != null && !ref.id().equals(edgeId)) {
                continue;
            }
            String actualKind = edgeRelationKind(edge);
            if (requestedKind != null && !requestedKind.equalsIgnoreCase(actualKind)) {
                continue;
            }
            if (requestedKind == null && !isMultiplicativeRelationshipEdge(edge)) {
                // Only match non-multiplicative when no multiplicative edge exists or when kind was omitted
                // We check multiplicative edges first below.
            }
            String eSrc = stringField(edge, "source");
            String eTgt = stringField(edge, "target");
            boolean samePair = (eSrc.equals(sourceId) && eTgt.equals(targetId))
                    || (eSrc.equals(targetId) && eTgt.equals(sourceId));
            if (!samePair) {
                continue;
            }
            if (expectedName != null) {
                String actualName = optionalStringField(edgeData(edge), "name");
                if (!expectedName.equals(key(actualName == null ? "" : actualName))) {
                    continue;
                }
            }
            matches.add(edge);
        }
        if (requestedKind == null && matches.size() > 1) {
            List<Map<String, Object>> multOnly = matches.stream()
                    .filter(ServerDiagramOperationApplier::isMultiplicativeRelationshipEdge)
                    .toList();
            if (multOnly.size() == 1) {
                matches = multOnly;
            }
        }
        if (matches.isEmpty()) {
            fail("No encontré la relación " + nodeName(source) + "-" + nodeName(target) + ".");
        }
        if (matches.size() > 1) {
            fail("Encontré más de una relación entre " + nodeName(source) + " y " + nodeName(target)
                    + ". Especifica cuál deseas modificar.");
        }
        return new FoundRelationship(matches.getFirst(), source, target);
    }

    private static FoundAttribute findAttribute(
            List<Map<String, Object>> nodes,
            String entityName,
            String attributeName) {
        Map<String, Object> node = findAttributeBearingNode(nodes, entityName);
        List<Map<String, Object>> matches = nodeAttributes(node).stream()
                .filter(item -> key(stringField(item, "name")).equals(key(attributeName)))
                .toList();
        if (matches.size() != 1) {
            fail("El atributo \"" + attributeName + "\" no existe o su nombre es ambiguo en \""
                    + nodeName(node) + "\"");
        }
        return new FoundAttribute(node, matches.getFirst());
    }

    private static Map<String, Object> findAnyClassifierNode(List<Map<String, Object>> nodes, String entityName) {
        List<Map<String, Object>> matches = nodes.stream()
                .filter(n -> key(nodeName(n)).equals(key(entityName)))
                .toList();
        if (matches.size() != 1) {
            fail("El elemento UML \"" + entityName + "\" no existe o su nombre es ambiguo");
        }
        return matches.getFirst();
    }

    private static Map<String, Object> findEditableEntityNode(List<Map<String, Object>> nodes, String entityName) {
        Map<String, Object> node = findAnyClassifierNode(nodes, entityName);
        String kind = nodeKind(node);
        if ("INTERFACE".equals(kind) || "ENUMERATION".equals(kind)) {
            fail("El elemento UML \"" + nodeName(node) + "\" es de tipo " + kind
                    + " y no admite operaciones IA de entidad");
        }
        return node;
    }

    private static Map<String, Object> findAttributeBearingNode(List<Map<String, Object>> nodes, String entityName) {
        Map<String, Object> node = findEditableEntityNode(nodes, entityName);
        if ("ENUMERATION".equals(nodeKind(node))) {
            fail("La enumeración \"" + nodeName(node) + "\" usa literales, no atributos.");
        }
        return node;
    }

    private static Map<String, Object> findMethodBearingNode(List<Map<String, Object>> nodes, String entityName) {
        Map<String, Object> node = findAnyClassifierNode(nodes, entityName);
        if ("ENUMERATION".equals(nodeKind(node))) {
            fail("La enumeración \"" + nodeName(node) + "\" usa literales y no admite métodos.");
        }
        return node;
    }

    private static Map<String, Object> findEnumerationNode(List<Map<String, Object>> nodes, String entityName) {
        Map<String, Object> node = findAnyClassifierNode(nodes, entityName);
        if (!"ENUMERATION".equals(nodeKind(node))) {
            fail("Solo una enumeración admite literales; \"" + nodeName(node) + "\" es de tipo " + nodeKind(node));
        }
        return node;
    }

    private static Map<String, Object> findRelationEndpointNode(List<Map<String, Object>> nodes, String entityName) {
        return findEditableEntityNode(nodes, entityName);
    }

    private static String nodeKind(Map<String, Object> node) {
        Object kind = nodeData(node).get("kind");
        return kind instanceof String text && !text.isBlank() ? text : "ENTITY";
    }

    private static boolean isClassLike(String kind) {
        return "ENTITY".equals(kind) || "CLASS".equals(kind) || "ASSOCIATION_CLASS".equals(kind);
    }

    private static String edgeRelationKind(Map<String, Object> edge) {
        Object relKind = edgeData(edge).get("relationKind");
        return relKind instanceof String text && !text.isBlank() ? text : "ASSOCIATION";
    }

    private static boolean isAssociationEdge(Map<String, Object> edge) {
        Object relKind = edgeData(edge).get("relationKind");
        return relKind == null || "ASSOCIATION".equals(relKind);
    }

    private static boolean isMultiplicativeRelationshipEdge(Map<String, Object> edge) {
        Object relKind = edgeData(edge).get("relationKind");
        return relKind == null || MULTIPLICATIVE_RELATION_KINDS.contains(String.valueOf(relKind));
    }

    private static Map<String, Object> createAttributeMap(
            AttributeDefinition a,
            Set<String> usedIds,
            Supplier<String> idSupplier) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", uniqueId("attribute", usedIds, idSupplier));
        map.put("name", a.name());
        map.put("type", AI_TYPE_MAP.get(a.dataType()));
        map.put("primaryKey", a.primaryKey());
        map.put("nullable", a.nullable());
        if (a.visibility() != null) {
            map.put("visibility", a.visibility().toLowerCase(Locale.ROOT));
        }
        if (a.isStatic() != null) {
            map.put("static", a.isStatic());
        }
        return map;
    }

    private static Map<String, Object> createMethodMap(
            MethodDefinition m,
            Set<String> usedIds,
            Supplier<String> idSupplier) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", uniqueId("method", usedIds, idSupplier));
        map.put("name", m.name());
        map.put("visibility", m.visibility() != null ? m.visibility().toLowerCase(Locale.ROOT) : "public");
        map.put("returnType", m.returnType() != null ? m.returnType() : "void");
        if (m.isStatic() != null) {
            map.put("static", m.isStatic());
        }
        if (m.isAbstract() != null) {
            map.put("abstract", m.isAbstract());
        }
        List<Map<String, Object>> parameters = new ArrayList<>();
        if (m.parameters() != null) {
            for (ParameterDefinition p : m.parameters()) {
                parameters.add(createParameterMap(p, usedIds, idSupplier));
            }
        }
        map.put("parameters", parameters);
        return map;
    }

    private static Map<String, Object> createParameterMap(
            ParameterDefinition p,
            Set<String> usedIds,
            Supplier<String> idSupplier) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", uniqueId("parameter", usedIds, idSupplier));
        map.put("name", p.name());
        map.put("type", p.type() != null ? p.type() : "String");
        return map;
    }

    private static void validateMethodAgainstList(
            List<Map<String, Object>> existingMethods,
            MethodDefinition candidate,
            int ignoreIndex,
            String classifierName) {
        if (Boolean.TRUE.equals(candidate.isStatic()) && Boolean.TRUE.equals(candidate.isAbstract())) {
            fail("El método " + classifierName + "." + candidate.name() + " no puede ser static y abstract.");
        }
        List<ParameterDefinition> params = candidate.parameters() == null ? List.of() : candidate.parameters();
        Set<String> seenParamNames = new HashSet<>();
        for (ParameterDefinition p : params) {
            if (!seenParamNames.add(key(p.name()))) {
                fail("El método " + classifierName + "." + candidate.name()
                        + " contiene parámetros duplicados: " + p.name());
            }
        }
        List<String> candidateSig = params.stream()
                .map(p -> key(p.type() != null ? p.type() : "String"))
                .toList();
        for (int i = 0; i < existingMethods.size(); i++) {
            if (i == ignoreIndex) continue;
            Map<String, Object> existing = existingMethods.get(i);
            if (key(stringField(existing, "name")).equals(key(candidate.name()))
                    && methodMapSignatureTypes(existing).equals(candidateSig)) {
                fail("El clasificador \"" + classifierName + "\" ya contiene el método \""
                        + candidate.name() + "\" con la misma firma de parámetros.");
            }
        }
    }

    private static int findTargetMethodIndex(
            List<Map<String, Object>> methods,
            String methodName,
            List<String> parameterTypes,
            String targetParameterName,
            String classifierName) {
        List<Integer> matches = new ArrayList<>();
        for (int i = 0; i < methods.size(); i++) {
            if (key(stringField(methods.get(i), "name")).equals(key(methodName))) {
                matches.add(i);
            }
        }
        if (matches.isEmpty()) {
            fail("El método \"" + methodName + "\" no existe en \"" + classifierName + "\".");
        }
        if (parameterTypes != null) {
            List<String> expectedSig = parameterTypes.stream().map(ServerDiagramOperationApplier::key).toList();
            for (int idx : matches) {
                if (methodMapSignatureTypes(methods.get(idx)).equals(expectedSig)) {
                    return idx;
                }
            }
            fail("No existe una sobrecarga de \"" + methodName + "\" con los parámetros indicados en \""
                    + classifierName + "\".");
        }
        if (matches.size() == 1) {
            return matches.getFirst();
        }
        if (targetParameterName != null) {
            List<Integer> paramMatches = new ArrayList<>();
            for (int idx : matches) {
                List<Map<String, Object>> params = methodParameters(methods.get(idx));
                if (params.stream().anyMatch(p -> key(stringField(p, "name")).equals(key(targetParameterName)))) {
                    paramMatches.add(idx);
                }
            }
            if (paramMatches.size() == 1) {
                return paramMatches.getFirst();
            }
        }
        fail("Existen varios métodos sobrecargados \"" + methodName + "\" en \""
                + classifierName + "\"; especifica sus tipos de parámetros.");
        return -1;
    }

    private static List<String> methodMapSignatureTypes(Map<String, Object> method) {
        List<Map<String, Object>> params = methodParameters(method);
        return params.stream().map(p -> key(stringField(p, "type"))).toList();
    }

    private static List<ParameterDefinition> extractParametersFromMethodMap(Map<String, Object> method) {
        List<Map<String, Object>> params = methodParameters(method);
        List<ParameterDefinition> list = new ArrayList<>(params.size());
        for (Map<String, Object> p : params) {
            list.add(new ParameterDefinition(stringField(p, "name"), stringField(p, "type")));
        }
        return list;
    }

    private static void verifyNotStructuralAssociationAttributeMutation(
            Map<String, Object> node,
            String attributeName,
            boolean disallowGeneratedPk,
            boolean addingOrRenamingTarget) {
        if (!(nodeData(node).get("association") instanceof Map<?, ?> assoc)) {
            return;
        }
        Set<String> fkKeys = new HashSet<>();
        if (assoc.get("endpoints") instanceof List<?> endpoints) {
            for (Object epRaw : endpoints) {
                if (epRaw instanceof Map<?, ?> ep && ep.get("foreignKeyName") instanceof String fk) {
                    String fkKey = key(fk);
                    fkKeys.add(fkKey);
                    fkKeys.add("fk_" + fkKey);
                }
            }
        }
        String attrKey = key(attributeName);
        String attrSql = key(sqlName(technicalName(attributeName)));
        if (fkKeys.contains(attrKey) || fkKeys.contains(attrSql)) {
            if (addingOrRenamingTarget) {
                fail("El atributo " + attributeName
                        + " colisiona con una clave foranea estructural.");
            } else {
                fail("El atributo \"" + attributeName
                        + "\" es una clave foranea estructural derivada de la clase asociativa \""
                        + nodeName(node) + "\" y no puede modificarse ni eliminarse como atributo independiente.");
            }
        }
        if (disallowGeneratedPk && "id".equals(attrKey)) {
            fail("El atributo \"id\" es la clave primaria estructural de la clase asociativa \""
                    + nodeName(node) + "\" y no puede eliminarse como atributo independiente.");
        }
    }

    private static void verifyNotStructuralAssociationEdgeDeletion(
            List<Map<String, Object>> nodes,
            String sourceEntity,
            String targetEntity) {
        Map<String, Object> srcNode = nodes.stream()
                .filter(n -> key(nodeName(n)).equals(key(sourceEntity)))
                .findFirst().orElse(null);
        Map<String, Object> tgtNode = nodes.stream()
                .filter(n -> key(nodeName(n)).equals(key(targetEntity)))
                .findFirst().orElse(null);
        if (srcNode == null || tgtNode == null) {
            return;
        }
        String srcId = stringField(srcNode, "id");
        String tgtId = stringField(tgtNode, "id");
        for (Map<String, Object> candidate : nodes) {
            if (nodeData(candidate).get("association") instanceof Map<?, ?> assoc
                    && assoc.get("endpoints") instanceof List<?> endpoints) {
                String assocId = stringField(candidate, "id");
                Set<String> endpointIds = new HashSet<>();
                for (Object epRaw : endpoints) {
                    if (epRaw instanceof Map<?, ?> ep && ep.get("entityId") instanceof String eid) {
                        endpointIds.add(eid);
                    }
                }
                if ((srcId.equals(assocId) && endpointIds.contains(tgtId))
                        || (tgtId.equals(assocId) && endpointIds.contains(srcId))) {
                    fail("No se puede eliminar una arista estructural individual de la clase asociativa \""
                            + nodeName(candidate) + "\". Para eliminar la asociación completa, elimina la clase asociativa \""
                            + nodeName(candidate) + "\".");
                }
            }
        }
    }

    private static Map<String, Object> findAssociationNodeByEndpoints(
            List<Map<String, Object>> nodes,
            String sourceEntity,
            String targetEntity) {
        Map<String, Object> srcNode = nodes.stream()
                .filter(n -> key(nodeName(n)).equals(key(sourceEntity)))
                .findFirst().orElse(null);
        Map<String, Object> tgtNode = nodes.stream()
                .filter(n -> key(nodeName(n)).equals(key(targetEntity)))
                .findFirst().orElse(null);
        if (srcNode == null || tgtNode == null) {
            return null;
        }
        Set<String> pair = Set.of(stringField(srcNode, "id"), stringField(tgtNode, "id"));
        for (Map<String, Object> candidate : nodes) {
            if (nodeData(candidate).get("association") instanceof Map<?, ?> assoc
                    && assoc.get("endpoints") instanceof List<?> endpoints) {
                Set<String> epIds = new HashSet<>();
                for (Object epRaw : endpoints) {
                    if (epRaw instanceof Map<?, ?> ep && ep.get("entityId") instanceof String eid) {
                        epIds.add(eid);
                    }
                }
                if (epIds.equals(pair)) {
                    return candidate;
                }
            }
        }
        return null;
    }

    private static boolean hasNoStandaloneEdgeBetween(
            List<Map<String, Object>> nodes,
            List<Map<String, Object>> edges,
            String sourceEntity,
            String targetEntity) {
        Map<String, Object> srcNode = findAnyClassifierNode(nodes, sourceEntity);
        Map<String, Object> tgtNode = findAnyClassifierNode(nodes, targetEntity);
        String srcId = stringField(srcNode, "id");
        String tgtId = stringField(tgtNode, "id");
        Set<String> structural = structuralRelationshipIds(nodes);
        for (Map<String, Object> edge : edges) {
            String edgeId = optionalStringField(edge, "id");
            if (edgeId != null && structural.contains(edgeId)) continue;
            String eSrc = stringField(edge, "source");
            String eTgt = stringField(edge, "target");
            if ((eSrc.equals(srcId) && eTgt.equals(tgtId)) || (eSrc.equals(tgtId) && eTgt.equals(srcId))) {
                return false;
            }
        }
        return true;
    }

    @SuppressWarnings("unchecked")
    private static void updateAssociationClassMultiplicities(
            List<Map<String, Object>> nodes,
            List<Map<String, Object>> edges,
            Map<String, Object> assocNode,
            RelationshipDefinition update) {
        if (update.sourceCardinality() == null || update.targetCardinality() == null) {
            fail("La actualización de la asociación requiere ambas cardinalidades.");
        }
        Map<String, Object> srcNode = findAnyClassifierNode(nodes, update.sourceEntity());
        String srcId = stringField(srcNode, "id");
        Map<String, Object> assoc = (Map<String, Object>) nodeData(assocNode).get("association");
        List<Map<String, Object>> endpoints = (List<Map<String, Object>>) assoc.get("endpoints");
        Map<String, Object> sourceEp = endpoints.stream()
                .filter(ep -> "SOURCE".equals(ep.get("role")))
                .findFirst().orElse(null);
        Map<String, Object> targetEp = endpoints.stream()
                .filter(ep -> "TARGET".equals(ep.get("role")))
                .findFirst().orElse(null);
        if (sourceEp == null || targetEp == null) {
            fail("Los extremos de la clase asociativa no son válidos.");
            return;
        }
        boolean direct = srcId.equals(sourceEp.get("entityId"));
        String newSrcMult = (direct ? update.sourceCardinality() : update.targetCardinality()).name();
        String newTgtMult = (direct ? update.targetCardinality() : update.sourceCardinality()).name();
        assoc.put("sourceMultiplicity", newSrcMult);
        assoc.put("targetMultiplicity", newTgtMult);
        String sourceRelId = (String) sourceEp.get("relationshipId");
        String targetRelId = (String) targetEp.get("relationshipId");
        for (Map<String, Object> edge : edges) {
            String eid = optionalStringField(edge, "id");
            if (sourceRelId.equals(eid)) {
                edgeData(edge).put("targetCardinality", newTgtMult);
            } else if (targetRelId.equals(eid)) {
                edgeData(edge).put("targetCardinality", newSrcMult);
            }
        }
    }

    private static boolean equivalentAttribute(Map<String, Object> existing, AttributeDefinition b) {
        String existingType = stringField(existing, "type");
        String mappedType = AI_TYPE_MAP.get(b.dataType());
        boolean sameType = existingType.equals(mappedType)
                || ("TEXT".equals(existingType) && b.dataType() == DiagramDataType.String);
        boolean existingPk = Boolean.TRUE.equals(existing.get("primaryKey"));
        boolean existingNullable = existing.get("nullable") instanceof Boolean n ? n : !existingPk;
        return sameType && existingPk == b.primaryKey() && existingNullable == b.nullable();
    }

    private static boolean equivalentRelationship(Map<String, Object> a, Map<String, Object> b) {
        String[] ac = edgeCardinalities(a);
        String[] bc = edgeCardinalities(b);
        String an = optionalStringField(edgeData(a), "name");
        String bn = optionalStringField(edgeData(b), "name");
        String aNameKey = key(an == null ? "" : an);
        String bNameKey = key(bn == null ? "" : bn);
        if (!aNameKey.equals(bNameKey)) {
            return false;
        }
        String aSrc = stringField(a, "source");
        String aTgt = stringField(a, "target");
        String bSrc = stringField(b, "source");
        String bTgt = stringField(b, "target");
        return (aSrc.equals(bSrc) && aTgt.equals(bTgt) && ac[0].equals(bc[0]) && ac[1].equals(bc[1]))
                || (aSrc.equals(bTgt) && aTgt.equals(bSrc) && ac[0].equals(bc[1]) && ac[1].equals(bc[0]));
    }

    private static void normalizeEdgeInPlace(Map<String, Object> edge) {
        Map<String, Object> data = edgeData(edge);
        Object relKind = data.get("relationKind");
        boolean nonMultiplicativeUmlRelation = "GENERALIZATION".equals(relKind)
                || "REALIZATION".equals(relKind)
                || "DEPENDENCY".equals(relKind);
        if (!nonMultiplicativeUmlRelation
                || data.containsKey("sourceCardinality")
                || data.containsKey("targetCardinality")
                || data.containsKey("relationshipType")) {
            String[] cards = edgeCardinalities(edge);
            data.put("sourceCardinality", cards[0]);
            data.put("targetCardinality", cards[1]);
        }
        data.remove("relationshipType");
        edge.put("type", "relationship");
        edge.remove("markerEnd");
        edge.remove("markerStart");
    }

    private static String[] edgeCardinalities(Map<String, Object> edge) {
        Map<String, Object> data = edgeData(edge);
        if (data.containsKey("sourceCardinality") || data.containsKey("targetCardinality")) {
            return new String[]{
                    stringField(data, "sourceCardinality"),
                    stringField(data, "targetCardinality")
            };
        }
        String legacy = optionalStringField(data, "relationshipType");
        if (legacy == null) {
            return new String[]{"ONE_ONE", "ONE_ONE"};
        }
        List<String> mapped = LEGACY_CARDINALITIES.get(legacy);
        if (mapped == null) {
            fail("Relación legacy inválida");
        }
        return new String[]{mapped.get(0), mapped.get(1)};
    }

    private static Set<String> structuralRelationshipIds(List<Map<String, Object>> nodes) {
        Set<String> ids = new HashSet<>();
        for (Map<String, Object> node : nodes) {
            if (nodeData(node).get("association") instanceof Map<?, ?> assoc
                    && assoc.get("endpoints") instanceof List<?> endpoints) {
                for (Object epRaw : endpoints) {
                    if (epRaw instanceof Map<?, ?> ep && ep.get("relationshipId") instanceof String relId) {
                        ids.add(relId);
                    }
                }
            }
        }
        return ids;
    }

    private static String deriveJoinTableName(Map<String, Object> edge, String sourceName, String targetName) {
        Map<String, Object> data = edgeData(edge);
        String customTable = optionalStringField(data, "joinTableName");
        String relName = optionalStringField(data, "name");
        String technicalTable;
        if (customTable != null && !customTable.isBlank()) {
            technicalTable = technicalName(customTable);
        } else {
            technicalTable = technicalName(sourceName) + technicalName(targetName)
                    + (relName != null && !relName.isBlank() ? technicalName(relName) : "");
        }
        return sqlName(technicalTable);
    }

    private static Set<String> collectUsedIds(
            List<Map<String, Object>> nodes,
            List<Map<String, Object>> edges) {
        Set<String> ids = new HashSet<>();
        for (Map<String, Object> node : nodes) {
            String nid = optionalStringField(node, "id");
            if (nid != null) {
                ids.add(nid);
            }
            for (Map<String, Object> attr : nodeAttributes(node)) {
                String aid = optionalStringField(attr, "id");
                if (aid != null) {
                    ids.add(aid);
                }
            }
            if (nodeData(node).get("methods") instanceof List<?> methods) {
                for (Object mRaw : methods) {
                    if (mRaw instanceof Map<?, ?> m) {
                        String mid = optionalStringField(m, "id");
                        if (mid != null) {
                            ids.add(mid);
                        }
                        if (m.get("parameters") instanceof List<?> params) {
                            for (Object pRaw : params) {
                                if (pRaw instanceof Map<?, ?> p) {
                                    String pid = optionalStringField(p, "id");
                                    if (pid != null) {
                                        ids.add(pid);
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        for (Map<String, Object> edge : edges) {
            String eid = optionalStringField(edge, "id");
            if (eid != null) {
                ids.add(eid);
            }
        }
        return ids;
    }

    private static String uniqueId(String prefix, Set<String> usedIds, Supplier<String> idSupplier) {
        String id = prefix + "-" + idSupplier.get();
        if (!usedIds.add(id)) {
            fail("No se pudo generar un identificador único");
        }
        return id;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> nodeData(Map<String, Object> node) {
        if (!(node.get("data") instanceof Map<?, ?> data)) {
            fail("Datos de entidad inválidos");
            return Map.of();
        }
        return (Map<String, Object>) data;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> nodeAttributes(Map<String, Object> node) {
        Object raw = nodeData(node).get("attributes");
        if (!(raw instanceof List<?> list)) {
            List<Map<String, Object>> created = new ArrayList<>();
            nodeData(node).put("attributes", created);
            return created;
        }
        return (List<Map<String, Object>>) raw;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> nodeMethods(Map<String, Object> node) {
        Object raw = nodeData(node).get("methods");
        if (!(raw instanceof List<?>)) {
            List<Map<String, Object>> created = new ArrayList<>();
            nodeData(node).put("methods", created);
            return created;
        }
        return (List<Map<String, Object>>) raw;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> methodParameters(Map<String, Object> method) {
        Object raw = method.get("parameters");
        if (!(raw instanceof List<?>)) {
            List<Map<String, Object>> created = new ArrayList<>();
            method.put("parameters", created);
            return created;
        }
        return (List<Map<String, Object>>) raw;
    }

    @SuppressWarnings("unchecked")
    private static List<String> nodeLiterals(Map<String, Object> node) {
        Object raw = nodeData(node).get("literals");
        if (!(raw instanceof List<?>)) {
            List<String> created = new ArrayList<>();
            nodeData(node).put("literals", created);
            return created;
        }
        return (List<String>) raw;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> edgeData(Map<String, Object> edge) {
        Object raw = edge.get("data");
        if (!(raw instanceof Map<?, ?>)) {
            Map<String, Object> created = new LinkedHashMap<>();
            edge.put("data", created);
            return created;
        }
        return (Map<String, Object>) raw;
    }

    private static String nodeName(Map<String, Object> node) {
        return stringField(nodeData(node), "name");
    }

    private static double positionCoord(Map<String, Object> node, String axis) {
        if (node.get("position") instanceof Map<?, ?> pos && pos.get(axis) instanceof Number num) {
            return num.doubleValue();
        }
        return 180.0;
    }

    private static String stringField(Map<?, ?> map, String field) {
        Object val = map.get(field);
        if (!(val instanceof String text) || text.isBlank()) {
            fail("Campo requerido inválido: " + field);
            return "";
        }
        return text;
    }

    private static String optionalStringField(Map<?, ?> map, String field) {
        Object val = map.get(field);
        return (val instanceof String text && !text.isBlank()) ? text.strip() : null;
    }

    private static boolean isMany(String cardinality) {
        return "ZERO_MANY".equals(cardinality) || "ONE_MANY".equals(cardinality);
    }

    private static String key(String value) {
        return Objects.toString(value, "").strip().toLowerCase(Locale.ROOT);
    }

    private static String technicalName(String value) {
        String cleaned = Normalizer.normalize(value.strip(), Normalizer.Form.NFD)
                .replaceAll("\\p{M}+", "")
                .replaceAll("([a-z0-9])([A-Z])", "$1 $2")
                .replaceAll("[^A-Za-z0-9]+", " ")
                .strip();
        StringBuilder result = new StringBuilder();
        for (String word : cleaned.split(" +")) {
            if (!word.isEmpty()) {
                result.append(word.substring(0, 1).toUpperCase(Locale.ROOT))
                        .append(word.substring(1).toLowerCase(Locale.ROOT));
            }
        }
        if (!result.isEmpty() && Character.isDigit(result.charAt(0))) {
            result.insert(0, 'N');
        }
        return result.toString();
    }

    private static String sqlName(String value) {
        return value.replaceAll("([a-z0-9])([A-Z])", "$1_$2").toLowerCase(Locale.ROOT);
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> cloneObjectList(Object raw, String field) {
        if (raw == null) {
            return new ArrayList<>();
        }
        if (!(raw instanceof List<?> list)) {
            fail("Lista inválida en " + field);
            return new ArrayList<>();
        }
        List<Map<String, Object>> copy = new ArrayList<>(list.size());
        for (Object item : list) {
            if (!(item instanceof Map<?, ?> map)) {
                fail("Elemento inválido en " + field);
            } else {
                copy.add(deepCopyMap((Map<String, Object>) map));
            }
        }
        return copy;
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> deepCopyMap(Map<String, Object> source) {
        Map<String, Object> copy = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : source.entrySet()) {
            copy.put(entry.getKey(), deepCopyValue(entry.getValue()));
        }
        return copy;
    }

    @SuppressWarnings("unchecked")
    private static Object deepCopyValue(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> nested = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                nested.put(String.valueOf(entry.getKey()), deepCopyValue(entry.getValue()));
            }
            return nested;
        }
        if (value instanceof List<?> list) {
            List<Object> nested = new ArrayList<>(list.size());
            for (Object item : list) {
                nested.add(deepCopyValue(item));
            }
            return nested;
        }
        return value;
    }

    private static void fail(String message) {
        throw new DiagramValidationException(message);
    }

    private record FoundAttribute(Map<String, Object> node, Map<String, Object> attribute) {
    }

    private record FoundRelationship(
            Map<String, Object> edge,
            Map<String, Object> source,
            Map<String, Object> target) {
    }
}
