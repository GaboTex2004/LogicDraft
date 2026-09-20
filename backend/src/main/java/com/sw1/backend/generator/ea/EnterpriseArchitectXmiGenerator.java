package com.sw1.backend.generator.ea;

import com.sw1.backend.ai.diagram.dto.AttributeDefinition;
import com.sw1.backend.ai.diagram.dto.DiagramContext;
import com.sw1.backend.ai.diagram.dto.EntityDefinition;
import com.sw1.backend.ai.diagram.dto.RelationshipDefinition;
import com.sw1.backend.ai.diagram.model.DiagramCardinality;
import com.sw1.backend.ai.diagram.validation.DiagramContextMapper;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.HashSet;
import java.util.UUID;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import org.springframework.stereotype.Component;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

/** XMI 2.1 plus Enterprise Architect's own element, connector and diagram metadata. */
@Component
public class EnterpriseArchitectXmiGenerator {
    private static final String XMI = "http://schema.omg.org/spec/XMI/2.1";
    private static final String UML = "http://schema.omg.org/spec/UML/2.1";
    private static final String APPEARANCE = "BackColor=-1;BorderColor=-1;BorderWidth=-1;FontColor=-1;VSwimLanes=1;HSwimLanes=1;BorderStyle=0;";
    private static final String DIAGRAM_STYLE = "ShowPrivate=1;ShowProtected=1;ShowPublic=1;HideRelationships=0;Locked=0;Border=1;HighlightForeign=1;PackageContents=1;ShowDetails=0;Orientation=P;Zoom=100;ShowTags=0;VisibleAttributeDetail=0;ShowIcons=1;HideProps=0;HideAtts=0;HideOps=0;HideStereo=0;HideElemStereo=0;ConnectorNotation=UML 2.1;ShowShape=1;AdvancedElementProps=1;AdvancedFeatureProps=1;AdvancedConnectorProps=1;";
    private static final String DIAGRAM_STYLE2 = "ExcludeRTF=0;DocAll=0;HideQuals=0;AttPkg=1;SuppressFOC=1;SwimlanesActive=1;TConnectorNotation=UML 2.1;AdvancedElementProps=1;AdvancedFeatureProps=1;AdvancedConnectorProps=1;";

    private record EntityRef(EntityDefinition entity, String id, int localId) {}
    private record RelationRef(RelationshipDefinition relation, String id, EntityRef source, EntityRef target,
                               int localId, DiagramCardinality sourceCardinality,
                               DiagramCardinality targetCardinality, String associationClassId) {}
    private record AssociationPlan(String name, String sourceName, String targetName,
                                   String sourceRelationId, String targetRelationId,
                                   DiagramCardinality sourceCardinality,
                                   DiagramCardinality targetCardinality) {}

