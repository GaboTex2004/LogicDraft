package com.sw1.backend.generator.ea;

import com.sw1.backend.ai.diagram.model.DiagramCardinality;
import com.sw1.backend.generator.ea.NormalizedDiagram.NormalizedAssociation;
import com.sw1.backend.generator.ea.NormalizedDiagram.NormalizedAttribute;
import com.sw1.backend.generator.ea.NormalizedDiagram.NormalizedClass;
import com.sw1.backend.generator.ea.NormalizedDiagram.NormalizedGeneralization;
import com.sw1.backend.generator.ea.NormalizedDiagram.Position;
import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

/** Parses XMI/EA into semantic records without knowing React Flow's document shape. */
@Component
public class EnterpriseArchitectXmiParser {
    static final int MAX_BYTES = 5 * 1024 * 1024;
    private static final DiagramCardinality DEFAULT_MULTIPLICITY = DiagramCardinality.ONE_ONE;

    private record Connector(String id, String sourceId, String targetId, Element source,
                             Element target, Element labels, String associationClassId) {}

    public NormalizedDiagram parse(byte[] bytes) {
        if (bytes == null || bytes.length == 0) throw badRequest("El archivo XMI está vacío.");
        if (bytes.length > MAX_BYTES) throw badRequest("El archivo XMI supera 5 MB.");
        try {
            Document xml = parseXml(bytes);
            Element root = xml.getDocumentElement();
            String version = attribute(root, "version");
            if (!"XMI".equals(local(root)) || version == null || !version.startsWith("2.")) {
                throw badRequest("El archivo debe utilizar XMI 2.x.");
            }
            Element model = firstDescendant(root, "Model");
            if (model == null) throw badRequest("El XMI no contiene un modelo UML.");

            List<Element> packaged = descendants(model, "packagedElement");
            Element projectPackage = packaged.stream().filter(item -> "uml:Package".equals(xmiType(item)))
                    .findFirst().orElse(null);
            String projectName = projectPackage == null ? model.getAttribute("name").strip()
                    : projectPackage.getAttribute("name").strip();
            if (projectName.isBlank()) projectName = "Proyecto importado";
            if (projectName.length() > 100) throw badRequest("El nombre del modelo supera 100 caracteres.");
            String packageId = projectPackage == null ? null : xmiId(projectPackage);

            List<String> warnings = new ArrayList<>();
            Element extension = findEaExtension(root);
            if (extension == null) warnings.add("No existe metadata opcional de Enterprise Architect; se usará solamente UML estándar.");
            Map<String, Element> extensionElements = extensionElements(extension);
            Map<String, Position> positions = positions(extension, packageId, warnings);
            Map<String, String> primitiveTypes = primitiveTypes(xml);

            Map<String, Element> classElements = new LinkedHashMap<>();
            for (Element item : packaged) {
                if ("uml:Class".equals(xmiType(item)) || "uml:AssociationClass".equals(xmiType(item))) {
                    String id = requiredId(item, "Clase UML sin xmi:id.");
                    if (classElements.putIfAbsent(id, item) != null) throw badRequest("ID de clase duplicado: " + id);
                }
            }
            if (classElements.isEmpty()) throw badRequest("El modelo no contiene clases UML reconocibles.");

            List<NormalizedClass> classes = new ArrayList<>();
            Set<String> names = new HashSet<>();
            int fallbackIndex = 0;
            for (var entry : classElements.entrySet()) {
                Element item = entry.getValue();
                String name = item.getAttribute("name").strip();
                if (name.isBlank() || name.length() > 100 || !names.add(name.toLowerCase(Locale.ROOT))) {
                    throw badRequest("Clase UML sin nombre válido o con nombre duplicado.");
                }
                Position position = positions.get(entry.getKey());
                if (position == null) {
                    position = new Position(180 + (fallbackIndex % 3) * 300, 180 + (fallbackIndex / 3) * 250);
                    warnings.add("La clase " + name + " no tiene posición; se asignó una posición provisional.");
                }
                fallbackIndex++;
                classes.add(new NormalizedClass(entry.getKey(), name,
                        "uml:AssociationClass".equals(xmiType(item)),
                        attributes(item, extensionElements.get(entry.getKey()), primitiveTypes, warnings), position));
            }

            Map<String, Connector> connectors = connectors(extension, classElements, warnings);
            List<NormalizedAssociation> associations = associations(packaged, classElements, connectors, warnings);
            validateAssociationClasses(classes, associations);
            List<NormalizedGeneralization> generalizations = generalizations(classElements, warnings);
            return new NormalizedDiagram(projectName, List.copyOf(classes), List.copyOf(associations),
                    List.copyOf(generalizations), List.copyOf(warnings));
        } catch (ResponseStatusException exception) {
            throw exception;
        } catch (Exception exception) {
            throw badRequest("No se pudo interpretar el XMI: " + exception.getClass().getSimpleName());
        }
    }

