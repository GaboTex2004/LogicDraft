package com.sw1.backend.ai.diagram.validation;

import com.sw1.backend.ai.client.AiServiceException;
import com.sw1.backend.ai.client.AiServiceException.ErrorCategory;
import com.sw1.backend.ai.diagram.dto.*;
import com.sw1.backend.ai.diagram.model.DiagramCardinality;
import com.sw1.backend.ai.diagram.model.DiagramOperationType;
import com.sw1.backend.ai.diagram.validation.DiagramContextMapper.ReadOnlyUmlElement;
import com.sw1.backend.ai.diagram.validation.DiagramContextMapper.ReadOnlyUmlRelation;
import com.sw1.backend.ai.diagram.validation.DiagramContextMapper.SemanticNode;
import com.sw1.backend.ai.diagram.validation.DiagramContextMapper.SemanticRelationship;
import java.text.Normalizer;
import java.util.*;
import org.springframework.http.HttpStatus;
import static com.sw1.backend.ai.diagram.validation.DiagramContextMapper.key;

public final class ContextualOperationValidator {
    private static final Set<String> MULTIPLICATIVE_KINDS = Set.of("ASSOCIATION", "AGGREGATION", "COMPOSITION");
    private static final Set<String> NON_MULTIPLICATIVE_KINDS = Set.of("GENERALIZATION", "REALIZATION", "DEPENDENCY");

    private ContextualOperationValidator() {}

    private static final class VirtualClassifier {
        String name;
        String kind;
        Boolean isAbstract;
        Map<String, AttributeDefinition> attributes = new LinkedHashMap<>();
        List<MethodDefinition> methods = new ArrayList<>();
        List<String> literals = new ArrayList<>();

        VirtualClassifier(String name, String kind, Boolean isAbstract) {
            this.name = name;
            this.kind = kind;
            this.isAbstract = isAbstract;
        }
    }

    public static DiagramInterpretResponse validate(DiagramContext context, DiagramInterpretResponse response) {
        return validate(context, response, context.readOnlyElementNames());
    }

