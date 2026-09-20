package com.sw1.backend.generator.ea;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

/** Reads the EA XMI 2.1 subset supported by LogicDraft; produces a preview, never writes data. */
@Component
public class EnterpriseArchitectXmiReader {
    private static final String XMI = "http://schema.omg.org/spec/XMI/2.1";
    private static final String UML = "http://schema.omg.org/spec/UML/2.1";
    private static final int MAX_BYTES = 5 * 1024 * 1024;
    private record ClassRef(String xmiId, String nodeId, String name, boolean associationClass,
                            Map<String, Object> data) {}
    private record ConnectorRef(String xmiId, String source, String target, String sourceCard,
                                String targetCard, String associationClassId, String name) {}

    public EnterpriseArchitectImportPreview read(byte[] bytes) {
        if (bytes == null || bytes.length == 0) throw badRequest("El archivo XMI está vacío.");
        if (bytes.length > MAX_BYTES) throw badRequest("El archivo XMI supera 5 MB.");
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setXIncludeAware(false);
            factory.setExpandEntityReferences(false);
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
            Document xml = factory.newDocumentBuilder().parse(new ByteArrayInputStream(bytes));
            Element root = xml.getDocumentElement();
            if (!"XMI".equals(root.getLocalName()) || !XMI.equals(root.getNamespaceURI())
                    || !"2.1".equals(root.getAttributeNS(XMI, "version")))
                throw badRequest("El archivo debe utilizar XMI 2.1.");
            Element model = child(root, "Model");
            if (model == null || !UML.equals(model.getNamespaceURI()))
                throw badRequest("El XMI no contiene un modelo UML 2.1.");
            Element pkg = null;
            for (Element item : children(model, "packagedElement")) {
                if ("uml:Package".equals(type(item))) {
                    if (pkg != null) throw badRequest("Hay varios paquetes raíz; exporta un solo paquete.");
                    pkg = item;
                }
            }
            if (pkg == null) throw badRequest("No existe un paquete UML para importar.");
            String projectName = pkg.getAttribute("name").strip();
            String packageId = xid(pkg);
            if (projectName.isEmpty() || projectName.length() > 100 || packageId.isBlank())
                throw badRequest("El paquete no tiene nombre o identificador válido.");

            Element extension = null;
            for (Element item : children(root, "Extension")) {
                if ("Enterprise Architect".equals(item.getAttribute("extender"))) {
                    extension = item;
                    break;
                }
            }
            if (extension == null)
                throw badRequest("Falta la extensión visual de Enterprise Architect; no se importará parcialmente.");
            List<String> warnings = new ArrayList<>();
            Map<String, Map<String, Object>> positions = readPositions(extension, packageId, warnings);
            Map<String, Element> extendedClasses = extensionClasses(extension);
            Map<String, ClassRef> classes = new LinkedHashMap<>();
            Set<String> usedNames = new HashSet<>();
            List<Map<String, Object>> nodes = new ArrayList<>();
            List<Element> umlRelations = new ArrayList<>();
            int fallbackIndex = 0;
            for (Element item : children(pkg, "packagedElement")) {
                String kind = type(item);
                if ("uml:Association".equals(kind)) { umlRelations.add(item); continue; }
                if ("uml:Package".equals(kind))
                    throw badRequest("Hay paquetes anidados. Importa un único paquete que contenga las clases.");
                boolean associationClass = "uml:AssociationClass".equals(kind);
                if (!"uml:Class".equals(kind) && !associationClass)
                    throw badRequest("Elemento UML no compatible: " + kind);
                String name = item.getAttribute("name").strip();
                String elementId = xid(item);
                if (name.isBlank() || name.length() > 100 || elementId.isBlank())
                    throw badRequest("Clase UML sin nombre o ID válido.");
                if (!usedNames.add(name.toLowerCase(Locale.ROOT)) || classes.containsKey(elementId))
                    throw badRequest("Hay nombres o IDs de clases duplicados.");
                String nodeId = stableId("entity-", elementId);
                List<Map<String, Object>> attributes = readAttributes(item, extendedClasses.get(elementId), warnings);
                Map<String, Object> data = new LinkedHashMap<>();
                data.put("id", nodeId);
                data.put("name", name);
                data.put("attributes", attributes);
                ClassRef ref = new ClassRef(elementId, nodeId, name, associationClass, data);
                classes.put(elementId, ref);
                Map<String, Object> position = positions.get(elementId);
                if (position == null) {
                    position = Map.of("x", 180 + (fallbackIndex % 3) * 300,
                                      "y", 180 + (fallbackIndex / 3) * 250);
                    warnings.add("La clase " + name + " no tiene posición; se asignó una posición provisional.");
                }
                fallbackIndex++;
                Map<String, Object> node = new LinkedHashMap<>();
                node.put("id", nodeId);
                node.put("type", "entity");
                node.put("position", position);
                node.put("data", data);
                nodes.add(node);
            }
            if (classes.isEmpty()) throw badRequest("El paquete no contiene clases UML compatibles.");

            // EA may put an ordinary association in UML or an association-class connector
            // inside the extension. We check both, rather than silently dropping either.
            Map<String, Element> umlById = new HashMap<>();
            for (Element relation : umlRelations) {
                if (xid(relation).isBlank() || umlById.putIfAbsent(xid(relation), relation) != null)
                    throw badRequest("Asociación UML con ID ausente o duplicado.");
            }
            List<ConnectorRef> connectors = readConnectors(extension, classes, umlById, warnings);
            Set<String> importedUmlIds = new HashSet<>();
            for (ConnectorRef connector : connectors)
                if (umlById.containsKey(connector.xmiId())) importedUmlIds.add(connector.xmiId());
            if (!importedUmlIds.equals(umlById.keySet()))
                throw badRequest("El XMI contiene asociaciones UML sin conector EA.");
            if (connectors.size() != umlRelations.size()
                    + (int) classes.values().stream().filter(ClassRef::associationClass).count())
                throw badRequest("No coinciden las asociaciones UML y los conectores de EA.");

            List<Map<String, Object>> edges = new ArrayList<>();
            Set<String> usedAssociationClasses = new HashSet<>();
            for (ConnectorRef connector : connectors) {
                ClassRef source = classes.get(connector.source());
                ClassRef target = classes.get(connector.target());
                if (connector.associationClassId() == null) {
                    if (source.associationClass() || target.associationClass())
                        throw badRequest("Un conector de clase asociativa carece de sus metadatos.");
                    edges.add(edge(connector.xmiId(), source.nodeId(), target.nodeId(),
                            connector.sourceCard(), connector.targetCard(), connector.name()));
                    continue;
                }
                ClassRef associative = classes.get(connector.associationClassId());
                if (associative == null || !associative.associationClass() || source == target
                        || source.associationClass() || target.associationClass()
                        || !usedAssociationClasses.add(associative.xmiId()))
                    throw badRequest("Clase de asociación inválida o duplicada.");
                String sourceEdgeId = stableId("edge-", connector.xmiId() + ":SOURCE");
                String targetEdgeId = stableId("edge-", connector.xmiId() + ":TARGET");
                edges.add(edgeWithId(sourceEdgeId, associative.nodeId(), source.nodeId(),
                        "ONE_ONE", connector.sourceCard(), null));
                edges.add(edgeWithId(targetEdgeId, associative.nodeId(), target.nodeId(),
                        "ONE_ONE", connector.targetCard(), null));
                associative.data().put("association", Map.of(
                        "kind", "MANY_TO_MANY_ASSOCIATION",
                        "tableName", physicalName(associative.name(), 55),
                        "uniquePair", true,
                        "endpoints", List.of(
                                Map.of(
                                        "role", "SOURCE",
                                        "entityId", source.nodeId(),
                                        "relationshipId", sourceEdgeId,
                                        "foreignKeyName",
                                        physicalName("fk_source_" + source.name() + "_id", 63)
                                ),
                                Map.of(
                                        "role", "TARGET",
                                        "entityId", target.nodeId(),
                                        "relationshipId", targetEdgeId,
                                        "foreignKeyName",
                                        physicalName("fk_target_" + target.name() + "_id", 63)
                                )
                        )
                ));
            }
            for (ClassRef item : classes.values()) {
                if (item.associationClass() && !usedAssociationClasses.contains(item.xmiId()))
                    throw badRequest("Clase de asociación sin conector: " + item.name());
            }
            return new EnterpriseArchitectImportPreview(projectName, 1, nodes, edges, warnings);
        } catch (ResponseStatusException ex) {
            throw ex;
        } catch (Exception ex) {
            throw badRequest("No se pudo interpretar el XMI: " + ex.getClass().getSimpleName());
        }
    }

    private static List<ConnectorRef> readConnectors(Element extension,
            Map<String, ClassRef> classes, Map<String, Element> umlById, List<String> warnings) {
        Element group = child(extension, "connectors");
        if (group == null) throw badRequest("Falta la sección connectors de EA.");
        List<ConnectorRef> result = new ArrayList<>();
        Set<String> ids = new HashSet<>();
        for (Element connector : children(group, "connector")) {
            String id = xref(connector);
            Element source = child(connector, "source");
            Element target = child(connector, "target");
            Element props = child(connector, "properties");
            if (id.isBlank() || !ids.add(id) || source == null || target == null || props == null
                    || !"Association".equals(props.getAttribute("ea_type")))
                throw badRequest("Conector EA inválido, duplicado o no compatible.");
            String sourceId = xref(source), targetId = xref(target);
            if (!classes.containsKey(sourceId) || !classes.containsKey(targetId) || sourceId.equals(targetId))
                throw badRequest("El conector apunta a clases ausentes o usa autorrelación no compatible.");
            Element extended = child(connector, "extendedProperties");
            String associativeId = extended == null ? "" : extended.getAttribute("associationclass");
            Element uml = umlById.get(id);
            if (associativeId.isEmpty()) {
                if (uml == null) throw badRequest("Conector sin asociación UML: " + id);
            } else {
                if (uml != null || !classes.containsKey(associativeId))
                    throw badRequest("Clase de asociación referenciada incorrectamente.");
            }
            String sourceCard = readCardinality(source);
            String targetCard = readCardinality(target);
            Element labels = child(connector, "labels");
            if (labels != null && (sourceCard == null || targetCard == null)) {
                if (sourceCard == null) sourceCard = parseCardinality(labels.getAttribute("lt"));
                if (targetCard == null) targetCard = parseCardinality(labels.getAttribute("rt"));
            }
            if (sourceCard == null || targetCard == null)
                throw badRequest("Multiplicidad desconocida en el conector " + id);
            String name = uml == null ? null : uml.getAttribute("name");
            if (name != null && (name.isBlank() || name.length() > 100)) name = null;
            result.add(new ConnectorRef(id, sourceId, targetId, sourceCard, targetCard,
                    associativeId.isEmpty() ? null : associativeId, name));
        }
        return result;
    }

    private static String readCardinality(Element end) {
        Element role = child(end, "role");
        return role == null ? null : parseCardinality(role.getAttribute("name"));
    }

    private static String parseCardinality(String text) {
        String value = text.trim().replace("...", "..").replace(" ", "");
        if (value.startsWith("+")) value = value.substring(1);
        return switch (value) {
            case "0..1", "0.. 1" -> "ZERO_ONE";
            case "1", "1..1" -> "ONE_ONE";
            case "0..*", "*" -> "ZERO_MANY";
            case "1..*" -> "ONE_MANY";
            default -> null;
        };
    }

    private static Map<String, Object> edge(String seed, String source, String target,
            String sourceCard, String targetCard, String name) {
        return edgeWithId(stableId("edge-", seed), source, target, sourceCard, targetCard, name);
    }

    private static Map<String, Object> edgeWithId(String id, String source, String target,
            String sourceCard, String targetCard, String name) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("sourceCardinality", sourceCard);
        data.put("targetCardinality", targetCard);
        if (name != null) data.put("name", name);
        Map<String, Object> edge = new LinkedHashMap<>();
        edge.put("id", id);
        edge.put("source", source);
        edge.put("target", target);
        edge.put("data", data);
        return edge;
    }

    private static List<Map<String, Object>> readAttributes(Element klass, Element extended,
            List<String> warnings) {
        Map<String, Element> extraAttributes = new HashMap<>();
        Element extraList = extended == null ? null : child(extended, "attributes");
        if (extraList != null) for (Element attr : children(extraList, "attribute"))
            extraAttributes.put(xref(attr), attr);
        List<Map<String, Object>> attributes = new ArrayList<>();
        Set<String> names = new HashSet<>();
        for (Element prop : children(klass, "ownedAttribute")) {
            String name = prop.getAttribute("name").strip(), id = xid(prop);
            if (name.isBlank() || name.length() > 100 || id.isBlank()
                    || !names.add(name.toLowerCase(Locale.ROOT)))
                throw badRequest("Atributo sin nombre, ID o duplicado en " + klass.getAttribute("name"));
            Element extra = extraAttributes.get(id);
            String eaType = null;
            if (extra != null && child(extra, "properties") != null)
                eaType = child(extra, "properties").getAttribute("type");
            Element type = child(prop, "type");
            String ref = type == null ? "" : type.getAttributeNS(XMI, "idref");
            String href = type == null ? "" : type.getAttribute("href");
            String dataType = toLogicDraftType(eaType, ref, href);
            if (dataType == null) {
                warnings.add("Tipo desconocido en " + klass.getAttribute("name") + "." + name
                        + "; se propone VARCHAR. Revisa antes de guardar.");
                dataType = "VARCHAR";
            }
            Element lower = child(prop, "lowerValue");
            boolean nullable = lower != null && "0".equals(lower.getAttribute("value"));
            boolean primaryKey = false;
            if (extra != null) {
                Element tags = child(extra, "tags");
                if (tags != null) for (Element tag : children(tags, "tag")) {
                    if ("LogicDraft.PrimaryKey".equals(tag.getAttribute("name"))
                            && "true".equalsIgnoreCase(tag.getAttribute("value"))) primaryKey = true;
                }
            }
            Map<String, Object> attr = new LinkedHashMap<>();
            attr.put("id", stableId("attribute-", id));
            attr.put("name", name);
            attr.put("type", dataType);
            attr.put("primaryKey", primaryKey);
            attr.put("nullable", primaryKey ? false : nullable);
            attributes.add(attr);
        }
        return attributes;
    }

    private static String toLogicDraftType(String eaType, String reference, String href) {
        String raw = eaType != null && !eaType.isBlank() ? eaType :
                (reference.startsWith("EAJava_") ? reference.substring(7) :
                 href.contains("#") ? href.substring(href.lastIndexOf('#') + 1) : "");
        return switch (raw.toLowerCase(Locale.ROOT)) {
            case "string", "varchar", "text", "char" -> "VARCHAR";
            case "int", "integer", "short" -> "INTEGER";
            case "long", "bigint" -> "BIGINT";
            case "real", "float", "double", "decimal" -> "DECIMAL";
            case "bool", "boolean" -> "BOOLEAN";
            case "date" -> "DATE";
            case "datetime", "timestamp" -> "TIMESTAMP";
            default -> null;
        };
    }

    private static Map<String, Element> extensionClasses(Element extension) {
        Map<String, Element> result = new HashMap<>();
        Element group = child(extension, "elements");
        if (group != null) for (Element item : children(group, "element")) {
            if ("uml:Class".equals(type(item)) || "uml:AssociationClass".equals(type(item)))
                result.put(xref(item), item);
        }
        return result;
    }

    private static Map<String, Map<String, Object>> readPositions(Element extension, String packageId,
            List<String> warnings) {
        Map<String, Map<String, Object>> result = new HashMap<>();
        Element group = child(extension, "diagrams");
        if (group == null) { warnings.add("No hay diagrama visual; se propondrán posiciones."); return result; }
        Element selected = null;
        for (Element diagram : children(group, "diagram")) {
            Element model = child(diagram, "model");
            if (model != null && packageId.equals(model.getAttribute("package"))) {
                if (selected == null) selected = diagram;
                else warnings.add("Hay varios diagramas; se usarán las posiciones del primero.");
            }
        }
        if (selected == null) { warnings.add("No se encontró un diagrama de este paquete."); return result; }
        Element drawings = child(selected, "elements");
        if (drawings == null) return result;
        for (Element drawing : children(drawings, "element")) {
            String geometry = drawing.getAttribute("geometry");
            Integer left = geometryValue(geometry, "Left");
            Integer top = geometryValue(geometry, "Top");
            if (left != null && top != null)
                result.putIfAbsent(drawing.getAttribute("subject"), Map.of("x", left, "y", top));
        }
        return result;
    }

    private static Integer geometryValue(String geometry, String key) {
        for (String segment : geometry.split(";")) if (segment.startsWith(key + "=")) {
            try { return Integer.valueOf(segment.substring(key.length() + 1)); }
            catch (NumberFormatException ignored) { return null; }
        }
        return null;
    }

    private static String stableId(String prefix, String source) {
        return prefix + UUID.nameUUIDFromBytes(source.getBytes(StandardCharsets.UTF_8));
    }
    private static String xid(Element element) { return element.getAttributeNS(XMI, "id"); }
    private static String xref(Element element) { return element.getAttributeNS(XMI, "idref"); }
    private static String type(Element element) { return element.getAttributeNS(XMI, "type"); }
    private static Element child(Element parent, String name) {
        for (Element item : children(parent, name)) return item;
        return null;
    }
    private static List<Element> children(Element parent, String name) {
        List<Element> result = new ArrayList<>();
        NodeList children = parent.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node node = children.item(i);
            if (node instanceof Element item && name.equals(item.getLocalName() == null
                    ? item.getTagName() : item.getLocalName())) result.add(item);
        }
        return result;
    }
    private static ResponseStatusException badRequest(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }
    private static String physicalName(String name, int maxLength) {
        String normalized = java.text.Normalizer.normalize(
                name,
                java.text.Normalizer.Form.NFD
        )
                .replaceAll("\\p{M}+", "")
                .toLowerCase(java.util.Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "_")
                .replaceAll("^_+|_+$", "");

        if (normalized.isEmpty()) {
            normalized = "elemento";
        }

        if (!Character.isLetter(normalized.charAt(0))) {
            normalized = "t_" + normalized;
        }

        return normalized.substring(
                0,
                Math.min(normalized.length(), maxLength)
        );
    }
}