    private static Document parseXml(byte[] bytes) throws Exception {
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
        return factory.newDocumentBuilder().parse(new ByteArrayInputStream(bytes));
    }

    private static List<NormalizedAttribute> attributes(Element owner, Element extension,
            Map<String, String> primitiveTypes, List<String> warnings) {
        Map<String, Element> extras = new HashMap<>();
        Element extraList = child(extension, "attributes");
        if (extraList != null) for (Element item : children(extraList, "attribute")) extras.put(xmiRef(item), item);
        List<NormalizedAttribute> result = new ArrayList<>();
        Set<String> names = new HashSet<>();
        for (Element property : children(owner, "ownedAttribute")) {
            String id = requiredId(property, "Atributo UML sin xmi:id.");
            String name = property.getAttribute("name").strip();
            if (name.isBlank() || name.length() > 100 || !names.add(name.toLowerCase(Locale.ROOT))) {
                throw badRequest("Atributo inválido o duplicado en " + owner.getAttribute("name"));
            }
            Element extra = extras.get(id);
            Element extraProperties = child(extra, "properties");
            Element type = child(property, "type");
            String reference = type == null ? "" : xmiRef(type);
            String eaType = extraProperties == null ? "" : extraProperties.getAttribute("type");
            String href = type == null ? "" : type.getAttribute("href");
            String mappedType = resolveType(reference, eaType, href, primitiveTypes);
            if (mappedType == null) {
                mappedType = "VARCHAR";
                warnings.add("Tipo desconocido en " + owner.getAttribute("name") + "." + name
                        + "; se usará VARCHAR.");
            }
            String lower = value(child(property, "lowerValue"));
            String upper = value(child(property, "upperValue"));
            Element bounds = child(extra, "bounds");
            if (lower == null && bounds != null) lower = blankToNull(bounds.getAttribute("lower"));
            if (upper == null && bounds != null) upper = blankToNull(bounds.getAttribute("upper"));
            boolean primaryKey = hasPrimaryKeyTag(extra);
            String visibility = blankToNull(property.getAttribute("visibility"));
            if (visibility == null && extra != null) visibility = blankToNull(extra.getAttribute("scope"));
            String initialValue = initialValue(property, extra);
            boolean isStatic = "true".equalsIgnoreCase(property.getAttribute("isStatic"))
                    || extraProperties != null && "1".equals(extraProperties.getAttribute("static"));
            result.add(new NormalizedAttribute(id, name, mappedType, visibility, primaryKey,
                    !primaryKey && "0".equals(lower), isStatic, initialValue, lower, upper));
        }
        return List.copyOf(result);
    }

    private static List<NormalizedAssociation> associations(List<Element> packaged,
            Map<String, Element> classes, Map<String, Connector> connectors, List<String> warnings) {
        List<NormalizedAssociation> result = new ArrayList<>();
        Set<String> usedConnectors = new HashSet<>();
        for (Element association : packaged) {
            boolean associationClass = "uml:AssociationClass".equals(xmiType(association));
            if (!associationClass && !"uml:Association".equals(xmiType(association))) continue;
            String associationId = requiredId(association, "Asociación UML sin xmi:id.");
            Connector connector = associationClass
                    ? connectors.values().stream().filter(item -> associationId.equals(item.associationClassId())).findFirst().orElse(null)
                    : connectors.get(associationId);
            List<Element> ends = children(association, "ownedEnd");
            String sourceId = connector == null ? endpointId(ends, 0) : connector.sourceId();
            String targetId = connector == null ? endpointId(ends, 1) : connector.targetId();
            if (!classes.containsKey(sourceId) || !classes.containsKey(targetId) || sourceId.equals(targetId)) {
                throw badRequest("La asociación " + associationId + " referencia extremos inexistentes.");
            }
            Element sourceEnd = endFor(ends, sourceId);
            Element targetEnd = endFor(ends, targetId);
            String relationName = relationshipName(classes, sourceId, targetId);
            DiagramCardinality sourceCard = multiplicity(sourceEnd, connector == null ? null : connector.source(),
                    connector == null || connector.labels() == null ? null : connector.labels().getAttribute("lt"),
                    relationName, warnings);
            DiagramCardinality targetCard = multiplicity(targetEnd, connector == null ? null : connector.target(),
                    connector == null || connector.labels() == null ? null : connector.labels().getAttribute("rt"),
                    relationName, warnings);
            String externalId = connector == null ? associationId : connector.id();
            if (connector != null) usedConnectors.add(connector.id());
            result.add(new NormalizedAssociation(externalId, sourceId, targetId, sourceCard, targetCard,
                    associationClass ? associationId : null, blankToNull(association.getAttribute("name"))));
        }
        for (Connector connector : connectors.values()) {
            if (!usedConnectors.contains(connector.id())) {
                warnings.add("El conector EA " + connector.id() + " no tiene una asociación UML compatible y fue omitido.");
            }
        }
        return List.copyOf(result);
    }