    public static DiagramInterpretResponse validate(
            DiagramContext context,
            DiagramInterpretResponse response,
            Set<String> readOnlyElementNames) {
        Set<String> readOnlyKeys = new HashSet<>();
        if (readOnlyElementNames != null) {
            for (String readOnlyName : readOnlyElementNames) {
                if (readOnlyName != null && !readOnlyName.isBlank()) {
                    readOnlyKeys.add(key(readOnlyName));
                }
            }
        }
        if (context.readOnlyElementNames() != null) {
            for (String readOnlyName : context.readOnlyElementNames()) {
                if (readOnlyName != null && !readOnlyName.isBlank()) {
                    readOnlyKeys.add(key(readOnlyName));
                }
            }
        }

        Map<String, SemanticNode> semanticNodesByName = new LinkedHashMap<>();
        for (SemanticNode node : context.semanticNodes()) {
            semanticNodesByName.put(key(node.name()), node);
        }
        Set<String> assocNames = new HashSet<>();
        for (DiagramAssociationContext assoc : context.associations()) {
            assocNames.add(key(assoc.entityName()));
        }

        Map<String, Map<String, AttributeDefinition>> entities = new LinkedHashMap<>();
        Map<String, VirtualClassifier> classifiers = new LinkedHashMap<>();

        for (EntityDefinition entity : context.entities()) {
            String entityKey = key(entity.name());
            SemanticNode semNode = semanticNodesByName.get(entityKey);
            String kind = semNode != null ? semNode.kind()
                    : (entity.kind() != null ? entity.kind()
                    : (assocNames.contains(entityKey) ? "ASSOCIATION_CLASS" : "ENTITY"));
            Boolean isAbstract = semNode != null ? semNode.isAbstract() : entity.isAbstract();
            VirtualClassifier vc = new VirtualClassifier(entity.name(), kind, isAbstract);
            Map<String, AttributeDefinition> attributes = new LinkedHashMap<>();
            for (AttributeDefinition attribute : entity.attributes()) {
                attributes.put(key(attribute.name()), attribute);
                vc.attributes.put(key(attribute.name()), attribute);
            }
            if (semNode != null && semNode.methods() != null) {
                vc.methods.addAll(semNode.methods());
            } else if (entity.methods() != null) {
                vc.methods.addAll(entity.methods());
            }
            entities.put(entityKey, attributes);
            classifiers.put(entityKey, vc);
        }

        for (ReadOnlyUmlElement ro : context.readOnlyElements()) {
            String roKey = key(ro.name());
            readOnlyKeys.add(roKey);
            VirtualClassifier vc = new VirtualClassifier(ro.name(), ro.kind(), ro.isAbstract());
            if (ro.literals() != null) {
                vc.literals.addAll(ro.literals());
            }
            if (ro.methods() != null && !ro.methods().isEmpty()) {
                vc.methods.addAll(ro.methods());
            } else if (ro.methodNames() != null) {
                for (String mName : ro.methodNames()) {
                    vc.methods.add(new MethodDefinition(mName, "void", "public", List.of()));
                }
            }
            classifiers.put(roKey, vc);
        }

        for (String roKey : readOnlyKeys) {
            classifiers.computeIfAbsent(roKey, k -> new VirtualClassifier(k, "INTERFACE", null));
        }

        List<DiagramAssociationContext> virtualAssociations = new ArrayList<>(context.associations());
        Set<List<String>> relationships = new HashSet<>();
        List<RelationshipDefinition> virtualRelationships = new ArrayList<>();
        Map<String, RelationshipDefinition> relationshipsById = new LinkedHashMap<>();

        Map<String, String> semanticRelKindsById = new LinkedHashMap<>();
        for (SemanticRelationship sr : context.semanticRelationships()) {
            if (sr.id() != null && sr.relationKind() != null) {
                semanticRelKindsById.put(sr.id(), sr.relationKind());
            }
        }

        for (RelationshipDefinition relation : context.relationships()) {
            String relKind = relation.kind() != null
                    ? relation.kind()
                    : (relation.id() != null ? semanticRelKindsById.get(relation.id()) : null);
            RelationshipDefinition enriched = relKind != null && relation.kind() == null
                    ? new RelationshipDefinition(
                            relation.sourceEntity(), relation.targetEntity(),
                            relation.sourceCardinality(), relation.targetCardinality(),
                            relation.name(), relation.joinTableName(), relation.id(),
                            relKind, relation.sourceMultiplicity(), relation.targetMultiplicity(), relation.aggregationEnd())
                    : relation;
            virtualRelationships.add(enriched);
            relationships.add(relationKey(enriched));
            if (enriched.id() != null) {
                relationshipsById.put(enriched.id(), enriched);
            }
        }

        List<RelationshipDefinition> virtualNonMultRelations = new ArrayList<>();
        for (ReadOnlyUmlRelation roRel : context.readOnlyRelations()) {
            virtualNonMultRelations.add(new RelationshipDefinition(
                    roRel.sourceName(), roRel.targetName(), null, null, null, null, roRel.id(),
                    roRel.relationKind(), null, null, null));
        }

        List<DiagramOperation> result = new ArrayList<>();
        for (DiagramOperation operation : response.operations()) {
            switch (operation.type()) {
                case ADD_ENTITY -> {
                    EntityDefinition entity = operation.entity();
                    String entityKey = key(entity.name());
                    String kind = entity.kind() == null ? "ENTITY" : entity.kind();
                    if (readOnlyKeys.contains(entityKey)) {
                        throw readOnlyConflict(entity.name());
                    }
                    if (entities.containsKey(entityKey) || classifiers.containsKey(entityKey)) {
                        throw conflict("Ya existe una entidad con ese nombre");
                    }
                    VirtualClassifier vc = new VirtualClassifier(entity.name(), kind, entity.isAbstract());
                    if ("ENUMERATION".equals(kind)) {
                        if (!entity.attributes().isEmpty()) {
                            throw conflict("La enumeracion \"" + entity.name() + "\" debe usar literales, no atributos");
                        }
                        if (entity.methods() != null && !entity.methods().isEmpty()) {
                            throw conflict("La enumeracion \"" + entity.name() + "\" no admite metodos");
                        }
                        if (Boolean.TRUE.equals(entity.isAbstract())) {
                            throw conflict("Una enumeracion no puede ser abstracta");
                        }
                        if (entity.literals() != null) {
                            Set<String> seenLiterals = new HashSet<>();
                            for (String lit : entity.literals()) {
                                if (!seenLiterals.add(key(lit))) {
                                    throw conflict("La enumeracion propuesta contiene literales duplicados");
                                }
                                vc.literals.add(lit);
                            }
                        }
                        classifiers.put(entityKey, vc);
                        readOnlyKeys.add(entityKey);
                    } else if ("INTERFACE".equals(kind)) {
                        if (!entity.attributes().isEmpty()) {
                            throw conflict("La interfaz \"" + entity.name() + "\" no admite atributos persistentes");
                        }
                        if (entity.literals() != null && !entity.literals().isEmpty()) {
                            throw conflict("Solo una enumeracion puede declarar literales");
                        }
                        if (entity.methods() != null) {
                            for (MethodDefinition method : entity.methods()) {
                                validateAndAddMethod(vc, method);
                            }
                        }
                        classifiers.put(entityKey, vc);
                        readOnlyKeys.add(entityKey);
                    } else {
                        if (entity.literals() != null && !entity.literals().isEmpty()) {
                            throw conflict("Solo una enumeracion puede declarar literales");
                        }
                        Map<String, AttributeDefinition> attributes = new LinkedHashMap<>();
                        for (AttributeDefinition attribute : entity.attributes()) {
                            if (attribute.primaryKey() && attribute.nullable()) {
                                throw conflict("Una PK no puede aceptar null");
                            }
                            if (attributes.putIfAbsent(key(attribute.name()), attribute) != null) {
                                throw conflict("La entidad propuesta contiene atributos duplicados");
                            }
                            vc.attributes.put(key(attribute.name()), attribute);
                        }
                        if (entity.methods() != null) {
                            for (MethodDefinition method : entity.methods()) {
                                validateAndAddMethod(vc, method);
                            }
                        }
                        entities.put(entityKey, attributes);
                        classifiers.put(entityKey, vc);
                    }
                }
                case DELETE_ENTITY -> {
                    String entityKey = key(operation.entityName());
                    VirtualClassifier removedClassifier = classifiers.remove(entityKey);
                    Map<String, AttributeDefinition> removedEntity = entities.remove(entityKey);
                    readOnlyKeys.remove(entityKey);
                    if (removedClassifier == null && removedEntity == null) {
                        throw conflict("La entidad a eliminar no existe");
                    }
                    virtualAssociations.removeIf(assoc -> key(assoc.entityName()).equals(entityKey)
                            || assoc.endpointEntityNames().stream().anyMatch(ep -> key(ep).equals(entityKey)));
                    virtualRelationships.removeIf(relation -> key(relation.sourceEntity()).equals(entityKey)
                            || key(relation.targetEntity()).equals(entityKey));
                    virtualNonMultRelations.removeIf(relation -> key(relation.sourceEntity()).equals(entityKey)
                            || key(relation.targetEntity()).equals(entityKey));
                    relationships.clear();
                    virtualRelationships.forEach(relation -> relationships.add(relationKey(relation)));
                }
                case RENAME_ENTITY -> {
                    String previous = key(operation.entityName()), next = key(operation.newName());
                    if (!previous.equals(next) && (readOnlyKeys.contains(next) || entities.containsKey(next) || classifiers.containsKey(next))) {
                        throw conflict("Ya existe una entidad con el nuevo nombre");
                    }
                    VirtualClassifier vc = classifiers.remove(previous);
                    Map<String, AttributeDefinition> attributes = entities.remove(previous);
                    if (vc == null && attributes == null) {
                        throw conflict("La entidad a renombrar no existe");
                    }
                    if (vc != null) {
                        vc.name = operation.newName();
                        classifiers.put(next, vc);
                    }
                    if (attributes != null) {
                        entities.put(next, attributes);
                    }
                    if (readOnlyKeys.remove(previous)) {
                        readOnlyKeys.add(next);
                    }
                    for (int index = 0; index < virtualRelationships.size(); index++) {
                        RelationshipDefinition relation = virtualRelationships.get(index);
                        virtualRelationships.set(index, new RelationshipDefinition(
                                key(relation.sourceEntity()).equals(previous) ? operation.newName() : relation.sourceEntity(),
                                key(relation.targetEntity()).equals(previous) ? operation.newName() : relation.targetEntity(),
                                relation.sourceCardinality(), relation.targetCardinality(), relation.name(),
                                relation.joinTableName(), relation.id(), relation.kind(),
                                relation.sourceMultiplicity(), relation.targetMultiplicity(), relation.aggregationEnd()));
                    }
                    for (int index = 0; index < virtualNonMultRelations.size(); index++) {
                        RelationshipDefinition relation = virtualNonMultRelations.get(index);
                        virtualNonMultRelations.set(index, new RelationshipDefinition(
                                key(relation.sourceEntity()).equals(previous) ? operation.newName() : relation.sourceEntity(),
                                key(relation.targetEntity()).equals(previous) ? operation.newName() : relation.targetEntity(),
                                null, null, relation.name(), null, relation.id(), relation.kind(), null, null, null));
                    }
                    relationships.clear();
                    virtualRelationships.forEach(relation -> relationships.add(relationKey(relation)));
                }
                case UPDATE_CLASSIFIER -> {
                    String entityKey = key(operation.entityName());
                    VirtualClassifier vc = classifiers.get(entityKey);
                    if (vc == null) {
                        throw conflict("El clasificador \"" + operation.entityName() + "\" no existe");
                    }
                    if (operation.kind() != null && !operation.kind().equalsIgnoreCase(vc.kind)) {
                        throw conflict("No se puede cambiar automaticamente el tipo del clasificador \""
                                + operation.entityName() + "\" de " + vc.kind + " a " + operation.kind()
                                + " porque tiene implicaciones estructurales");
                    }
                    if (operation.abstractValue() != null) {
                        if ("ENUMERATION".equals(vc.kind)) {
                            throw conflict("La enumeracion \"" + operation.entityName() + "\" no puede ser abstracta");
                        }
                        vc.isAbstract = operation.abstractValue();
                    }
                }
                case ADD_ATTRIBUTE -> {
                    if (readOnlyKeys.contains(key(operation.entityName()))) {
                        throw readOnlyConflict(operation.entityName());
                    }
                    Map<String, AttributeDefinition> attributes = entities.get(key(operation.entityName()));
                    if (attributes == null) throw conflict("La entidad destino no existe");
                    AttributeDefinition proposed = operation.attribute();
                    if (proposed.primaryKey() && proposed.nullable()) {
                        throw conflict("Una PK no puede aceptar null");
                    }
                    AttributeDefinition existing = attributes.get(key(proposed.name()));
                    if (existing != null) {
                        if (existing.dataType() == proposed.dataType()
                                && existing.primaryKey() == proposed.primaryKey()
                                && existing.nullable() == proposed.nullable()) continue;
                        throw conflict("El atributo propuesto entra en conflicto con un atributo existente");
                    }
                    attributes.put(key(proposed.name()), proposed);
                }
                case DELETE_ATTRIBUTE -> {
                    Map<String, AttributeDefinition> attributes =
                            requiredAttributes(entities, readOnlyKeys, operation.entityName());
                    verifyNotStructuralAttributeMutation(
                            virtualAssociations, operation.entityName(), operation.attributeName(), true);
                    if (attributes.remove(key(operation.attributeName())) == null)
                        throw conflict("El atributo a eliminar no existe");
                }
                case RENAME_ATTRIBUTE -> {
                    Map<String, AttributeDefinition> attributes =
                            requiredAttributes(entities, readOnlyKeys, operation.entityName());
                    verifyNotStructuralAttributeMutation(
                            virtualAssociations, operation.entityName(), operation.attributeName(), false);
                    verifyNotStructuralForeignKeyCollision(
                            virtualAssociations, operation.entityName(), operation.newName());
                    AttributeDefinition attribute = attributes.remove(key(operation.attributeName()));
                    if (attribute == null) throw conflict("El atributo a renombrar no existe");
                    if (attributes.putIfAbsent(key(operation.newName()), new AttributeDefinition(operation.newName(),
                            attribute.dataType(), attribute.primaryKey(), attribute.nullable(),
                            attribute.visibility(), attribute.isStatic())) != null)
                        throw conflict("Ya existe un atributo con el nuevo nombre");
                }
                case CHANGE_ATTRIBUTE_TYPE, SET_ATTRIBUTE_PRIMARY_KEY, SET_ATTRIBUTE_NULLABLE,
                        SET_ATTRIBUTE_VISIBILITY, SET_ATTRIBUTE_STATIC -> {
                    Map<String, AttributeDefinition> attributes =
                            requiredAttributes(entities, readOnlyKeys, operation.entityName());
                    boolean removingPk = operation.type() == DiagramOperationType.SET_ATTRIBUTE_PRIMARY_KEY
                            && Boolean.FALSE.equals(operation.value());
                    verifyNotStructuralAttributeMutation(
                            virtualAssociations, operation.entityName(), operation.attributeName(), removingPk);
                    String attributeKey = key(operation.attributeName());
                    AttributeDefinition attribute = attributes.get(attributeKey);
                    if (attribute == null) throw conflict("El atributo a modificar no existe");
                    AttributeDefinition updated = switch (operation.type()) {
                        case CHANGE_ATTRIBUTE_TYPE -> new AttributeDefinition(attribute.name(), operation.dataType(),
                                attribute.primaryKey(), attribute.nullable(), attribute.visibility(), attribute.isStatic());
                        case SET_ATTRIBUTE_PRIMARY_KEY -> new AttributeDefinition(attribute.name(), attribute.dataType(),
                                operation.value(), operation.value() ? false : attribute.nullable(),
                                attribute.visibility(), attribute.isStatic());
                        case SET_ATTRIBUTE_NULLABLE -> {
                            if (operation.value() && attribute.primaryKey()) throw conflict("Una PK no puede aceptar null");
                            yield new AttributeDefinition(attribute.name(), attribute.dataType(), attribute.primaryKey(),
                                    operation.value(), attribute.visibility(), attribute.isStatic());
                        }
                        case SET_ATTRIBUTE_VISIBILITY -> new AttributeDefinition(attribute.name(), attribute.dataType(),
                                attribute.primaryKey(), attribute.nullable(), operation.visibility(), attribute.isStatic());
                        case SET_ATTRIBUTE_STATIC -> new AttributeDefinition(attribute.name(), attribute.dataType(),
                                attribute.primaryKey(), attribute.nullable(), attribute.visibility(),
                                operation.value() != null ? operation.value() : operation.staticValue());
                        default -> throw new IllegalStateException();
                    };
                    attributes.put(attributeKey, updated);
                }
                case ADD_METHOD -> {
                    VirtualClassifier vc = requireMethodBearingClassifier(classifiers, operation.entityName());
                    validateAndAddMethod(vc, operation.method());
                }
                case UPDATE_METHOD -> {
                    VirtualClassifier vc = requireMethodBearingClassifier(classifiers, operation.entityName());
                    int idx = findTargetMethodIndex(vc, operation.methodName(), operation.parameterTypes(), null);
                    MethodDefinition current = vc.methods.get(idx);
                    MethodDefinition replacement;
                    if (operation.method() != null) {
                        replacement = operation.method();
                    } else {
                        String newName = operation.newName() != null ? operation.newName() : current.name();
                        String returnType = operation.returnType() != null ? operation.returnType() : current.returnType();
                        String visibility = operation.visibility() != null ? operation.visibility() : current.visibility();
                        Boolean isStatic = operation.staticValue() != null ? operation.staticValue() : current.isStatic();
                        Boolean isAbstract = operation.abstractValue() != null ? operation.abstractValue() : current.isAbstract();
                        replacement = new MethodDefinition(
                                newName, returnType, visibility, isStatic, isAbstract, current.parameters());
                    }
                    validateMethodConstraints(vc, replacement, idx);
                    vc.methods.set(idx, replacement);
                }
                case DELETE_METHOD -> {
                    VirtualClassifier vc = requireMethodBearingClassifier(classifiers, operation.entityName());
                    int idx = findTargetMethodIndex(vc, operation.methodName(), operation.parameterTypes(), null);
                    vc.methods.remove(idx);
                }
                case ADD_METHOD_PARAMETER -> {
                    VirtualClassifier vc = requireMethodBearingClassifier(classifiers, operation.entityName());
                    int idx = findTargetMethodIndex(vc, operation.methodName(), operation.parameterTypes(), null);
                    MethodDefinition current = vc.methods.get(idx);
                    ParameterDefinition newParam = operation.parameter();
                    List<ParameterDefinition> nextParams = new ArrayList<>(
                            current.parameters() == null ? List.of() : current.parameters());
                    for (ParameterDefinition p : nextParams) {
                        if (key(p.name()).equals(key(newParam.name()))) {
                            throw conflict("El metodo \"" + current.name() + "\" ya tiene un parametro llamado \"" + newParam.name() + "\"");
                        }
                    }
                    nextParams.add(newParam);
                    MethodDefinition replacement = new MethodDefinition(
                            current.name(), current.returnType(), current.visibility(),
                            current.isStatic(), current.isAbstract(), List.copyOf(nextParams));
                    validateMethodConstraints(vc, replacement, idx);
                    vc.methods.set(idx, replacement);
                }
                case UPDATE_METHOD_PARAMETER -> {
                    VirtualClassifier vc = requireMethodBearingClassifier(classifiers, operation.entityName());
                    int idx = findTargetMethodIndex(
                            vc, operation.methodName(), operation.parameterTypes(), operation.parameterName());
                    MethodDefinition current = vc.methods.get(idx);
                    List<ParameterDefinition> nextParams = new ArrayList<>(
                            current.parameters() == null ? List.of() : current.parameters());
                    int paramIdx = -1;
                    for (int i = 0; i < nextParams.size(); i++) {
                        if (key(nextParams.get(i).name()).equals(key(operation.parameterName()))) {
                            paramIdx = i;
                            break;
                        }
                    }
                    if (paramIdx < 0) {
                        throw conflict("El parametro \"" + operation.parameterName() + "\" no existe en el metodo \"" + current.name() + "\"");
                    }
                    ParameterDefinition currentParam = nextParams.get(paramIdx);
                    String newParamName = operation.parameter() != null
                            ? operation.parameter().name()
                            : (operation.newName() != null ? operation.newName() : currentParam.name());
                    String newParamType = operation.parameter() != null && operation.parameter().type() != null
                            ? operation.parameter().type()
                            : (operation.newType() != null ? operation.newType() : currentParam.type());
                    for (int i = 0; i < nextParams.size(); i++) {
                        if (i != paramIdx && key(nextParams.get(i).name()).equals(key(newParamName))) {
                            throw conflict("El metodo \"" + current.name() + "\" ya contiene un parametro con el nombre \"" + newParamName + "\"");
                        }
                    }
                    nextParams.set(paramIdx, new ParameterDefinition(newParamName, newParamType));
                    MethodDefinition replacement = new MethodDefinition(
                            current.name(), current.returnType(), current.visibility(),
                            current.isStatic(), current.isAbstract(), List.copyOf(nextParams));
                    validateMethodConstraints(vc, replacement, idx);
                    vc.methods.set(idx, replacement);
                }
                case DELETE_METHOD_PARAMETER -> {
                    VirtualClassifier vc = requireMethodBearingClassifier(classifiers, operation.entityName());
                    int idx = findTargetMethodIndex(
                            vc, operation.methodName(), operation.parameterTypes(), operation.parameterName());
                    MethodDefinition current = vc.methods.get(idx);
                    List<ParameterDefinition> nextParams = new ArrayList<>(
                            current.parameters() == null ? List.of() : current.parameters());
                    boolean removed = nextParams.removeIf(p -> key(p.name()).equals(key(operation.parameterName())));
                    if (!removed) {
                        throw conflict("El parametro \"" + operation.parameterName() + "\" no existe en el metodo \"" + current.name() + "\"");
                    }
                    MethodDefinition replacement = new MethodDefinition(
                            current.name(), current.returnType(), current.visibility(),
                            current.isStatic(), current.isAbstract(), List.copyOf(nextParams));
                    validateMethodConstraints(vc, replacement, idx);
                    vc.methods.set(idx, replacement);
                }
                case ADD_ENUM_LITERAL -> {
                    VirtualClassifier vc = requireEnumerationClassifier(classifiers, operation.entityName());
                    String lit = operation.literal();
                    if (vc.literals.stream().anyMatch(existing -> key(existing).equals(key(lit)))) {
                        throw conflict("La enumeracion \"" + vc.name + "\" ya contiene el literal \"" + lit + "\"");
                    }
                    vc.literals.add(lit);
                }
                case RENAME_ENUM_LITERAL -> {
                    VirtualClassifier vc = requireEnumerationClassifier(classifiers, operation.entityName());
                    String oldLit = operation.literal();
                    String newLit = operation.newLiteral() != null ? operation.newLiteral() : operation.newName();
                    int idx = -1;
                    for (int i = 0; i < vc.literals.size(); i++) {
                        if (key(vc.literals.get(i)).equals(key(oldLit))) {
                            idx = i;
                            break;
                        }
                    }
                    if (idx < 0) {
                        throw conflict("El literal \"" + oldLit + "\" no existe en la enumeracion \"" + vc.name + "\"");
                    }
                    for (int i = 0; i < vc.literals.size(); i++) {
                        if (i != idx && key(vc.literals.get(i)).equals(key(newLit))) {
                            throw conflict("La enumeracion \"" + vc.name + "\" ya contiene el literal \"" + newLit + "\"");
                        }
                    }
                    vc.literals.set(idx, newLit);
                }
                case DELETE_ENUM_LITERAL -> {
                    VirtualClassifier vc = requireEnumerationClassifier(classifiers, operation.entityName());
                    String lit = operation.literal();
                    boolean removed = vc.literals.removeIf(existing -> key(existing).equals(key(lit)));
                    if (!removed) {
                        throw conflict("El literal \"" + lit + "\" no existe en la enumeracion \"" + vc.name + "\"");
                    }
                }
                case ADD_RELATIONSHIP -> {
                    RelationshipDefinition relation = operation.relationship();
                    String relKind = relation.kind() == null ? "ASSOCIATION" : relation.kind();
                    String srcKey = key(relation.sourceEntity());
                    String tgtKey = key(relation.targetEntity());
                    if (srcKey.equals(tgtKey)) {
                        throw conflict("Las autorrelaciones nuevas no estan habilitadas en este MVP");
                    }
                    if (MULTIPLICATIVE_KINDS.contains(relKind)) {
                        if (readOnlyKeys.contains(srcKey)) {
                            throw readOnlyConflict(relation.sourceEntity());
                        }
                        if (readOnlyKeys.contains(tgtKey)) {
                            throw readOnlyConflict(relation.targetEntity());
                        }
                        if (!entities.containsKey(srcKey) || !entities.containsKey(tgtKey)) {
                            throw conflict("La relacion referencia una entidad inexistente");
                        }
                        if (relation.sourceCardinality() == null || relation.targetCardinality() == null) {
                            throw conflict("La cardinalidad propuesta no es valida");
                        }
                        if (relation.aggregationEnd() != null && !"AGGREGATION".equals(relKind) && !"COMPOSITION".equals(relKind)) {
                            throw conflict("El extremo de agregacion solo es valido en AGGREGATION o COMPOSITION");
                        }
                        if (!relationships.add(relationKey(relation))) continue;
                        virtualRelationships.add(relation);
                    } else if (NON_MULTIPLICATIVE_KINDS.contains(relKind)) {
                        VirtualClassifier srcClassifier = classifiers.get(srcKey);
                        VirtualClassifier tgtClassifier = classifiers.get(tgtKey);
                        if (srcClassifier == null || tgtClassifier == null) {
                            throw conflict("La relacion referencia un clasificador inexistente");
                        }
                        if (relation.sourceMultiplicity() != null || relation.targetMultiplicity() != null) {
                            throw conflict("La relacion " + relKind + " no admite multiplicidades");
                        }
                        validateUmlRelationEndpoints(relKind, srcClassifier.kind, tgtClassifier.kind);
                        boolean duplicate = virtualNonMultRelations.stream().anyMatch(existing ->
                                key(existing.sourceEntity()).equals(srcKey)
                                        && key(existing.targetEntity()).equals(tgtKey)
                                        && relKind.equals(existing.kind()));
                        if (duplicate) continue;
                        virtualNonMultRelations.add(relation);
                    } else {
                        throw conflict("Tipo de relacion UML no soportado: " + relKind);
                    }
                }
                case DELETE_RELATIONSHIP -> {
                    RelationshipDefinition ref = operation.relationship();
                    String srcKey = key(ref.sourceEntity());
                    String tgtKey = key(ref.targetEntity());
                    verifyNotStructuralAssociationEdgeDeletion(virtualAssociations, ref.sourceEntity(), ref.targetEntity());
                    String relKind = ref.kind();
                    if (relKind != null && NON_MULTIPLICATIVE_KINDS.contains(relKind)) {
                        RelationshipDefinition match = findNonMultiplicativeRelationship(
                                virtualNonMultRelations, ref.sourceEntity(), ref.targetEntity(), relKind);
                        virtualNonMultRelations.remove(match);
                    } else {
                        // Could be in virtualRelationships or virtualNonMultRelations if relKind is null
                        List<RelationshipDefinition> multMatches = findMatchingRelationships(
                                virtualRelationships, ref.sourceEntity(), ref.targetEntity(), ref.name(), relKind);
                        if (!multMatches.isEmpty()) {
                            if (multMatches.size() > 1) {
                                throw conflict("Existen varias relaciones candidatas; especifica su nombre");
                            }
                            RelationshipDefinition relation = multMatches.getFirst();
                            virtualRelationships.remove(relation);
                            relationships.remove(relationKey(relation));
                        } else {
                            List<RelationshipDefinition> nonMultMatches = findMatchingNonMultRelationships(
                                    virtualNonMultRelations, ref.sourceEntity(), ref.targetEntity(), relKind);
                            if (nonMultMatches.isEmpty()) {
                                if (readOnlyKeys.contains(srcKey)) throw readOnlyConflict(ref.sourceEntity());
                                if (readOnlyKeys.contains(tgtKey)) throw readOnlyConflict(ref.targetEntity());
                                throw conflict("La relacion indicada no existe");
                            }
                            if (nonMultMatches.size() > 1) {
                                throw conflict("Existen varias relaciones candidatas; especifica su tipo");
                            }
                            virtualNonMultRelations.remove(nonMultMatches.getFirst());
                        }
                    }
                }
                case UPDATE_RELATIONSHIP -> {
                    RelationshipDefinition update = operation.relationship();
                    String relKind = update.kind();
                    String srcKey = key(update.sourceEntity());
                    String tgtKey = key(update.targetEntity());
                    if (relKind != null && NON_MULTIPLICATIVE_KINDS.contains(relKind)) {
                        VirtualClassifier srcClassifier = classifiers.get(srcKey);
                        VirtualClassifier tgtClassifier = classifiers.get(tgtKey);
                        if (srcClassifier == null || tgtClassifier == null) {
                            throw conflict("La relacion referencia un clasificador inexistente");
                        }
                        validateUmlRelationEndpoints(relKind, srcClassifier.kind, tgtClassifier.kind);
                        List<RelationshipDefinition> nonMultMatches = findMatchingNonMultRelationships(
                                virtualNonMultRelations, update.sourceEntity(), update.targetEntity(), null);
                        if (!nonMultMatches.isEmpty()) {
                            virtualNonMultRelations.remove(nonMultMatches.getFirst());
                            virtualNonMultRelations.add(update);
                        } else {
                            RelationshipDefinition relation = findRelationship(virtualRelationships,
                                    update.sourceEntity(), update.targetEntity(), update.name());
                            virtualRelationships.remove(relation);
                            relationships.remove(relationKey(relation));
                            virtualNonMultRelations.add(update);
                        }
                    } else {
                        if (readOnlyKeys.contains(srcKey)) {
                            throw readOnlyConflict(update.sourceEntity());
                        }
                        if (readOnlyKeys.contains(tgtKey)) {
                            throw readOnlyConflict(update.targetEntity());
                        }
                        DiagramAssociationContext assocMatch = findAssociationByEndpoints(
                                virtualAssociations, update.sourceEntity(), update.targetEntity());
                        List<RelationshipDefinition> multMatches = findMatchingRelationships(
                                virtualRelationships, update.sourceEntity(), update.targetEntity(), update.name(), null);
                        if (multMatches.isEmpty() && assocMatch != null) {
                            // Updating multiplicities of an association class's underlying association
                            if (update.sourceCardinality() == null || update.targetCardinality() == null) {
                                throw conflict("La cardinalidad propuesta no es valida");
                            }
                        } else {
                            RelationshipDefinition relation = findRelationship(virtualRelationships, update.sourceEntity(),
                                    update.targetEntity(), update.name());
                            virtualRelationships.remove(relation);
                            relationships.remove(relationKey(relation));
                            RelationshipDefinition updated = new RelationshipDefinition(
                                    update.sourceEntity(), update.targetEntity(),
                                    update.sourceCardinality(), update.targetCardinality(),
                                    relation.name(), relation.joinTableName(), relation.id(),
                                    relKind != null ? relKind : relation.kind(),
                                    update.sourceMultiplicity(), update.targetMultiplicity(),
                                    update.aggregationEnd());
                            virtualRelationships.add(updated);
                            relationships.add(relationKey(updated));
                        }
                    }
                }
                case CREATE_ASSOCIATION -> {
                    AssociationCreationDefinition association = operation.association();
                    if (readOnlyKeys.contains(key(association.sourceEntity()))) {
                        throw readOnlyConflict(association.sourceEntity());
                    }
                    if (readOnlyKeys.contains(key(association.targetEntity()))) {
                        throw readOnlyConflict(association.targetEntity());
                    }
                    if (!entities.containsKey(key(association.sourceEntity())) || !entities.containsKey(key(association.targetEntity())))
                        throw conflict("La asociacion referencia una entidad inexistente");
                    if (key(association.sourceEntity()).equals(key(association.targetEntity())))
                        throw conflict("Las autorrelaciones no estan habilitadas");
                    if (readOnlyKeys.contains(key(association.associationEntityName()))
                            || entities.containsKey(key(association.associationEntityName()))
                            || classifiers.containsKey(key(association.associationEntityName())))
                        throw conflict("Ya existe una entidad con el nombre asociativo solicitado");
                    Map<String, AttributeDefinition> attributes = new LinkedHashMap<>();
                    VirtualClassifier vc = new VirtualClassifier(association.associationEntityName(), "ASSOCIATION_CLASS", false);
                    AttributeDefinition genId = new AttributeDefinition(
                            "id", com.sw1.backend.ai.diagram.model.DiagramDataType.Integer, true, false);
                    attributes.put("id", genId);
                    vc.attributes.put("id", genId);
                    for (AttributeDefinition attribute : association.attributes()) {
                        if (attribute.primaryKey() || attributes.putIfAbsent(key(attribute.name()), attribute) != null)
                            throw conflict("Los atributos propios de la asociacion no son validos");
                        vc.attributes.put(key(attribute.name()), attribute);
                    }
                    String assocKey = key(association.associationEntityName());
                    entities.put(assocKey, attributes);
                    classifiers.put(assocKey, vc);
                    virtualAssociations.add(new DiagramAssociationContext(
                            association.associationEntityName(),
                            sqlName(technicalName(association.associationEntityName())),
                            List.of(association.sourceEntity(), association.targetEntity()),
                            List.of()));
                }
                case DELETE_ASSOCIATION -> {
                    String assocKey = key(operation.associationEntityName());
                    if (readOnlyKeys.contains(assocKey)) {
                        throw readOnlyConflict(operation.associationEntityName());
                    }
                    boolean exists = virtualAssociations.removeIf(association ->
                            key(association.entityName()).equals(assocKey));
                    classifiers.remove(assocKey);
                    if (!exists || entities.remove(assocKey) == null)
                        throw conflict("La entidad asociativa a eliminar no existe");
                }
                case CONVERT_MANY_TO_MANY_ASSOCIATION -> {
                    AssociationConversionDefinition conversion = operation.conversion();
                    if (readOnlyKeys.contains(key(conversion.sourceEntity()))) {
                        throw readOnlyConflict(conversion.sourceEntity());
                    }
                    if (readOnlyKeys.contains(key(conversion.targetEntity()))) {
                        throw readOnlyConflict(conversion.targetEntity());
                    }
                    RelationshipDefinition relation = relationshipsById.get(conversion.relationshipId());
                    if (relation == null) throw conflict("La relacion indicada no existe o ya fue convertida");
                    Set<String> expected = Set.of(key(conversion.sourceEntity()), key(conversion.targetEntity()));
                    Set<String> actual = Set.of(key(relation.sourceEntity()), key(relation.targetEntity()));
                    if (!actual.equals(expected)) throw conflict("Los extremos no coinciden con la relacion indicada");
                    if (!many(relation.sourceCardinality()) || !many(relation.targetCardinality()))
                        throw conflict("La relacion indicada no es muchos a muchos");
                    long candidates = context.relationships().stream()
                            .filter(candidate -> Set.of(key(candidate.sourceEntity()), key(candidate.targetEntity())).equals(actual))
                            .filter(candidate -> many(candidate.sourceCardinality()) && many(candidate.targetCardinality()))
                            .count();
                    if (candidates > 1) throw conflict("Existen varias relaciones N:M candidatas; selecciona o identifica una relacion concreta");
                    if (readOnlyKeys.contains(key(conversion.associationEntityName()))
                            || entities.containsKey(key(conversion.associationEntityName()))
                            || classifiers.containsKey(key(conversion.associationEntityName())))
                        throw conflict("Ya existe una entidad con el nombre asociativo solicitado");
                    boolean alreadyConverted = virtualAssociations.stream().anyMatch(association ->
                            association.endpointEntityNames().stream().map(DiagramContextMapper::key).collect(java.util.stream.Collectors.toSet())
                                    .equals(actual));
                    if (alreadyConverted) throw conflict("La relacion ya fue convertida en una entidad asociativa");
                    Set<String> attributeNames = new HashSet<>();
                    Map<String, AttributeDefinition> associationAttributes = new LinkedHashMap<>();
                    VirtualClassifier vc = new VirtualClassifier(conversion.associationEntityName(), "ASSOCIATION_CLASS", false);
                    AttributeDefinition genId = new AttributeDefinition(
                            "id", com.sw1.backend.ai.diagram.model.DiagramDataType.Integer, true, false);
                    associationAttributes.put("id", genId);
                    vc.attributes.put("id", genId);
                    for (AttributeDefinition attribute : conversion.attributes()) {
                        if (attribute.primaryKey()) throw conflict("Los atributos propios no pueden reemplazar la PK generada");
                        if (!attributeNames.add(key(attribute.name())))
                            throw conflict("La conversion contiene atributos propios duplicados");
                        associationAttributes.put(key(attribute.name()), attribute);
                        vc.attributes.put(key(attribute.name()), attribute);
                    }
                    String assocKey = key(conversion.associationEntityName());
                    entities.put(assocKey, associationAttributes);
                    classifiers.put(assocKey, vc);
                    virtualAssociations.add(new DiagramAssociationContext(
                            conversion.associationEntityName(),
                            sqlName(technicalName(conversion.associationEntityName())),
                            List.of(conversion.sourceEntity(), conversion.targetEntity()),
                            List.of()));
                    relationshipsById.remove(conversion.relationshipId());
                }
            }
            result.add(operation);
        }
        return new DiagramInterpretResponse(List.copyOf(result));
    }