    public byte[] generate(EnterpriseArchitectExportSourceService.ExportSnapshot snapshot) {
        try {
            var project = snapshot.proyecto();
            var diagram = snapshot.diagrama();
            Map<String, Object> content = diagram.contenido();
            DiagramContext context = DiagramContextMapper.fromDocument(content);
            Map<String, AssociationPlan> plans = associationPlans(content, context);
            var factory = DocumentBuilderFactory.newInstance();
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

            Element model = add(xml, root, "uml:Model");
            attr(model, "xmi:type", "uml:Model");
            model.setAttribute("name", "EA_Model");
            model.setAttribute("visibility", "public");
            String packageId = id("EAPK", "project:" + project.getId());
            Element pkg = add(xml, model, "packagedElement");
            attr(pkg, "xmi:type", "uml:Package");
            attr(pkg, "xmi:id", packageId);
            pkg.setAttribute("name", project.getNombre());
            pkg.setAttribute("visibility", "public");

            Map<String, EntityRef> entities = new LinkedHashMap<>();
            Map<String, Element> umlElements = new LinkedHashMap<>();
            int entityLocalId = 2;
            for (EntityDefinition entity : context.entities()) {
                String classId = id("EAID", "project:" + project.getId() + ":entity:" + entity.name());
                EntityRef ref = new EntityRef(entity, classId, entityLocalId++);
                entities.put(DiagramContextMapper.key(entity.name()), ref);
                Element klass = add(xml, pkg, "packagedElement");
                attr(klass, "xmi:type", plans.containsKey(DiagramContextMapper.key(entity.name()))
                        ? "uml:AssociationClass" : "uml:Class");
                umlElements.put(DiagramContextMapper.key(entity.name()), klass);
                attr(klass, "xmi:id", classId);
                klass.setAttribute("name", entity.name());
                klass.setAttribute("visibility", "public");
                for (AttributeDefinition attribute : entity.attributes()) {
                    String attributeId = attributeId(classId, attribute);
                    Element property = add(xml, klass, "ownedAttribute");
                    attr(property, "xmi:type", "uml:Property");
                    attr(property, "xmi:id", attributeId);
                    property.setAttribute("name", attribute.name());
                    property.setAttribute("visibility", "private");
                    property.setAttribute("isStatic", "false");
                    property.setAttribute("isReadOnly", "false");
                    property.setAttribute("isDerived", "false");
                    property.setAttribute("isOrdered", "false");
                    property.setAttribute("isUnique", "true");
                    Element type = add(xml, property, "type");
                    String dataTypeName = attribute.dataType().name();
                    if ("Date".equals(dataTypeName) || "DateTime".equals(dataTypeName)) {
                        // EA's own Java primitive definitions retain date types as editable UML attributes.
                        attr(type, "xmi:idref", "EAJava_" + dataTypeName);
                    } else {
                        attr(type, "xmi:type", "uml:PrimitiveType");
                        type.setAttribute("href", UML + "/uml.xml#" + umlType(dataTypeName));
                    }
                    multiplicity(xml, property, attribute.primaryKey() || !attribute.nullable() ? 1 : 0, 1, attributeId);
                }
            }

            List<RelationRef> relations = new ArrayList<>();
            Set<String> convertedRelationshipIds = new HashSet<>();
            for (AssociationPlan plan : plans.values()) {
                convertedRelationshipIds.add(plan.sourceRelationId());
                convertedRelationshipIds.add(plan.targetRelationId());
            }
            int relationLocalId = 1;
            for (RelationshipDefinition relationship : context.relationships()) {
                if (convertedRelationshipIds.contains(relationship.id())) continue;
                EntityRef source = entities.get(DiagramContextMapper.key(relationship.sourceEntity()));
                EntityRef target = entities.get(DiagramContextMapper.key(relationship.targetEntity()));
                if (source == null || target == null) throw new IllegalArgumentException("Relación con entidad inexistente");
                String relationId = id("EAID", "project:" + project.getId() + ":relationship:" + (relationLocalId - 1));
                RelationRef ref = new RelationRef(relationship, relationId, source, target, relationLocalId++,
                        relationship.sourceCardinality(), relationship.targetCardinality(), null);
                relations.add(ref);
                Element association = add(xml, pkg, "packagedElement");
                attr(association, "xmi:type", "uml:Association");
                attr(association, "xmi:id", relationId);
                if (relationship.name() != null) association.setAttribute("name", relationship.name());
                association.setAttribute("visibility", "public");
                // EA's export uses memberEnd references and ownedEnd properties for both ends.
                String sourceEnd = endId(relationId, "src");
                String targetEnd = endId(relationId, "dst");
                attr(add(xml, association, "memberEnd"), "xmi:idref", targetEnd);
                attr(add(xml, association, "memberEnd"), "xmi:idref", sourceEnd);
                associationEnd(xml, association, targetEnd, target.id(), relationship.targetCardinality(), relationId);
                associationEnd(xml, association, sourceEnd, source.id(), relationship.sourceCardinality(), relationId);
            }
            // In EA an AssociationClass is one UML element, linked to one endpoint-to-endpoint
            // connector. The two relational foreign-key edges are replaced ONLY in this export.
            for (AssociationPlan plan : plans.values()) {
                EntityRef associationClass = entities.get(DiagramContextMapper.key(plan.name()));
                EntityRef source = entities.get(DiagramContextMapper.key(plan.sourceName()));
                EntityRef target = entities.get(DiagramContextMapper.key(plan.targetName()));
                if (associationClass == null || source == null || target == null) {
                    throw new IllegalArgumentException("Asociación incompleta: " + plan.name());
                }
                Element association = umlElements.get(DiagramContextMapper.key(plan.name()));
                String connectorId = id("EAID", "project:" + project.getId() + ":associationclass:" + plan.name());
                String sourceEnd = endId(connectorId, "src");
                String targetEnd = endId(connectorId, "dst");
                attr(add(xml, association, "memberEnd"), "xmi:idref", targetEnd);
                attr(add(xml, association, "memberEnd"), "xmi:idref", sourceEnd);
                associationEnd(xml, association, targetEnd, target.id(), plan.targetCardinality(), associationClass.id());
                associationEnd(xml, association, sourceEnd, source.id(), plan.sourceCardinality(), associationClass.id());
                relations.add(new RelationRef(null, connectorId, source, target, relationLocalId++,
                        plan.sourceCardinality(), plan.targetCardinality(), associationClass.id()));
            }

            addEaExtension(xml, root, project.getId(), project.getNombre(), packageId, content, entities, relations);

            var transformers = TransformerFactory.newInstance();
            transformers.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            var transformer = transformers.newTransformer();
            transformer.setOutputProperty(OutputKeys.ENCODING, "UTF-8");
            transformer.setOutputProperty(OutputKeys.INDENT, "yes");
            var out = new ByteArrayOutputStream();
            transformer.transform(new DOMSource(xml), new StreamResult(out));
            return out.toByteArray();
        } catch (Exception exception) {
            throw new IllegalStateException("No se pudo generar el archivo XMI.", exception);
        }
    }