    private static DiagramCardinality multiplicity(Element ownedEnd, Element connectorEnd, String label,
            String relationName, List<String> warnings) {
        // Semantic UML data wins over EA presentation metadata: bounds, ownedEnd name,
        // connector role and, only as a last visual fallback, the rendered label.
        List<String> candidates = new ArrayList<>();
        if (ownedEnd != null) {
            String lower = value(child(ownedEnd, "lowerValue"));
            String upper = value(child(ownedEnd, "upperValue"));
            if (lower != null || upper != null) candidates.add((lower == null ? "1" : lower) + ".." + (upper == null ? "1" : upper));
            candidates.add(ownedEnd.getAttribute("name"));
        }
        Element role = child(connectorEnd, "role");
        if (role != null) candidates.add(role.getAttribute("name"));
        candidates.add(label);
        String malformed = null;
        for (String candidate : candidates) {
            if (candidate == null || candidate.isBlank()) continue;
            DiagramCardinality normalized = normalizeMultiplicity(candidate);
            if (normalized != null) return normalized;
            if (malformed == null) malformed = candidate.strip();
        }
        if (malformed == null) {
            warnings.add("No se encontró multiplicidad en la relación " + relationName
                    + "; se usará 1..1.");
        } else {
            warnings.add("Multiplicidad no reconocida \"" + malformed + "\" en la relación "
                    + relationName + "; se usará 1..1.");
        }
        return DEFAULT_MULTIPLICITY;
    }

    static DiagramCardinality normalizeMultiplicity(String raw) {
        if (raw == null) return null;
        String value = raw.strip().replace(" ", "");
        while (value.startsWith("+")) value = value.substring(1);
        return switch (value) {
            case "1", "1..1" -> DiagramCardinality.ONE_ONE;
            case "0..1" -> DiagramCardinality.ZERO_ONE;
            case "*", "0..*", "0..N", "0..n" -> DiagramCardinality.ZERO_MANY;
            case "1..*", "1..N", "1..n" -> DiagramCardinality.ONE_MANY;
            default -> null;
        };
    }

    private static Map<String, Connector> connectors(Element extension, Map<String, Element> classes,
            List<String> warnings) {
        Map<String, Connector> result = new LinkedHashMap<>();
        Element group = child(extension, "connectors");
        if (group == null) {
            if (extension != null) warnings.add("La extensión de Enterprise Architect no contiene conectores; se usará UML estándar.");
            return result;
        }
        for (Element connector : children(group, "connector")) {
            String id = xmiRef(connector);
            Element source = child(connector, "source"), target = child(connector, "target");
            Element properties = child(connector, "properties");
            if (id.isBlank() || source == null || target == null || properties == null
                    || !"Association".equalsIgnoreCase(properties.getAttribute("ea_type"))) continue;
            String sourceId = xmiRef(source), targetId = xmiRef(target);
            if (!classes.containsKey(sourceId) || !classes.containsKey(targetId)) {
                throw badRequest("El conector " + id + " referencia una clase inexistente.");
            }
            Element extended = child(connector, "extendedProperties");
            String associationClassId = extended == null ? null : blankToNull(extended.getAttribute("associationclass"));
            Connector item = new Connector(id, sourceId, targetId, source, target, child(connector, "labels"), associationClassId);
            if (result.putIfAbsent(id, item) != null) throw badRequest("Conector EA duplicado: " + id);
        }
        return result;
    }