    private static VirtualClassifier requireMethodBearingClassifier(
            Map<String, VirtualClassifier> classifiers, String entityName) {
        VirtualClassifier vc = classifiers.get(key(entityName));
        if (vc == null) {
            throw conflict("El clasificador destino \"" + entityName + "\" no existe");
        }
        if ("ENUMERATION".equals(vc.kind)) {
            throw conflict("La enumeracion \"" + vc.name + "\" no admite metodos");
        }
        return vc;
    }

    private static VirtualClassifier requireEnumerationClassifier(
            Map<String, VirtualClassifier> classifiers, String entityName) {
        VirtualClassifier vc = classifiers.get(key(entityName));
        if (vc == null) {
            throw conflict("La enumeracion destino \"" + entityName + "\" no existe");
        }
        if (!"ENUMERATION".equals(vc.kind)) {
            throw conflict("Solo una enumeracion admite literales; \"" + vc.name + "\" es de tipo " + vc.kind);
        }
        return vc;
    }

    private static void validateAndAddMethod(VirtualClassifier vc, MethodDefinition method) {
        validateMethodConstraints(vc, method, -1);
        vc.methods.add(method);
    }

    private static void validateMethodConstraints(
            VirtualClassifier vc, MethodDefinition method, int ignoreIndex) {
        if (Boolean.TRUE.equals(method.isStatic()) && Boolean.TRUE.equals(method.isAbstract())) {
            throw conflict("El metodo \"" + method.name() + "\" no puede ser static y abstract simultaneamente");
        }
        List<ParameterDefinition> params = method.parameters() == null ? List.of() : method.parameters();
        Set<String> paramNames = new HashSet<>();
        for (ParameterDefinition param : params) {
            if (!paramNames.add(key(param.name()))) {
                throw conflict("El metodo \"" + method.name() + "\" contiene parametros con nombres duplicados: " + param.name());
            }
        }
        List<String> sig = methodSignatureTypes(method);
        for (int i = 0; i < vc.methods.size(); i++) {
            if (i == ignoreIndex) continue;
            MethodDefinition existing = vc.methods.get(i);
            if (key(existing.name()).equals(key(method.name()))
                    && methodSignatureTypes(existing).equals(sig)) {
                throw conflict("Ya existe un metodo \"" + method.name() + "\" con la misma firma de parametros en \"" + vc.name + "\"");
            }
        }
    }