    private static void addEaExtension(Document xml, Element root, Long projectId, String projectName,
                                       String packageId, Map<String, Object> content,
                                       Map<String, EntityRef> entities, List<RelationRef> relations) {
        Element extension = xml.createElementNS(XMI, "xmi:Extension");
        extension.setAttribute("extender", "Enterprise Architect");
        extension.setAttribute("extenderID", "6.5");
        root.appendChild(extension);
        Element elements = add(xml, extension, "elements");
        Element packageExtension = extensionElement(xml, elements, packageId, "uml:Package", projectName);
        Element packageModel = add(xml, packageExtension, "model");
        packageModel.setAttribute("package2", packageId.replaceFirst("^EAPK_", "EAID_"));
        packageModel.setAttribute("package", "EAPK_00000000_0000_0000_0000_000000000000");
        packageModel.setAttribute("tpos", "1");
        packageModel.setAttribute("ea_localid", "1");
        packageModel.setAttribute("ea_eleType", "package");
        Element packageProperties = add(xml, packageExtension, "properties");
        packageProperties.setAttribute("isSpecification", "false");
        packageProperties.setAttribute("sType", "Package");
        packageProperties.setAttribute("nType", "0");
        packageProperties.setAttribute("scope", "public");
        commonElementMetadata(xml, packageExtension, "Model");
        add(xml, packageExtension, "packageproperties").setAttribute("version", "1.0");
        add(xml, packageExtension, "paths");
        add(xml, packageExtension, "times");
        add(xml, packageExtension, "flags").setAttribute("iscontrolled", "FALSE");

        int attributeLocalId = 1;
        for (EntityRef entity : entities.values()) {
            Element klass = extensionElement(xml, elements, entity.id(), "uml:Class", entity.entity().name());
            Element elementModel = add(xml, klass, "model");
            elementModel.setAttribute("package", packageId);
            elementModel.setAttribute("tpos", "0");
            elementModel.setAttribute("ea_localid", String.valueOf(entity.localId()));
            elementModel.setAttribute("ea_eleType", "element");
            Element properties = add(xml, klass, "properties");
            properties.setAttribute("isSpecification", "false");
            properties.setAttribute("sType", "Class");
            boolean associationClass = plansForExtension(content, entity.entity().name());
            properties.setAttribute("nType", associationClass ? "17" : "0");
            properties.setAttribute("scope", "public");
            properties.setAttribute("isRoot", "false");
            properties.setAttribute("isLeaf", "false");
            properties.setAttribute("isAbstract", "false");
            properties.setAttribute("isActive", "false");
            commonElementMetadata(xml, klass, projectName);
            if (associationClass) {
                for (RelationRef relation : relations) {
                    if (entity.id().equals(relation.associationClassId())) {
                        ((Element) klass.getElementsByTagName("extendedProperties").item(0))
                                .setAttribute("conID", relation.id());
                        break;
                    }
                }
            }
            Element attributes = add(xml, klass, "attributes");
            int order = 0;
            for (AttributeDefinition attribute : entity.entity().attributes()) {
                String aid = attributeId(entity.id(), attribute);
                Element a = add(xml, attributes, "attribute");
                attr(a, "xmi:idref", aid);
                a.setAttribute("name", attribute.name());
                a.setAttribute("scope", "Private");
                add(xml, a, "initial");
                add(xml, a, "documentation");
                Element aModel = add(xml, a, "model");
                aModel.setAttribute("ea_localid", String.valueOf(attributeLocalId++));
                aModel.setAttribute("ea_guid", guid(aid));
                Element aProperties = add(xml, a, "properties");
                aProperties.setAttribute("type", eaType(attribute.dataType().name()));
                aProperties.setAttribute("collection", "false");
                aProperties.setAttribute("static", "0");
                aProperties.setAttribute("duplicates", "0");
                aProperties.setAttribute("changeability", "changeable");
                add(xml, a, "coords").setAttribute("ordered", "0");
                Element containment = add(xml, a, "containment");
                containment.setAttribute("containment", "Not Specified");
                containment.setAttribute("position", String.valueOf(order++));
                add(xml, a, "stereotype");
                Element bounds = add(xml, a, "bounds");
                bounds.setAttribute("lower", attribute.primaryKey() || !attribute.nullable() ? "1" : "0");
                bounds.setAttribute("upper", "1");
                add(xml, a, "options");
                add(xml, a, "style");
                add(xml, a, "styleex").setAttribute("value", "volatile=0;");
                Element tags = add(xml, a, "tags");
                if (attribute.primaryKey()) {
                    // An ordinary tagged value preserves PK intent without pretending it is an EA database column.
                    Element tag = add(xml, tags, "tag");
                    tag.setAttribute("name", "LogicDraft.PrimaryKey");
                    tag.setAttribute("value", "true");
                }
                add(xml, a, "xrefs");
            }
            Element links = add(xml, klass, "links");
            for (RelationRef relation : relations) {
                if (relation.associationClassId() != null && entity.id().equals(relation.associationClassId())) continue;
                if (relation.source() != entity && relation.target() != entity) continue;
                Element link = add(xml, links, "Association");
                attr(link, "xmi:id", relation.id());
                link.setAttribute("start", relation.source().id());
                link.setAttribute("end", relation.target().id());
            }
        }

        Element connectors = add(xml, extension, "connectors");
        for (RelationRef relation : relations) {
            Element connector = add(xml, connectors, "connector");
            attr(connector, "xmi:idref", relation.id());
            connectorEnd(xml, connector, "source", relation.source(), relation.sourceCardinality());
            connectorEnd(xml, connector, "target", relation.target(), relation.targetCardinality());
            add(xml, connector, "model").setAttribute("ea_localid", String.valueOf(relation.localId()));
            Element properties = add(xml, connector, "properties");
            properties.setAttribute("ea_type", "Association");
            if (relation.associationClassId() != null) properties.setAttribute("subtype", "Class");
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
            labels.setAttribute("lt", "+" + cardinality(relation.sourceCardinality()));
            labels.setAttribute("rt", "+" + cardinality(relation.targetCardinality()));
            Element connectorExtended = add(xml, connector, "extendedProperties");
            connectorExtended.setAttribute("virtualInheritance", "0");
            if (relation.associationClassId() != null) {
                connectorExtended.setAttribute("associationclass", relation.associationClassId());
                for (EntityRef entity : entities.values()) {
                    if (entity.id().equals(relation.associationClassId())) {
                        connectorExtended.setAttribute("privatedata1", String.valueOf(entity.localId()));
                        break;
                    }
                }
            }
            add(xml, connector, "style");
            add(xml, connector, "xrefs");
            add(xml, connector, "tags");
        }

        Element primitives = add(xml, extension, "primitivetypes");
        Element primitivePackage = add(xml, primitives, "packagedElement");
        attr(primitivePackage, "xmi:type", "uml:Package");
        attr(primitivePackage, "xmi:id", "EAPrimitiveTypesPackage");
        primitivePackage.setAttribute("name", "EA_PrimitiveTypes_Package");
        primitivePackage.setAttribute("visibility", "public");
        Element javaTypes = add(xml, primitivePackage, "packagedElement");
        attr(javaTypes, "xmi:type", "uml:Package");
        attr(javaTypes, "xmi:id", "EAJavaTypesPackage");
        javaTypes.setAttribute("name", "EA_Java_Types_Package");
        javaTypes.setAttribute("visibility", "public");
        for (String javaType : List.of("Date", "DateTime")) {
            Element primitive = add(xml, javaTypes, "packagedElement");
            attr(primitive, "xmi:type", "uml:PrimitiveType");
            attr(primitive, "xmi:id", "EAJava_" + javaType);
            primitive.setAttribute("name", javaType);
            primitive.setAttribute("visibility", "public");
        }
        add(xml, extension, "profiles");

        Element diagrams = add(xml, extension, "diagrams");
        Element diagram = add(xml, diagrams, "diagram");
        attr(diagram, "xmi:id", id("EAID", "project:" + projectId + ":diagram"));
        Element diagramModel = add(xml, diagram, "model");
        diagramModel.setAttribute("package", packageId);
        diagramModel.setAttribute("localID", "1");
        diagramModel.setAttribute("owner", packageId);
        Element diagramProperties = add(xml, diagram, "properties");
        diagramProperties.setAttribute("name", projectName);
        diagramProperties.setAttribute("type", "Logical");
        Element diagramProject = add(xml, diagram, "project");
        diagramProject.setAttribute("author", "LogicDraft");
        diagramProject.setAttribute("version", "1.0");
        add(xml, diagram, "style1").setAttribute("value", DIAGRAM_STYLE);
        add(xml, diagram, "style2").setAttribute("value", DIAGRAM_STYLE2);
        add(xml, diagram, "swimlanes").setAttribute("value", "locked=false;orientation=0;width=0;inbar=false;names=false;");
        add(xml, diagram, "matrixitems").setAttribute("value", "locked=false;matrixactive=false;swimlanesactive=true;");
        add(xml, diagram, "extendedProperties");
        Element drawings = add(xml, diagram, "elements");
        if (!(content.get("nodes") instanceof List<?> nodes)) throw new IllegalArgumentException("Nodos inválidos");
        int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE;
        for (Object raw : nodes) {
            Map<?, ?> node = asMap(raw);
            Map<?, ?> position = asMap(node.get("position"));
            minX = Math.min(minX, coordinate(position.get("x")));
            minY = Math.min(minY, coordinate(position.get("y")));
        }
        if (nodes.isEmpty()) { minX = 0; minY = 0; }
        int sequence = 1;
        for (Object raw : nodes) {
            Map<?, ?> node = asMap(raw);
            Map<?, ?> data = asMap(node.get("data"));
            Object name = data.get("name");
            if (!(name instanceof String entityName)) throw new IllegalArgumentException("Nombre de nodo inválido");
            EntityRef entity = entities.get(DiagramContextMapper.key(entityName));
            if (entity == null) throw new IllegalArgumentException("Entidad sin clase UML: " + entityName);
            Map<?, ?> position = asMap(node.get("position"));
            int left = 100 + coordinate(position.get("x")) - minX;
            int top = 100 + coordinate(position.get("y")) - minY;
            int attributeCount = entity.entity().attributes().size();
            int width = Math.max(170, Math.min(350, entityName.length() * 9 + 65));
            int height = Math.max(90, 48 + attributeCount * 24);
            Element drawing = add(xml, drawings, "element");
            drawing.setAttribute("geometry", "Left=" + left + ";Top=" + top + ";Right=" + (left + width)
                    + ";Bottom=" + (top + height) + ";");
            drawing.setAttribute("subject", entity.id());
            drawing.setAttribute("seqno", String.valueOf(sequence++));
            drawing.setAttribute("style", "ImageID=0;DUID=" + shortId(entity.id()) + ";");
        }
        long associationClasses = relations.stream().filter(r -> r.associationClassId() != null).count();
        if (!(content.get("edges") instanceof List<?> edges)
                || edges.size() != relations.size() + associationClasses)
            throw new IllegalArgumentException("Relaciones UML y visuales no coinciden");
        for (RelationRef relation : relations) {
            Element drawing = add(xml, drawings, "element");
            drawing.setAttribute("geometry", "SX=0;SY=0;EX=0;EY=0;EDGE=2;$LLB=;LLT=;LMB=;LRT=;LRB=;IRHS=;ILHS=;Path=;");
            drawing.setAttribute("subject", relation.id());
            drawing.setAttribute("style", "Mode=3;EOID=" + shortId(relation.target().id()) + ";SOID="
                    + shortId(relation.source().id()) + ";Color=-1;LWidth=0;Hidden=0;");
        }
    }

