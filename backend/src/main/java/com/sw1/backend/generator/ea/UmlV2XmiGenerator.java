package com.sw1.backend.generator.ea;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.*;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

/** Standards-oriented UML 2.1 projection for LogicDraft document v2. */
final class UmlV2XmiGenerator {
    private static final String XMI = "http://schema.omg.org/spec/XMI/2.1";
    private static final String UML = "http://schema.omg.org/spec/UML/2.1";
    private static final String DIAGRAM_STYLE = "ShowPrivate=1;ShowProtected=1;ShowPublic=1;HideRelationships=0;ConnectorNotation=UML 2.1;Zoom=100;";

    private record AssociationClassPlan(String nodeId, String connectorId, String sourceNodeId,
            String targetNodeId, String sourceEdgeId, String targetEdgeId,
            String sourceMultiplicity, String targetMultiplicity) {}

    private UmlV2XmiGenerator() {}

    static byte[] generate(String projectName, Map<String, Object> content) {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            Document xml = factory.newDocumentBuilder().newDocument();
            Element root = xml.createElementNS(XMI, "xmi:XMI");
            root.setAttributeNS(XMI, "xmi:version", "2.1");
            root.setAttributeNS(XMLConstants.XMLNS_ATTRIBUTE_NS_URI, "xmlns:uml", UML);
            xml.appendChild(root);
            Element documentation = xml.createElementNS(XMI, "xmi:Documentation");
            documentation.setAttribute("exporter", "Enterprise Architect");
            documentation.setAttribute("exporterVersion", "6.5");
            root.appendChild(documentation);
            Element model = xml.createElementNS(UML, "uml:Model");
            xmi(model, "type", "uml:Model"); xmi(model, "id", stable("model", projectName));
            model.setAttribute("name", projectName); root.appendChild(model);
            String packageId = stablePackage(projectName);
            Element umlPackage = add(xml, model, "packagedElement");
            xmi(umlPackage, "type", "uml:Package"); xmi(umlPackage, "id", packageId);
            umlPackage.setAttribute("name", projectName);

            List<?> nodes = list(content.get("nodes"), "nodes");
            List<?> edges = list(content.get("edges"), "edges");
            Map<String, String> xmiIds = new LinkedHashMap<>();
            Map<String, String> typeIds = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
            Map<String, Element> elements = new LinkedHashMap<>();
            Map<String, Map<?, ?>> dataByNode = new LinkedHashMap<>();
            for (Object raw : nodes) {
                Map<?, ?> node = map(raw, "nodo");
                String nodeId = text(node.get("id"), "ID de nodo");
                Map<?, ?> data = map(node.get("data"), "datos de " + nodeId);
                if (dataByNode.putIfAbsent(nodeId, data) != null) {
                    throw new EnterpriseArchitectXmiException("Hay IDs de nodo duplicados en el diagrama.");
                }
                xmiIds.put(nodeId, externalId(data, nodeId));
                typeIds.put(text(data.get("name"), "nombre de nodo"), xmiIds.get(nodeId));
            }
            for (Object raw : nodes) {
                Map<?, ?> node = map(raw, "nodo");
                String nodeId = text(node.get("id"), "ID de nodo");
                Map<?, ?> data = map(node.get("data"), "datos de " + nodeId);
                String name = text(data.get("name"), "nombre de nodo");
                String kind = data.get("kind") == null ? "ENTITY" : text(data.get("kind"), "tipo UML");
                String umlType = data.get("association") instanceof Map<?, ?> ? "uml:AssociationClass" : switch (kind) {
                    case "ENTITY", "CLASS" -> "uml:Class";
                    case "ASSOCIATION_CLASS" -> "uml:AssociationClass";
                    case "INTERFACE" -> "uml:Interface";
                    case "ENUMERATION" -> "uml:Enumeration";
                    default -> throw new EnterpriseArchitectXmiException("El nodo '" + name + "' usa un tipo UML no exportable: " + kind + ".");
                };
                String xmiId = xmiIds.get(nodeId);
                Element element = add(xml, umlPackage, "packagedElement");
                xmi(element, "type", umlType); xmi(element, "id", xmiId);
                element.setAttribute("name", name);
                element.setAttribute("visibility", "public");
                if (Boolean.TRUE.equals(data.get("abstract"))) element.setAttribute("isAbstract", "true");
                exportAttributes(xml, element, data, kind, typeIds);
                exportMethods(xml, element, data, typeIds);
                if ("ENUMERATION".equals(kind) && data.get("literals") != null) {
                    for (Object literal : list(data.get("literals"), "literales de " + name)) {
                        String value = text(literal, "literal");
                        Element owned = add(xml, element, "ownedLiteral");
                        xmi(owned, "type", "uml:EnumerationLiteral"); xmi(owned, "id", stable(xmiId, "literal:" + value));
                        owned.setAttribute("name", value);
                    }
                }
                elements.put(nodeId, element);
            }

            Map<String, AssociationClassPlan> associationClasses = associationClassPlans(
                    nodes, edges, xmiIds, dataByNode);
            Set<String> associationEdges = new HashSet<>();
            for (AssociationClassPlan plan : associationClasses.values()) {
                associationEdges.add(plan.sourceEdgeId());
                associationEdges.add(plan.targetEdgeId());
                Element associationClass = elements.get(plan.nodeId());
                String sourceEnd = stable(plan.connectorId(), "source");
                String targetEnd = stable(plan.connectorId(), "target");
                Element sourceMember = add(xml, associationClass, "memberEnd");
                xmi(sourceMember, "idref", sourceEnd);
                Element targetMember = add(xml, associationClass, "memberEnd");
                xmi(targetMember, "idref", targetEnd);
                ownedEnd(xml, associationClass, sourceEnd, xmiIds.get(plan.sourceNodeId()),
                        plan.sourceMultiplicity(), "none");
                ownedEnd(xml, associationClass, targetEnd, xmiIds.get(plan.targetNodeId()),
                        plan.targetMultiplicity(), "none");
            }

            for (Object raw : edges) {
                Map<?, ?> edge = map(raw, "relacion");
                String edgeId = text(edge.get("id"), "ID de relacion");
                if (associationEdges.contains(edgeId)) continue;
                String source = text(edge.get("source"), "origen");
                String target = text(edge.get("target"), "destino");
                if (!xmiIds.containsKey(source) || !xmiIds.containsKey(target))
                    throw new EnterpriseArchitectXmiException("La relacion '" + edgeId + "' referencia un nodo inexistente.");
                Map<?, ?> data = edge.get("data") == null ? Map.of() : map(edge.get("data"), "datos de relacion");
                String kind = data.get("relationKind") == null ? "ASSOCIATION" : text(data.get("relationKind"), "tipo de relacion");
                String relationXmiId = externalId(data, edgeId);
                switch (kind) {
                    case "GENERALIZATION" -> relation(xml, elements.get(source), "generalization", "uml:Generalization", relationXmiId, "general", xmiIds.get(target));
                    case "REALIZATION" -> relation(xml, elements.get(source), "interfaceRealization", "uml:InterfaceRealization", relationXmiId, "supplier", xmiIds.get(target));
                    case "DEPENDENCY" -> dependency(xml, umlPackage, relationXmiId, data, xmiIds.get(source), xmiIds.get(target));
                    case "ASSOCIATION", "AGGREGATION", "COMPOSITION" -> association(xml, umlPackage, relationXmiId, data, xmiIds.get(source), xmiIds.get(target), kind);
                    default -> throw new EnterpriseArchitectXmiException("La relacion '" + edgeId + "' usa un tipo UML no exportable: " + kind + ".");
                }
            }
            addEaDiagramExtension(xml, root, projectName, packageId, content, xmiIds,
                    associationClasses, associationEdges);
            TransformerFactory transformers = TransformerFactory.newInstance();
            transformers.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            var transformer = transformers.newTransformer();
            transformer.setOutputProperty(OutputKeys.ENCODING, "UTF-8"); transformer.setOutputProperty(OutputKeys.INDENT, "yes");
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            transformer.transform(new DOMSource(xml), new StreamResult(out));
            return out.toByteArray();
        } catch (EnterpriseArchitectXmiException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new EnterpriseArchitectXmiException("No se pudo construir el XMI UML v2 con el documento guardado.", exception);
        }
    }

    private static Map<String, AssociationClassPlan> associationClassPlans(List<?> nodes, List<?> edges,
            Map<String, String> xmiIds, Map<String, Map<?, ?>> dataByNode) {
        Map<String, Map<?, ?>> edgesById = new LinkedHashMap<>();
        for (Object raw : edges) {
            Map<?, ?> edge = map(raw, "relacion");
            String id = text(edge.get("id"), "ID de relacion");
            if (edgesById.putIfAbsent(id, edge) != null) {
                throw new EnterpriseArchitectXmiException("Hay IDs de relacion duplicados en el diagrama.");
            }
        }
        Map<String, AssociationClassPlan> result = new LinkedHashMap<>();
        Set<String> consumed = new HashSet<>();
        for (Object raw : nodes) {
            Map<?, ?> node = map(raw, "nodo");
            String nodeId = text(node.get("id"), "ID de nodo");
            Map<?, ?> data = dataByNode.get(nodeId);
            String kind = data.get("kind") == null ? "ENTITY" : text(data.get("kind"), "tipo UML");
            Object associationValue = data.get("association");
            if (associationValue == null) {
                if ("ASSOCIATION_CLASS".equals(kind)) {
                    throw new EnterpriseArchitectXmiException("La clase asociativa '" + data.get("name")
                            + "' no define sus extremos semanticos.");
                }
                continue;
            }
            Map<?, ?> association = map(associationValue, "clase asociativa " + data.get("name"));
            if (!"MANY_TO_MANY_ASSOCIATION".equals(association.get("kind"))
                    || !Boolean.TRUE.equals(association.get("uniquePair"))) {
                throw new EnterpriseArchitectXmiException("La clase asociativa '" + data.get("name")
                        + "' tiene metadatos semanticos invalidos.");
            }
            List<?> endpoints = list(association.get("endpoints"), "extremos de clase asociativa");
            if (endpoints.size() != 2) {
                throw new EnterpriseArchitectXmiException("La clase asociativa debe tener exactamente dos extremos.");
            }
            Map<String, String> endpointNodes = new HashMap<>();
            Map<String, String> endpointEdges = new HashMap<>();
            Map<String, String> endpointMultiplicities = new HashMap<>();
            for (Object endpointRaw : endpoints) {
                Map<?, ?> endpoint = map(endpointRaw, "extremo de clase asociativa");
                String role = text(endpoint.get("role"), "rol asociativo");
                if (!Set.of("SOURCE", "TARGET").contains(role) || endpointNodes.containsKey(role)) {
                    throw new EnterpriseArchitectXmiException("La clase asociativa contiene roles invalidos o duplicados.");
                }
                String endpointNode = text(endpoint.get("entityId"), "clasificador de extremo");
                String edgeId = text(endpoint.get("relationshipId"), "relacion estructural");
                Map<?, ?> edge = edgesById.get(edgeId);
                if (!xmiIds.containsKey(endpointNode) || endpointNode.equals(nodeId) || edge == null
                        || !connects(edge, endpointNode, nodeId) || !consumed.add(edgeId)) {
                    throw new EnterpriseArchitectXmiException("Las relaciones estructurales de la clase asociativa no coinciden.");
                }
                Map<?, ?> edgeData = edge.get("data") == null ? Map.of() : map(edge.get("data"), "datos de relacion");
                endpointNodes.put(role, endpointNode);
                endpointEdges.put(role, edgeId);
                endpointMultiplicities.put(role, multiplicity(edgeData, "targetMultiplicity", "targetCardinality"));
            }
            if (!endpointNodes.keySet().equals(Set.of("SOURCE", "TARGET"))
                    || endpointNodes.get("SOURCE").equals(endpointNodes.get("TARGET"))) {
                throw new EnterpriseArchitectXmiException("La clase asociativa necesita dos extremos distintos.");
            }
            String sourceMultiplicity = associationMultiplicity(association.get("sourceMultiplicity"),
                    endpointMultiplicities.get("SOURCE"));
            String targetMultiplicity = associationMultiplicity(association.get("targetMultiplicity"),
                    endpointMultiplicities.get("TARGET"));
            result.put(nodeId, new AssociationClassPlan(nodeId,
                    externalId(association, nodeId + ":association"), endpointNodes.get("SOURCE"),
                    endpointNodes.get("TARGET"), endpointEdges.get("SOURCE"), endpointEdges.get("TARGET"),
                    sourceMultiplicity, targetMultiplicity));
        }
        return result;
    }

    private static boolean connects(Map<?, ?> edge, String first, String second) {
        return first.equals(edge.get("source")) && second.equals(edge.get("target"))
                || second.equals(edge.get("source")) && first.equals(edge.get("target"));
    }

    private static String associationMultiplicity(Object configured, String fallback) {
        return configured instanceof String value && !value.isBlank() ? cardinalityText(value) : fallback;
    }

    private static void exportAttributes(Document xml, Element owner, Map<?, ?> data, String kind,
                                         Map<String, String> classifiers) {
        if (data.get("attributes") == null) return;
        for (Object raw : list(data.get("attributes"), "atributos")) {
            Map<?, ?> attribute = map(raw, "atributo");
            Element property = add(xml, owner, "ownedAttribute");
            xmi(property, "type", "uml:Property"); xmi(property, "id", externalId(attribute, text(attribute.get("id"), "ID de atributo")));
            property.setAttribute("name", text(attribute.get("name"), "nombre de atributo"));
            property.setAttribute("visibility", visibility(attribute.get("visibility")));
            property.setAttribute("isStatic", String.valueOf(Boolean.TRUE.equals(attribute.get("static"))));
            typeReference(xml, property, text(attribute.get("type"), "tipo de atributo"), classifiers);
            if ("ENTITY".equals(kind) && Boolean.TRUE.equals(attribute.get("primaryKey"))) property.setAttribute("isID", "true");
        }
    }

    private static void exportMethods(Document xml, Element owner, Map<?, ?> data,
                                      Map<String, String> classifiers) {
        if (data.get("methods") == null) return;
        for (Object raw : list(data.get("methods"), "metodos")) {
            Map<?, ?> method = map(raw, "metodo");
            String methodId = externalId(method, text(method.get("id"), "ID de metodo"));
            Element operation = add(xml, owner, "ownedOperation");
            xmi(operation, "type", "uml:Operation"); xmi(operation, "id", methodId);
            operation.setAttribute("name", text(method.get("name"), "nombre de metodo"));
            operation.setAttribute("visibility", visibility(method.get("visibility")));
            operation.setAttribute("isStatic", String.valueOf(Boolean.TRUE.equals(method.get("static"))));
            operation.setAttribute("isAbstract", String.valueOf(Boolean.TRUE.equals(method.get("abstract"))));
            for (Object rawParameter : list(method.get("parameters"), "parametros")) {
                Map<?, ?> parameter = map(rawParameter, "parametro");
                Element owned = add(xml, operation, "ownedParameter");
                xmi(owned, "type", "uml:Parameter"); xmi(owned, "id", externalId(parameter, text(parameter.get("id"), "ID de parametro")));
                owned.setAttribute("name", text(parameter.get("name"), "nombre de parametro"));
                typeReference(xml, owned, text(parameter.get("type"), "tipo de parametro"), classifiers);
                owned.setAttribute("direction", "in");
            }
            Element result = add(xml, operation, "ownedParameter");
            xmi(result, "type", "uml:Parameter"); xmi(result, "id", stable(methodId, "return"));
            result.setAttribute("name", "return"); result.setAttribute("direction", "return");
            typeReference(xml, result, text(method.get("returnType"), "tipo de retorno"), classifiers);
        }
    }

    private static void relation(Document xml, Element owner, String tag, String type, String id, String reference, String target) {
        Element relation = add(xml, owner, tag); xmi(relation, "type", type); xmi(relation, "id", id); relation.setAttribute(reference, target);
    }

    private static void dependency(Document xml, Element model, String id, Map<?, ?> data, String source, String target) {
        Element dependency = add(xml, model, "packagedElement"); xmi(dependency, "type", "uml:Dependency"); xmi(dependency, "id", id);
        dependency.setAttribute("client", source); dependency.setAttribute("supplier", target); optionalName(dependency, data);
    }

    private static void association(Document xml, Element model, String id, Map<?, ?> data, String source, String target, String kind) {
        Element association = add(xml, model, "packagedElement"); xmi(association, "type", "uml:Association"); xmi(association, "id", id); optionalName(association, data);
        String sourceEnd = stable(id, "source"), targetEnd = stable(id, "target");
        Element sourceMember = add(xml, association, "memberEnd"); xmi(sourceMember, "idref", sourceEnd);
        Element targetMember = add(xml, association, "memberEnd"); xmi(targetMember, "idref", targetEnd);
        String aggregate = "AGGREGATION".equals(kind) ? "shared" : "COMPOSITION".equals(kind) ? "composite" : "none";
        boolean aggregateAtTarget = "TARGET".equals(data.get("aggregationEnd"));
        ownedEnd(xml, association, sourceEnd, source, multiplicity(data, "sourceMultiplicity", "sourceCardinality"),
                aggregateAtTarget ? "none" : aggregate);
        ownedEnd(xml, association, targetEnd, target, multiplicity(data, "targetMultiplicity", "targetCardinality"),
                aggregateAtTarget ? aggregate : "none");
    }

    private static void ownedEnd(Document xml, Element association, String id, String type, String multiplicity, String aggregation) {
        Element end = add(xml, association, "ownedEnd"); xmi(end, "type", "uml:Property"); xmi(end, "id", id);
        Element typeReference = add(xml, end, "type"); xmi(typeReference, "idref", type);
        end.setAttribute("association", association.getAttributeNS(XMI, "id")); end.setAttribute("aggregation", aggregation);
        String[] bounds = bounds(multiplicity);
        Element lower = add(xml, end, "lowerValue"); xmi(lower, "type", "uml:LiteralInteger"); xmi(lower, "id", stable(id, "lower")); lower.setAttribute("value", bounds[0]);
        Element upper = add(xml, end, "upperValue"); xmi(upper, "type", "uml:LiteralUnlimitedNatural"); xmi(upper, "id", stable(id, "upper")); upper.setAttribute("value", bounds[1]);
    }

    private static void typeReference(Document xml, Element owner, String declaredType,
                                      Map<String, String> classifiers) {
        Element type = add(xml, owner, "type");
        String classifierId = classifiers.get(declaredType);
        if (classifierId != null) {
            xmi(type, "idref", classifierId);
            return;
        }
        String umlPrimitive = switch (declaredType.toUpperCase(Locale.ROOT)) {
            case "VARCHAR", "TEXT", "STRING", "CHAR" -> "String";
            case "INTEGER", "INT", "SHORT" -> "Integer";
            case "BIGINT", "LONG" -> "UnlimitedNatural";
            case "DECIMAL", "DOUBLE", "FLOAT", "REAL", "NUMERIC" -> "Real";
            case "BOOLEAN", "BOOL" -> "Boolean";
            case "VOID" -> "Void";
            default -> null;
        };
        if (umlPrimitive != null) {
            xmi(type, "type", "uml:PrimitiveType");
            type.setAttribute("href", UML + "/uml.xml#" + umlPrimitive);
            return;
        }
        String localPrimitive = switch (declaredType.toUpperCase(Locale.ROOT)) {
            case "DATE" -> "Date";
            case "TIMESTAMP", "DATETIME" -> "DateTime";
            default -> null;
        };
        if (localPrimitive != null) {
            xmi(type, "idref", "EAJava_" + localPrimitive);
            return;
        }
        throw new EnterpriseArchitectXmiException("El tipo UML '" + declaredType
                + "' no referencia un clasificador ni un tipo primitivo soportado.");
    }

    private static void addEaDiagramExtension(Document xml, Element root, String projectName,
                                              String packageId, Map<String, Object> content,
                                               Map<String, String> xmiIds,
                                               Map<String, AssociationClassPlan> associationClasses,
                                               Set<String> associationEdges) {
        Element extension = xml.createElementNS(XMI, "xmi:Extension");
        extension.setAttribute("extender", "Enterprise Architect");
        extension.setAttribute("extenderID", "6.5");
        root.appendChild(extension);

        Element elements = add(xml, extension, "elements");
        Element packageElement = extensionElement(xml, elements, packageId, "uml:Package", projectName);
        Element packageModel = add(xml, packageElement, "model");
        packageModel.setAttribute("package2", packageId.replaceFirst("^EAPK_", "EAID_"));
        packageModel.setAttribute("package", "EAPK_00000000_0000_0000_0000_000000000000");
        packageModel.setAttribute("tpos", "1");
        packageModel.setAttribute("ea_localid", "1");
        Element packageProperties = add(xml, packageElement, "properties");
        packageProperties.setAttribute("sType", "Package");
        packageProperties.setAttribute("scope", "public");
        eaElementMetadata(xml, packageElement, projectName);
        add(xml, packageElement, "packageproperties").setAttribute("version", "1.0");
        add(xml, packageElement, "paths");
        add(xml, packageElement, "times");
        add(xml, packageElement, "flags").setAttribute("iscontrolled", "FALSE");
        List<?> nodes = list(content.get("nodes"), "nodes");
        Map<String, String> duids = diagramObjectIds(nodes);
        Map<String, Integer> localIds = new HashMap<>();
        Map<String, Element> extensionItems = new HashMap<>();
        int localId = 2;
        for (Object raw : nodes) {
            Map<?, ?> node = map(raw, "nodo");
            String nodeId = text(node.get("id"), "ID de nodo");
            Map<?, ?> data = map(node.get("data"), "datos de " + nodeId);
            String kind = data.get("kind") == null ? "ENTITY" : text(data.get("kind"), "tipo UML");
            boolean associationClass = associationClasses.containsKey(nodeId);
            Element item = extensionElement(xml, elements, xmiIds.get(nodeId),
                    associationClass ? "uml:Class" : eaUmlType(kind),
                    text(data.get("name"), "nombre de nodo"));
            extensionItems.put(nodeId, item);
            Element model = add(xml, item, "model");
            model.setAttribute("package", packageId);
            localIds.put(nodeId, localId);
            model.setAttribute("ea_localid", String.valueOf(localId++));
            model.setAttribute("ea_eleType", "element");
            Element properties = add(xml, item, "properties");
            properties.setAttribute("sType", eaElementType(kind));
            properties.setAttribute("nType", associationClass ? "17" : "0");
            properties.setAttribute("scope", "public");
            properties.setAttribute("isAbstract", String.valueOf(Boolean.TRUE.equals(data.get("abstract"))));
            eaElementMetadata(xml, item, projectName);
            Element tags = child(item, "tags");
            Element kindTag = add(xml, tags, "tag");
            kindTag.setAttribute("name", "LogicDraft.Kind");
            kindTag.setAttribute("value", associationClass ? "ASSOCIATION_CLASS" : kind);
            if (associationClass) {
                child(item, "extendedProperties").setAttribute("conID",
                        associationClasses.get(nodeId).connectorId());
            }
        }
        for (Object raw : list(content.get("edges"), "edges")) {
            Map<?, ?> edge = map(raw, "relacion");
            String edgeId = text(edge.get("id"), "ID de relacion");
            if (associationEdges.contains(edgeId)) continue;
            String sourceNodeId = text(edge.get("source"), "origen");
            String targetNodeId = text(edge.get("target"), "destino");
            Map<?, ?> data = edge.get("data") == null ? Map.of() : map(edge.get("data"), "datos de relacion");
            String kind = data.get("relationKind") == null ? "ASSOCIATION"
                    : text(data.get("relationKind"), "tipo de relacion");
            String connectorId = externalId(data, edgeId);
            appendEaLink(xml, extensionItems.get(sourceNodeId), eaConnectorType(kind), connectorId,
                    xmiIds.get(sourceNodeId), xmiIds.get(targetNodeId));
            appendEaLink(xml, extensionItems.get(targetNodeId), eaConnectorType(kind), connectorId,
                    xmiIds.get(sourceNodeId), xmiIds.get(targetNodeId));
        }
        for (AssociationClassPlan plan : associationClasses.values()) {
            appendEaLink(xml, extensionItems.get(plan.sourceNodeId()), "Association", plan.connectorId(),
                    xmiIds.get(plan.sourceNodeId()), xmiIds.get(plan.targetNodeId()));
            appendEaLink(xml, extensionItems.get(plan.targetNodeId()), "Association", plan.connectorId(),
                    xmiIds.get(plan.sourceNodeId()), xmiIds.get(plan.targetNodeId()));
        }

        Element connectors = add(xml, extension, "connectors");
        for (Object raw : list(content.get("edges"), "edges")) {
            Map<?, ?> edge = map(raw, "relacion");
            String edgeId = text(edge.get("id"), "ID de relacion");
            if (associationEdges.contains(edgeId)) continue;
            Map<?, ?> data = edge.get("data") == null ? Map.of() : map(edge.get("data"), "datos de relacion");
            String kind = data.get("relationKind") == null ? "ASSOCIATION" : text(data.get("relationKind"), "tipo de relacion");
            Element connector = add(xml, connectors, "connector");
            xmi(connector, "idref", externalId(data, edgeId));
            String aggregationEnd = data.get("aggregationEnd") instanceof String value ? value : "SOURCE";
            String aggregate = "AGGREGATION".equals(kind) ? "shared"
                    : "COMPOSITION".equals(kind) ? "composite" : "none";
            connectorEnd(xml, connector, "source", xmiIds.get(text(edge.get("source"), "origen")),
                    "TARGET".equals(aggregationEnd) ? "none" : aggregate);
            connectorEnd(xml, connector, "target", xmiIds.get(text(edge.get("target"), "destino")),
                    "TARGET".equals(aggregationEnd) ? aggregate : "none");
            Element properties = add(xml, connector, "properties");
            properties.setAttribute("ea_type", eaConnectorType(kind));
            if ("COMPOSITION".equals(kind)) properties.setAttribute("subtype", "Strong");
            properties.setAttribute("direction", "Source -> Destination");
            Element labels = add(xml, connector, "labels");
            labels.setAttribute("lt", "+" + multiplicity(multiplicity(data,
                    "sourceMultiplicity", "sourceCardinality")));
            labels.setAttribute("rt", "+" + multiplicity(multiplicity(data,
                    "targetMultiplicity", "targetCardinality")));
            add(xml, connector, "extendedProperties").setAttribute("virtualInheritance", "0");
        }
        for (AssociationClassPlan plan : associationClasses.values()) {
            Element connector = add(xml, connectors, "connector");
            xmi(connector, "idref", plan.connectorId());
            connectorEnd(xml, connector, "source", xmiIds.get(plan.sourceNodeId()), "none");
            connectorEnd(xml, connector, "target", xmiIds.get(plan.targetNodeId()), "none");
            add(xml, connector, "model").setAttribute("ea_localid", String.valueOf(localId++));
            Element properties = add(xml, connector, "properties");
            properties.setAttribute("ea_type", "Association");
            properties.setAttribute("subtype", "Class");
            properties.setAttribute("direction", "Unspecified");
            Element modifiers = add(xml, connector, "modifiers");
            modifiers.setAttribute("isRoot", "false");
            modifiers.setAttribute("isLeaf", "false");
            add(xml, connector, "parameterSubstitutions");
            add(xml, connector, "documentation");
            Element appearance = add(xml, connector, "appearance");
            appearance.setAttribute("linemode", "3");
            appearance.setAttribute("linecolor", "-1");
            appearance.setAttribute("linewidth", "0");
            appearance.setAttribute("seqno", "0");
            appearance.setAttribute("headStyle", "0");
            appearance.setAttribute("lineStyle", "0");
            Element labels = add(xml, connector, "labels");
            labels.setAttribute("lt", "+" + multiplicity(plan.sourceMultiplicity()));
            labels.setAttribute("rt", "+" + multiplicity(plan.targetMultiplicity()));
            Element extended = add(xml, connector, "extendedProperties");
            extended.setAttribute("virtualInheritance", "0");
            extended.setAttribute("associationclass", xmiIds.get(plan.nodeId()));
            extended.setAttribute("privatedata1", String.valueOf(localIds.get(plan.nodeId())));
            add(xml, connector, "style");
            add(xml, connector, "xrefs");
            add(xml, connector, "tags");
        }

        Element primitives = add(xml, extension, "primitivetypes");
        Element primitivePackage = add(xml, primitives, "packagedElement");
        xmi(primitivePackage, "type", "uml:Package"); xmi(primitivePackage, "id", "EAJavaTypesPackage");
        primitivePackage.setAttribute("name", "EA_Java_Types_Package");
        for (String primitiveName : List.of("Date", "DateTime")) {
            Element primitive = add(xml, primitivePackage, "packagedElement");
            xmi(primitive, "type", "uml:PrimitiveType"); xmi(primitive, "id", "EAJava_" + primitiveName);
            primitive.setAttribute("name", primitiveName);
        }
        add(xml, extension, "profiles");

        Element diagrams = add(xml, extension, "diagrams");
        Element diagram = add(xml, diagrams, "diagram");
        xmi(diagram, "id", stable("diagram", projectName));
        Element diagramModel = add(xml, diagram, "model");
        diagramModel.setAttribute("package", packageId); diagramModel.setAttribute("owner", packageId);
        diagramModel.setAttribute("localID", "1");
        Element properties = add(xml, diagram, "properties");
        properties.setAttribute("name", diagramName(content)); 
        properties.setAttribute("type", "Logical");
        Element project = add(xml, diagram, "project");
        project.setAttribute("author", "LogicDraft"); project.setAttribute("version", "1.0");
        add(xml, diagram, "style1").setAttribute("value", DIAGRAM_STYLE);
        add(xml, diagram, "style2").setAttribute("value", "TConnectorNotation=UML 2.1;HideElemStereo=0;HideOps=0;HideAtts=0;");
        add(xml, diagram, "swimlanes").setAttribute("value", "locked=false;orientation=0;width=0;inbar=false;names=false;");
        add(xml, diagram, "matrixitems").setAttribute("value", "locked=false;matrixactive=false;swimlanesactive=true;");
        add(xml, diagram, "extendedProperties");
        Element drawings = add(xml, diagram, "elements");
        int minX = nodes.stream().map(UmlV2XmiGenerator::mapNode).mapToInt(node -> coordinate(map(node.get("position"), "posicion").get("x"))).min().orElse(0);
        int minY = nodes.stream().map(UmlV2XmiGenerator::mapNode).mapToInt(node -> coordinate(map(node.get("position"), "posicion").get("y"))).min().orElse(0);
        int sequence = 1;

        for (Object raw : nodes) {
            Map<?, ?> node = map(raw, "nodo");

            String nodeId = text(node.get("id"), "ID de nodo");

            Map<?, ?> data = map(
                node.get("data"),
                "datos de " + nodeId
            );

            Map<?, ?> position = map(
                node.get("position"),
                "posicion de " + nodeId
            );

            int left = 100 + coordinate(position.get("x")) - minX;
            int top = 100 + coordinate(position.get("y")) - minY;

            int width = dimension(data.get("width"), 220);
            int height = dimension(data.get("height"), 120);

            Element drawing = add(xml, drawings, "element");

            drawing.setAttribute("subject", xmiIds.get(nodeId));
            drawing.setAttribute("seqno", String.valueOf(sequence++));

            drawing.setAttribute(
                "geometry",
                "Left=" + left
                    + ";Top=" + top
                    + ";Right=" + (left + width)
                    + ";Bottom=" + (top + height)
                    + ";"
            );

            drawing.setAttribute(
                "style",
                "ImageID=0;DUID=" + duids.get(nodeId) + ";"
            );
        }
        for (Object raw : list(content.get("edges"), "edges")) {
            Map<?, ?> edge = map(raw, "relacion");
            String edgeId = text(edge.get("id"), "ID de relacion");
            if (associationEdges.contains(edgeId)) continue;
            Map<?, ?> data = edge.get("data") == null ? Map.of() : map(edge.get("data"), "datos de relacion");
            String source = text(edge.get("source"), "origen");
            String target = text(edge.get("target"), "destino");
            Element connectorDrawing = add(xml, drawings, "element");
            connectorDrawing.setAttribute("subject", externalId(data, edgeId));
            connectorDrawing.setAttribute("geometry", "SX=0;SY=0;EX=0;EY=0;EDGE=2;$LLB=;LLT=;LMB=;LRT=;LRB=;IRHS=;ILHS=;Path=;");
            connectorDrawing.setAttribute(
                "style",
                "Mode=3;EOID=" + duids.get(target)
                    + ";SOID=" + duids.get(source)
                    + ";Color=-1;LWidth=0;Hidden=0;"
            );
        }
        for (AssociationClassPlan plan : associationClasses.values()) {
            Element connectorDrawing = add(xml, drawings, "element");
            connectorDrawing.setAttribute("subject", plan.connectorId());
            connectorDrawing.setAttribute("geometry",
                    "SX=0;SY=0;EX=0;EY=0;EDGE=2;$LLB=;LLT=;LMB=;LRT=;LRB=;IRHS=;ILHS=;Path=;");
            connectorDrawing.setAttribute("style", "Mode=3;EOID=" + duids.get(plan.targetNodeId())
                    + ";SOID=" + duids.get(plan.sourceNodeId()) + ";Color=-1;LWidth=0;Hidden=0;");
        }
    }
    private static String diagramName(Map<String, Object> content) {
        Object configured = content.get("diagramName");

        return configured instanceof String name && !name.isBlank()
                ? name.trim()
                : "Starter Class Diagram";
    }
    private static Map<String, String> diagramObjectIds(List<?> nodes) {
        Map<String, String> result = new LinkedHashMap<>();
        Set<String> used = new HashSet<>();

        for (Object raw : nodes) {
            Map<?, ?> node = map(raw, "nodo");

            String id = text(node.get("id"), "ID de nodo");

            int nonce = 0;
            String duid;

            do {
                String guid = uuid(
                    "diagram-object",
                    id + ":" + nonce++
                );

                duid = guid.substring(0, 8);

            } while (used.contains(duid));

            used.add(duid);
            result.put(id, duid);
        }

        return result;
    }
    /** Keep EA extension metadata aligned with the established LogicDraft v1 export format. */
    private static void eaElementMetadata(Document xml, Element owner, String packageName) {
        Element project = add(xml, owner, "project");
        project.setAttribute("author", "LogicDraft");
        project.setAttribute("version", "1.0");
        project.setAttribute("phase", "1.0");
        project.setAttribute("complexity", "1");
        project.setAttribute("status", "Proposed");
        add(xml, owner, "code").setAttribute("gentype", "<none>");
        add(xml, owner, "style").setAttribute("appearance",
                "BackColor=-1;BorderColor=-1;BorderWidth=-1;FontColor=-1;VSwimLanes=1;HSwimLanes=1;BorderStyle=0;");
        add(xml, owner, "tags");
        add(xml, owner, "xrefs");
        Element extended = add(xml, owner, "extendedProperties");
        extended.setAttribute("tagged", "0");
        extended.setAttribute("package_name", packageName);
    }

    private static Element extensionElement(Document xml, Element parent, String id, String type, String name) {
        Element element = add(xml, parent, "element");
        xmi(element, "idref", id); xmi(element, "type", type); element.setAttribute("name", name);
        element.setAttribute("scope", "public");
        return element;
    }

    private static void connectorEnd(Document xml, Element connector, String name, String id, String aggregation) {
        Element end = add(xml, connector, name); xmi(end, "idref", id); add(xml, end, "role");
        Element type = add(xml, end, "type");
        type.setAttribute("aggregation", aggregation);
        type.setAttribute("containment", "Unspecified");
    }

    private static void appendEaLink(Document xml, Element item, String type, String connectorId,
            String sourceId, String targetId) {
        if (item == null) return;
        Element links = child(item, "links");
        if (links == null) links = add(xml, item, "links");
        Element link = add(xml, links, type);
        xmi(link, "id", connectorId);
        link.setAttribute("start", sourceId);
        link.setAttribute("end", targetId);
    }

    private static String multiplicity(String value) {
        return switch (value) {
            case "ZERO_ONE" -> "0..1";
            case "ZERO_MANY" -> "0..*";
            case "ONE_MANY" -> "1..*";
            case "0..1", "0..*", "1..*" -> value;
            default -> "1";
        };
    }

    private static String eaUmlType(String kind) {
        return switch (kind) { case "INTERFACE" -> "uml:Interface"; case "ENUMERATION" -> "uml:Enumeration";
            case "ASSOCIATION_CLASS" -> "uml:AssociationClass"; default -> "uml:Class"; };
    }
    private static String eaElementType(String kind) {
        return switch (kind) { case "INTERFACE" -> "Interface"; case "ENUMERATION" -> "Enumeration"; default -> "Class"; };
    }
    private static String eaConnectorType(String kind) {
        return switch (kind) { case "GENERALIZATION" -> "Generalization"; case "REALIZATION" -> "Realisation";
            case "DEPENDENCY" -> "Dependency"; case "AGGREGATION", "COMPOSITION" -> "Aggregation";
            default -> "Association"; };
    }
    private static Map<?, ?> mapNode(Object value) { return map(value, "nodo"); }
    private static int coordinate(Object value) {
        if (!(value instanceof Number number)) throw new EnterpriseArchitectXmiException("Las coordenadas del diagrama no son validas.");
        return (int) Math.round(number.doubleValue());
    }
    private static int dimension(Object value, int fallback) {
        return value instanceof Number number ? Math.max(40, Math.min(1200, (int) Math.round(number.doubleValue()))) : fallback;
    }

    private static String multiplicity(Map<?, ?> data, String direct, String cardinality) {
        if (data.get(direct) instanceof String value && !value.isBlank()) return cardinalityText(value);
        Object cardinalityValue = data.get(cardinality);
        return cardinalityText(cardinalityValue == null ? "ONE_ONE" : String.valueOf(cardinalityValue));
    }
    private static String cardinalityText(String value) {
        return switch (value) {
            case "ONE_ONE", "1", "1..1" -> "1";
            case "ZERO_ONE", "0..1" -> "0..1";
            case "ZERO_MANY", "*", "0..*", "0..N", "0..n" -> "0..*";
            case "ONE_MANY", "1..*", "1..N", "1..n" -> "1..*";
            default -> throw new EnterpriseArchitectXmiException("La multiplicidad UML '" + value + "' no es exportable.");
        };
    }
    private static String[] bounds(String value) {
        return switch (value) { case "0..1" -> new String[]{"0", "1"}; case "*", "0..*", "0..N" -> new String[]{"0", "*"}; case "1..*", "1..N" -> new String[]{"1", "*"}; default -> new String[]{"1", "1"}; };
    }
    private static void optionalName(Element element, Map<?, ?> data) { if (data.get("name") instanceof String name && !name.isBlank()) element.setAttribute("name", name); }
    private static String visibility(Object value) { return value instanceof String text && Set.of("public", "private", "protected", "package").contains(text) ? text : "private"; }
    private static String externalId(Map<?, ?> data, String fallback) {
        Object raw = data.get("externalMetadata");
        if (raw instanceof Map<?, ?> metadata && "enterprise-architect".equals(metadata.get("source")) && metadata.get("externalId") instanceof String id && !id.isBlank()) return id;
        return stable("logicdraft", fallback);
    }
    private static String stable(String scope, String value) { return "EAID_" + uuid(scope, value); }
    private static String stablePackage(String value) { return "EAPK_" + uuid("package", value); }
    private static String uuid(String scope, String value) { return UUID.nameUUIDFromBytes((scope + ":" + value)
            .getBytes(StandardCharsets.UTF_8)).toString().replace('-', '_').toUpperCase(Locale.ROOT); }
    private static String shortId(String id) { return id == null ? "" : id.replaceFirst("^(EAID|EAPK)_", ""); }
    private static Element add(Document xml, Element parent, String tag) { Element child = xml.createElement(tag); parent.appendChild(child); return child; }
    private static Element child(Element parent, String tag) {
        if (parent == null) return null;
        for (var node = parent.getFirstChild(); node != null; node = node.getNextSibling()) {
            if (node instanceof Element element && tag.equals(element.getTagName())) return element;
        }
        return null;
    }
    private static void xmi(Element element, String name, String value) { element.setAttributeNS(XMI, "xmi:" + name, value); }
    private static Map<?, ?> map(Object value, String subject) { if (!(value instanceof Map<?, ?> result)) throw new EnterpriseArchitectXmiException("La estructura de " + subject + " no es valida."); return result; }
    private static List<?> list(Object value, String subject) { if (!(value instanceof List<?> result)) throw new EnterpriseArchitectXmiException("La estructura de " + subject + " no es valida."); return result; }
    private static String text(Object value, String subject) { if (!(value instanceof String result) || result.isBlank()) throw new EnterpriseArchitectXmiException(subject + " no es valido."); return result; }
}