    private static int findTargetMethodIndex(
            VirtualClassifier vc,
            String methodName,
            List<String> parameterTypes,
            String targetParameterName) {
        List<Integer> nameMatches = new ArrayList<>();
        for (int i = 0; i < vc.methods.size(); i++) {
            if (key(vc.methods.get(i).name()).equals(key(methodName))) {
                nameMatches.add(i);
            }
        }
        if (nameMatches.isEmpty()) {
            throw conflict("El metodo \"" + methodName + "\" no existe en \"" + vc.name + "\"");
        }
        if (parameterTypes != null) {
            List<String> expectedSig = parameterTypes.stream().map(DiagramContextMapper::key).toList();
            for (int idx : nameMatches) {
                if (methodSignatureTypes(vc.methods.get(idx)).equals(expectedSig)) {
                    return idx;
                }
            }
            throw conflict("No existe una sobrecarga de \"" + methodName + "\" con los parametros indicados en \"" + vc.name + "\"");
        }
        if (nameMatches.size() == 1) {
            return nameMatches.getFirst();
        }
        if (targetParameterName != null) {
            List<Integer> paramMatches = new ArrayList<>();
            for (int idx : nameMatches) {
                List<ParameterDefinition> params = vc.methods.get(idx).parameters();
                if (params != null && params.stream().anyMatch(p -> key(p.name()).equals(key(targetParameterName)))) {
                    paramMatches.add(idx);
                }
            }
            if (paramMatches.size() == 1) {
                return paramMatches.getFirst();
            }
        }
        throw conflict("Existen varios metodos sobrecargados \"" + methodName + "\" en \"" + vc.name + "\"; especifica sus tipos de parametros");
    }

