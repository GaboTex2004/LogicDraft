package com.sw1.backend.generator.ea;

import com.sw1.backend.ai.diagram.model.DiagramCardinality;
import java.util.List;

/** Semantic XMI representation, independent from React Flow and persistence DTOs. */
public record NormalizedDiagram(
        String projectName,
        List<NormalizedClass> classes,
        List<NormalizedAssociation> associations,
        List<NormalizedGeneralization> generalizations,
        List<String> warnings
) {
    public record Position(double x, double y) {}

    public record NormalizedClass(
            String externalId,
            String name,
            boolean associationClass,
            List<NormalizedAttribute> attributes,
            Position position
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

    public record NormalizedAssociation(
            String externalId,
            String sourceExternalId,
            String targetExternalId,
            DiagramCardinality sourceMultiplicity,
            DiagramCardinality targetMultiplicity,
            String associationClassExternalId,
            String name
    ) {}

    public record NormalizedGeneralization(
            String externalId,
            String specificExternalId,
            String generalExternalId
    ) {}
}
