package com.sw1.backend.generator.ea;

import com.sw1.backend.ai.diagram.model.DiagramCardinality;
import java.util.List;

/** Semantic XMI representation, independent from React Flow and persistence DTOs. */
public record NormalizedDiagram(
        String projectName,
        List<NormalizedClass> classes,
        List<NormalizedAssociation> associations,
        List<NormalizedGeneralization> generalizations,
        List<NormalizedRealization> realizations,
        List<NormalizedDependency> dependencies,
        List<String> warnings
) {
    public record Position(double x, double y) {}
    public record Size(double width, double height) {}

    public record NormalizedClass(
            String externalId,
            String name,
            boolean associationClass,
            List<NormalizedAttribute> attributes,
            Position position,
            Size size,
            String kind,
            boolean abstractType,
            List<NormalizedOperation> operations,
            List<String> literals
    ) {}

    public record NormalizedAttribute(
            String externalId,
            String name,
            String type,
            String visibility,
            boolean primaryKey,
            boolean nullable,
            boolean staticAttribute,
            String initialValue,
            String lower,
            String upper
    ) {}

    public record NormalizedOperation(
            String externalId,
            String name,
            String returnType,
            String visibility,
            boolean staticOperation,
            boolean abstractOperation,
            List<NormalizedParameter> parameters
    ) {}

    public record NormalizedParameter(
            String externalId,
            String name,
            String type
    ) {}

    public record NormalizedAssociation(
            String externalId,
            String sourceExternalId,
            String targetExternalId,
            DiagramCardinality sourceMultiplicity,
            DiagramCardinality targetMultiplicity,
            String associationClassExternalId,
            String name,
            String relationKind,
            String aggregationEnd
    ) {}

    public record NormalizedGeneralization(
            String externalId,
            String specificExternalId,
            String generalExternalId
    ) {}

    public record NormalizedRealization(
            String externalId,
            String implementingExternalId,
            String interfaceExternalId
    ) {}

    public record NormalizedDependency(
            String externalId,
            String clientExternalId,
            String supplierExternalId,
            String name
    ) {}
}