    private static boolean plansForExtension(Map<String, Object> content, String entityName) {
        if (!(content.get("nodes") instanceof List<?> nodes)) return false;
        for (Object raw : nodes) {
            Map<?, ?> node = asMap(raw);
            Map<?, ?> data = asMap(node.get("data"));
            if (entityName.equals(data.get("name")) && data.get("association") instanceof Map<?, ?> assoc
                    && "MANY_TO_MANY_ASSOCIATION".equals(assoc.get("kind"))) return true;
        }
        return false;
    }

    private static Map<String, AssociationPlan> associationPlans(Map<String, Object> content, DiagramContext context) {
        Map<String, AssociationPlan> result = new LinkedHashMap<>();
        if (!(content.get("nodes") instanceof List<?> nodes)) throw new IllegalArgumentException("Nodos inválidos");
        Map<String, String> namesById = new LinkedHashMap<>();
        for (Object raw : nodes) {
            Map<?, ?> node = asMap(raw);
            Map<?, ?> data = asMap(node.get("data"));
            if (!(node.get("id") instanceof String nodeId) || !(data.get("name") instanceof String name))
                throw new IllegalArgumentException("Entidad inválida");
            namesById.put(nodeId, name);
        }
        Map<String, RelationshipDefinition> relationships = new LinkedHashMap<>();
        for (RelationshipDefinition relation : context.relationships()) {
            if (relation.id() != null) relationships.put(relation.id(), relation);
        }
        for (Object raw : nodes) {
            Map<?, ?> node = asMap(raw);
            Map<?, ?> data = asMap(node.get("data"));
            if (!(data.get("association") instanceof Map<?, ?> assoc)) continue;
            if (!"MANY_TO_MANY_ASSOCIATION".equals(assoc.get("kind"))
                    || !Boolean.TRUE.equals(assoc.get("uniquePair"))
                    || !(assoc.get("endpoints") instanceof List<?> endpoints) || endpoints.size() != 2)
                throw new IllegalArgumentException("Metadatos de asociación inválidos");
            String name = (String) data.get("name");
            Map<String, String> names = new HashMap<>();
            Map<String, String> ids = new HashMap<>();
            Map<String, DiagramCardinality> cardinalities = new HashMap<>();
            for (Object value : endpoints) {
                Map<?, ?> endpoint = asMap(value);
                if (!(endpoint.get("role") instanceof String role) || !Set.of("SOURCE", "TARGET").contains(role)
                        || !(endpoint.get("entityId") instanceof String nodeId)
                        || !(endpoint.get("relationshipId") instanceof String relationId))
                    throw new IllegalArgumentException("Extremo de asociación inválido");
                String endpointName = namesById.get(nodeId);
                RelationshipDefinition relation = relationships.get(relationId);
                if (endpointName == null || relation == null || endpointName.equals(name)
                        || !(relation.sourceEntity().equals(name) && relation.targetEntity().equals(endpointName)
                        || relation.targetEntity().equals(name) && relation.sourceEntity().equals(endpointName)))
                    throw new IllegalArgumentException("Las relaciones de la entidad asociativa no coinciden");
                if (names.putIfAbsent(role, endpointName) != null)
                    throw new IllegalArgumentException("Rol asociativo duplicado");
                ids.put(role, relationId);
                cardinalities.put(role, relation.sourceEntity().equals(name)
                        ? relation.sourceCardinality() : relation.targetCardinality());
            }
            if (!names.keySet().equals(Set.of("SOURCE", "TARGET"))
                    || names.get("SOURCE").equals(names.get("TARGET"))
                    || ids.get("SOURCE").equals(ids.get("TARGET")))
                throw new IllegalArgumentException("La asociación necesita dos extremos distintos");
            result.put(DiagramContextMapper.key(name), new AssociationPlan(name, names.get("SOURCE"), names.get("TARGET"),
                    ids.get("SOURCE"), ids.get("TARGET"), cardinalities.get("SOURCE"), cardinalities.get("TARGET")));
        }
        Set<String> consumed = new HashSet<>();
        for (AssociationPlan plan : result.values()) {
            if (!consumed.add(plan.sourceRelationId()) || !consumed.add(plan.targetRelationId()))
                throw new IllegalArgumentException("Una relación no puede pertenecer a varias clases de asociación");
        }
        return result;
    }