    private static List<String> methodSignatureTypes(MethodDefinition method) {
        if (method.parameters() == null || method.parameters().isEmpty()) {
            return List.of();
        }
        return method.parameters().stream()
                .map(p -> key(p.type() == null ? "String" : p.type()))
                .toList();
    }

    private static void validateUmlRelationEndpoints(String relKind, String sourceKind, String targetKind) {
        boolean srcClass = isClassLike(sourceKind);
        boolean tgtClass = isClassLike(targetKind);
        if ("REALIZATION".equals(relKind) && (!srcClass || !"INTERFACE".equals(targetKind))) {
            throw conflict("Una realizacion debe partir de una clase o entidad y terminar en una interfaz");
        }
        if ("GENERALIZATION".equals(relKind)
                && !((srcClass && tgtClass) || ("INTERFACE".equals(sourceKind) && "INTERFACE".equals(targetKind)))) {
            throw conflict("Una generalizacion debe conectar clases entre si o interfaces entre si");
        }
    }

    private static boolean isClassLike(String kind) {
        return "ENTITY".equals(kind) || "CLASS".equals(kind) || "ASSOCIATION_CLASS".equals(kind);
    }

    private static void verifyNotStructuralForeignKeyCollision(
            List<DiagramAssociationContext> associations, String entityName, String attributeName) {
        DiagramAssociationContext assoc = findAssociationByName(associations, entityName);
        if (assoc == null) return;
        Set<String> fkKeys = structuralFkKeys(assoc);
        String candidateSql = key(sqlName(technicalName(attributeName)));
        if (fkKeys.contains(candidateSql) || fkKeys.contains(key(attributeName))) {
            throw conflict("El atributo \"" + attributeName
                    + "\" colisiona con una clave foranea estructural derivada de la clase asociativa \"" + assoc.entityName() + "\"");
        }
    }