    private static List<NormalizedGeneralization> generalizations(Map<String, Element> classes, List<String> warnings) {
        List<NormalizedGeneralization> result = new ArrayList<>();
        for (var entry : classes.entrySet()) for (Element generalization : children(entry.getValue(), "generalization")) {
            String id = xmiId(generalization);
            String generalId = blankToNull(generalization.getAttribute("general"));
            Element general = child(generalization, "general");
            if (generalId == null && general != null) generalId = blankToNull(xmiRef(general));
            if (generalId != null && classes.containsKey(generalId)) {
                result.add(new NormalizedGeneralization(id.isBlank() ? entry.getKey() + ":generalization" : id,
                        entry.getKey(), generalId));
            } else {
                warnings.add("Una generalización de " + entry.getValue().getAttribute("name")
                        + " no pudo resolverse y fue omitida.");
            }
        }
        return List.copyOf(result);
    }

    private static void validateAssociationClasses(List<NormalizedClass> classes,
            List<NormalizedAssociation> associations) {
        for (NormalizedClass item : classes) if (item.associationClass()) {
            long count = associations.stream().filter(association -> item.externalId()
                    .equals(association.associationClassExternalId())).count();
            if (count != 1) throw badRequest("La clase asociativa " + item.name() + " debe pertenecer a una asociación.");
        }
    }

    private static Map<String, String> primitiveTypes(Document xml) {
        Map<String, String> result = new HashMap<>();
        for (Element item : allElements(xml)) {
            if (!"uml:PrimitiveType".equals(xmiType(item))) continue;
            String id = xmiId(item), name = item.getAttribute("name");
            if (!id.isBlank() && !name.isBlank()) result.put(id, name);
        }
        return result;
    }

    private static String resolveType(String reference, String eaType, String href,
            Map<String, String> primitiveTypes) {
        String local = primitiveTypes.get(reference);
        String raw = local != null ? local : !eaType.isBlank() ? eaType
                : href.contains("#") ? href.substring(href.lastIndexOf('#') + 1)
                : reference.startsWith("EAJava_") ? reference.substring("EAJava_".length()) : "";
        return switch (raw.toLowerCase(Locale.ROOT)) {
            case "string", "varchar", "char" -> "VARCHAR";
            case "text" -> "TEXT";
            case "int", "integer", "short" -> "INTEGER";
            case "long", "bigint", "unlimitednatural" -> "BIGINT";
            case "real", "float", "double", "decimal", "numeric" -> "DECIMAL";
            case "bool", "boolean" -> "BOOLEAN";
            case "date" -> "DATE";
            case "datetime", "timestamp" -> "TIMESTAMP";
            default -> null;
        };
    }

    private static Map<String, Element> extensionElements(Element extension) {
        Map<String, Element> result = new HashMap<>();
        Element elements = child(extension, "elements");
        if (elements != null) for (Element item : children(elements, "element")) {
            String id = xmiRef(item);
            if (!id.isBlank()) result.put(id, item);
        }
        return result;
    }

    private static Map<String, Position> positions(Element extension, String packageId, List<String> warnings) {
        Map<String, Position> result = new HashMap<>();
        Element diagrams = child(extension, "diagrams");
        if (diagrams == null) return result;
        Element selected = null;
        for (Element diagram : children(diagrams, "diagram")) {
            Element model = child(diagram, "model");
            if (selected == null && (packageId == null || model != null && packageId.equals(model.getAttribute("package")))) {
                selected = diagram;
            }
        }
        if (selected == null) return result;
        Element elements = child(selected, "elements");
        if (elements == null) return result;
        for (Element item : children(elements, "element")) {
            Integer left = geometry(item.getAttribute("geometry"), "Left");
            Integer top = geometry(item.getAttribute("geometry"), "Top");
            if (left != null && top != null) result.putIfAbsent(item.getAttribute("subject"), new Position(left, top));
        }
        return result;
    }

    private static Integer geometry(String value, String key) {
        for (String segment : value.split(";")) if (segment.startsWith(key + "=")) {
            try { return Integer.valueOf(segment.substring(key.length() + 1)); }
            catch (NumberFormatException ignored) { return null; }
        }
        return null;
    }

    private static Element findEaExtension(Element root) {
        for (Element item : children(root, "Extension")) {
            if (item.getAttribute("extender").toLowerCase(Locale.ROOT).contains("enterprise architect")) return item;
        }
        return null;
    }