    private static Element extensionElement(Document xml, Element parent, String id, String type, String name) {
        Element element = add(xml, parent, "element");
        attr(element, "xmi:idref", id);
        attr(element, "xmi:type", type);
        element.setAttribute("name", name);
        element.setAttribute("scope", "public");
        return element;
    }

    private static void commonElementMetadata(Document xml, Element element, String packageName) {
        Element project = add(xml, element, "project");
        project.setAttribute("author", "LogicDraft");
        project.setAttribute("version", "1.0");
        project.setAttribute("phase", "1.0");
        project.setAttribute("complexity", "1");
        project.setAttribute("status", "Proposed");
        add(xml, element, "code").setAttribute("gentype", "<none>");
        add(xml, element, "style").setAttribute("appearance", APPEARANCE);
        add(xml, element, "tags");
        add(xml, element, "xrefs");
        Element extended = add(xml, element, "extendedProperties");
        extended.setAttribute("tagged", "0");
        extended.setAttribute("package_name", packageName);
    }

    private static void connectorEnd(Document xml, Element connector, String endName,
                                     EntityRef entity, DiagramCardinality cardinality) {
        Element end = add(xml, connector, endName);
        attr(end, "xmi:idref", entity.id());
        Element model = add(xml, end, "model");
        model.setAttribute("ea_localid", String.valueOf(entity.localId()));
        model.setAttribute("type", "Class");
        model.setAttribute("name", entity.entity().name());
        Element role = add(xml, end, "role");
        role.setAttribute("name", cardinality(cardinality));
        role.setAttribute("visibility", "Public");
        role.setAttribute("targetScope", "instance");
        Element type = add(xml, end, "type");
        type.setAttribute("aggregation", "none");
        type.setAttribute("containment", "Unspecified");
        add(xml, end, "constraints");
        Element modifiers = add(xml, end, "modifiers");
        modifiers.setAttribute("isOrdered", "false");
        modifiers.setAttribute("changeable", "none");
        modifiers.setAttribute("isNavigable", "false");
        add(xml, end, "style").setAttribute("value", "Union=0;Derived=0;AllowDuplicates=0;Owned=0;Navigable=Unspecified;");
        add(xml, end, "documentation");
        add(xml, end, "xrefs");
        add(xml, end, "tags");
    }