    private static void verifyNotStructuralAttributeMutation(
            List<DiagramAssociationContext> associations,
            String entityName,
            String attributeName,
            boolean disallowGeneratedPk) {
        DiagramAssociationContext assoc = findAssociationByName(associations, entityName);
        if (assoc == null) return;
        Set<String> fkKeys = structuralFkKeys(assoc);
        String attrKey = key(attributeName);
        String attrSql = key(sqlName(technicalName(attributeName)));
        if (fkKeys.contains(attrKey) || fkKeys.contains(attrSql)) {
            throw conflict("El atributo \"" + attributeName
                    + "\" es una clave foranea estructural derivada de la clase asociativa \""
                    + assoc.entityName() + "\" y no puede modificarse ni eliminarse como atributo independiente");
        }
        if (disallowGeneratedPk && "id".equals(attrKey)) {
            throw conflict("El atributo \"id\" es la clave primaria estructural de la clase asociativa \""
                    + assoc.entityName() + "\" y no puede eliminarse como atributo independiente");
        }
    }

    private static void verifyNotStructuralAssociationEdgeDeletion(
            List<DiagramAssociationContext> associations,
            String sourceEntity,
            String targetEntity) {
        String srcKey = key(sourceEntity);
        String tgtKey = key(targetEntity);
        for (DiagramAssociationContext assoc : associations) {
            String assocKey = key(assoc.entityName());
            Set<String> endpointKeys = new HashSet<>();
            for (String ep : assoc.endpointEntityNames()) {
                endpointKeys.add(key(ep));
            }
            if ((srcKey.equals(assocKey) && endpointKeys.contains(tgtKey))
                    || (tgtKey.equals(assocKey) && endpointKeys.contains(srcKey))) {
                throw conflict("No se puede eliminar una arista estructural individual de la clase asociativa \""
                        + assoc.entityName() + "\". Para eliminar la asociacion completa, elimina la clase asociativa \""
                        + assoc.entityName() + "\"");
            }
        }
    }