    private static String endpointId(List<Element> ends, int index) {
        if (index >= ends.size()) return null;
        Element type = child(ends.get(index), "type");
        return type == null ? null : blankToNull(xmiRef(type));
    }

    private static Element endFor(List<Element> ends, String entityId) {
        return ends.stream().filter(end -> entityId != null && entityId.equals(endpointId(List.of(end), 0)))
                .findFirst().orElse(null);
    }

    private static String relationshipName(Map<String, Element> classes, String sourceId, String targetId) {
        return classes.get(sourceId).getAttribute("name") + " ↔ " + classes.get(targetId).getAttribute("name");
    }

    private static boolean hasPrimaryKeyTag(Element extra) {
        Element tags = child(extra, "tags");
        if (tags == null) return false;
        for (Element tag : children(tags, "tag")) if ("LogicDraft.PrimaryKey".equals(tag.getAttribute("name"))
                && "true".equalsIgnoreCase(tag.getAttribute("value"))) return true;
        return false;
    }

    private static String initialValue(Element property, Element extra) {
        String direct = blankToNull(property.getAttribute("default"));
        if (direct != null) return direct;
        Element defaultValue = child(property, "defaultValue");
        String standard = value(defaultValue);
        if (standard != null) return standard;
        Element initial = child(extra, "initial");
        if (initial == null) return null;
        String value = blankToNull(initial.getAttribute("value"));
        return value != null ? value : blankToNull(initial.getTextContent());
    }

    private static String value(Element element) {
        if (element == null) return null;
        String value = blankToNull(element.getAttribute("value"));
        if (value != null) return value;
        return blankToNull(element.getTextContent());
    }

    private static String requiredId(Element element, String message) {
        String id = xmiId(element);
        if (id.isBlank()) throw badRequest(message);
        return id;
    }

    private static String xmiId(Element element) { return element == null ? "" : nullToEmpty(attribute(element, "id")); }
    private static String xmiRef(Element element) { return element == null ? "" : nullToEmpty(attribute(element, "idref")); }
    private static String xmiType(Element element) { return element == null ? "" : nullToEmpty(attribute(element, "type")); }

    private static String attribute(Element element, String localName) {
        if (element == null) return null;
        for (int index = 0; index < element.getAttributes().getLength(); index++) {
            Node item = element.getAttributes().item(index);
            if (localName.equals(item.getLocalName()) || localName.equals(item.getNodeName())) return item.getNodeValue();
        }
        return null;
    }

    private static String local(Element element) {
        return element.getLocalName() == null ? element.getTagName().replaceFirst("^.*:", "") : element.getLocalName();
    }

    private static Element child(Element parent, String name) {
        if (parent == null) return null;
        for (Element item : children(parent, name)) return item;
        return null;
    }

    private static List<Element> children(Element parent, String name) {
        List<Element> result = new ArrayList<>();
        if (parent == null) return result;
        NodeList nodes = parent.getChildNodes();
        for (int index = 0; index < nodes.getLength(); index++) {
            if (nodes.item(index) instanceof Element item && name.equals(local(item))) result.add(item);
        }
        return result;
    }

    private static Element firstDescendant(Element parent, String name) {
        return descendants(parent, name).stream().findFirst().orElse(null);
    }

    private static List<Element> descendants(Element parent, String name) {
        List<Element> result = new ArrayList<>();
        NodeList nodes = parent.getElementsByTagNameNS("*", name);
        for (int index = 0; index < nodes.getLength(); index++) if (nodes.item(index) instanceof Element item) result.add(item);
        if (result.isEmpty()) {
            NodeList fallback = parent.getElementsByTagName(name);
            for (int index = 0; index < fallback.getLength(); index++) if (fallback.item(index) instanceof Element item) result.add(item);
        }
        return result;
    }

    private static List<Element> allElements(Document document) {
        List<Element> result = new ArrayList<>();
        NodeList nodes = document.getElementsByTagName("*");
        for (int index = 0; index < nodes.getLength(); index++) if (nodes.item(index) instanceof Element item) result.add(item);
        return result;
    }

    private static String blankToNull(String value) { return value == null || value.isBlank() ? null : value.strip(); }
    private static String nullToEmpty(String value) { return value == null ? "" : value; }
    private static ResponseStatusException badRequest(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }
}