    private static void associationEnd(Document xml, Element association, String endId,
                                       String entityId, DiagramCardinality card, String associationId) {
        Element end = add(xml, association, "ownedEnd");
        attr(end, "xmi:type", "uml:Property");
        attr(end, "xmi:id", endId);
        end.setAttribute("name", cardinality(card));
        end.setAttribute("visibility", "public");
        end.setAttribute("association", associationId);
        end.setAttribute("aggregation", "none");
        Element type = add(xml, end, "type");
        attr(type, "xmi:idref", entityId);
        int lower = card == DiagramCardinality.ZERO_ONE || card == DiagramCardinality.ZERO_MANY ? 0 : 1;
        int upper = card == DiagramCardinality.ZERO_MANY || card == DiagramCardinality.ONE_MANY ? -1 : 1;
        multiplicity(xml, end, lower, upper, endId);
    }

    private static void multiplicity(Document xml, Element parent, int lower, int upper, String seed) {
        Element low = add(xml, parent, "lowerValue");
        attr(low, "xmi:type", "uml:LiteralInteger");
        attr(low, "xmi:id", id("EAID", seed + ":lower"));
        low.setAttribute("value", String.valueOf(lower));
        Element up = add(xml, parent, "upperValue");
        attr(up, "xmi:type", "uml:LiteralUnlimitedNatural");
        attr(up, "xmi:id", id("EAID", seed + ":upper"));
        up.setAttribute("value", upper == -1 ? "*" : String.valueOf(upper));
    }