    private static DiagramAssociationContext findAssociationByName(
            List<DiagramAssociationContext> associations, String entityName) {
        String targetKey = key(entityName);
        for (DiagramAssociationContext assoc : associations) {
            if (key(assoc.entityName()).equals(targetKey)) {
                return assoc;
            }
        }
        return null;
    }

    private static DiagramAssociationContext findAssociationByEndpoints(
            List<DiagramAssociationContext> associations, String source, String target) {
        Set<String> pair = Set.of(key(source), key(target));
        for (DiagramAssociationContext assoc : associations) {
            Set<String> endpoints = new HashSet<>();
            for (String ep : assoc.endpointEntityNames()) {
                endpoints.add(key(ep));
            }
            if (endpoints.equals(pair)) {
                return assoc;
            }
        }
        return null;
    }

    private static Set<String> structuralFkKeys(DiagramAssociationContext assoc) {
        Set<String> keys = new HashSet<>();
        for (String ep : assoc.endpointEntityNames()) {
            String baseSql = key(sqlName(technicalName(ep) + "Id"));
            keys.add(baseSql);
            keys.add("fk_" + baseSql);
            keys.add(key(technicalName(ep) + "Id"));
        }
        return keys;
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

    private static List<String> relationKey(RelationshipDefinition relation) {
        String source = key(relation.sourceEntity()), target = key(relation.targetEntity());
        if (relation.sourceCardinality() == null || relation.targetCardinality() == null)
            throw conflict("La cardinalidad no es valida");
        String name = relation.name() == null ? "" : key(relation.name());
        String kind = relation.kind() == null ? "ASSOCIATION" : relation.kind();
        return source.compareTo(target) <= 0
                ? List.of(source, relation.sourceCardinality().name(), target, relation.targetCardinality().name(), name, kind)
                : List.of(target, relation.targetCardinality().name(), source, relation.sourceCardinality().name(), name, kind);
    }

    private static boolean many(DiagramCardinality cardinality) {
        return cardinality == DiagramCardinality.ZERO_MANY
                || cardinality == DiagramCardinality.ONE_MANY;
    }

    private static Map<String, AttributeDefinition> requiredAttributes(
            Map<String, Map<String, AttributeDefinition>> entities,
            Set<String> readOnlyKeys,
            String entityName) {
        if (readOnlyKeys.contains(key(entityName))) {
            throw readOnlyConflict(entityName);
        }
        Map<String, AttributeDefinition> attributes = entities.get(key(entityName));
        if (attributes == null) throw conflict("La entidad destino no existe");
        return attributes;
    }

    private static List<RelationshipDefinition> findMatchingRelationships(
            List<RelationshipDefinition> relationships,
            String source,
            String target,
            String name,
            String kind) {
        Set<String> endpoints = Set.of(key(source), key(target));
        return relationships.stream()
                .filter(relation -> Set.of(key(relation.sourceEntity()), key(relation.targetEntity())).equals(endpoints))
                .filter(relation -> name == null || Objects.equals(
                        relation.name() == null ? "" : key(relation.name()), key(name)))
                .filter(relation -> kind == null || kind.equalsIgnoreCase(
                        relation.kind() == null ? "ASSOCIATION" : relation.kind()))
                .toList();
    }

    private static List<RelationshipDefinition> findMatchingNonMultRelationships(
            List<RelationshipDefinition> relationships,
            String source,
            String target,
            String kind) {
        Set<String> endpoints = Set.of(key(source), key(target));
        return relationships.stream()
                .filter(relation -> Set.of(key(relation.sourceEntity()), key(relation.targetEntity())).equals(endpoints))
                .filter(relation -> kind == null || kind.equalsIgnoreCase(relation.kind()))
                .toList();
    }

    private static RelationshipDefinition findNonMultiplicativeRelationship(
            List<RelationshipDefinition> relationships,
            String source,
            String target,
            String kind) {
        List<RelationshipDefinition> matches = findMatchingNonMultRelationships(relationships, source, target, kind);
        if (matches.isEmpty()) throw conflict("La relacion indicada no existe");
        if (matches.size() > 1) throw conflict("Existen varias relaciones candidatas; especifica cual deseas modificar");
        return matches.getFirst();
    }

    private static RelationshipDefinition findRelationship(
            List<RelationshipDefinition> relationships,
            String source,
            String target,
            String name) {
        List<RelationshipDefinition> matches = findMatchingRelationships(relationships, source, target, name, null);
        if (matches.isEmpty()) throw conflict("La relacion indicada no existe");
        if (matches.size() > 1) throw conflict("Existen varias relaciones candidatas; especifica su nombre");
        return matches.getFirst();
    }

    private static AiServiceException readOnlyConflict(String name) {
        return new AiServiceException(
                HttpStatus.CONFLICT,
                ErrorCategory.OPERATION_VALIDATION_ERROR,
                "El elemento UML \"" + name + "\" es de solo consulta y no admite modificaciones estructurales de IA");
    }

    private static AiServiceException conflict(String message) {
        return new AiServiceException(HttpStatus.CONFLICT, ErrorCategory.OPERATION_VALIDATION_ERROR, message);
    }
}