    private static String umlType(String type) {
        return switch (type) {
            case "String" -> "String";
            case "Long", "Integer" -> "Integer";
            case "Boolean" -> "Boolean";
            case "Double" -> "Real";
            case "Date", "DateTime" -> "String"; // UML standard fallback; EA-specific metadata retains the original type.
            default -> type;
        };
    }

    private static String eaType(String type) {
        return switch (type) {
            case "Integer" -> "int";
            case "Long" -> "long";
            case "Double" -> "double";
            case "Boolean" -> "boolean";
            case "Date" -> "Date";
            case "DateTime" -> "DateTime";
            default -> "String";
        };
    }

    private static String cardinality(DiagramCardinality value) {
        return switch (value) {
            case ZERO_ONE -> "0..1";
            case ONE_ONE -> "1..1";
            case ZERO_MANY -> "0..*";
            case ONE_MANY -> "1..*";
        };
    }

    private static Map<?, ?> asMap(Object value) {
        if (!(value instanceof Map<?, ?> map)) throw new IllegalArgumentException("Documento de diagrama inválido");
        return map;
    }

    private static int coordinate(Object value) {
        if (!(value instanceof Number number) || !Double.isFinite(number.doubleValue()))
            throw new IllegalArgumentException("Coordenadas de diagrama inválidas");
        return (int) Math.round(number.doubleValue());
    }

    private static String attributeId(String classId, AttributeDefinition attr) {
        return id("EAID", classId + ":attribute:" + attr.name());
    }

    private static String endId(String relationId, String end) {
        return id("EAID", relationId + ":" + end);
    }

    private static String guid(String id) {
        String suffix = id.substring(id.indexOf('_') + 1);
        return "{" + suffix.replace('_', '-') + "}";
    }

    private static String shortId(String id) {
        return id.substring(id.indexOf('_') + 1).replace("_", "").substring(0, 8);
    }

    private static String id(String prefix, String value) {
        UUID uuid = UUID.nameUUIDFromBytes(value.getBytes(StandardCharsets.UTF_8));
        return prefix + "_" + uuid.toString().replace('-', '_').toUpperCase();
    }

    private static void attr(Element element, String name, String value) {
        element.setAttributeNS(XMI, name, value);
    }

    private static Element add(Document document, Element parent, String tag) {
        Element child = document.createElement(tag);
        parent.appendChild(child);
        return child;
    }
}
