package com.sw1.backend.generator.schema;

import com.fasterxml.jackson.annotation.JsonIgnore;
import java.util.List;

public record ApplicationSchema(
        int schemaVersion,
        String projectName,
        String applicationName,
        String technicalName,
        List<ApplicationEntity> entities,
        List<ApplicationRelationship> relationships,
        @JsonIgnore UmlStructuralModel uml) {
    public ApplicationSchema(int schemaVersion, String projectName, String applicationName, String technicalName,
                             List<ApplicationEntity> entities, List<ApplicationRelationship> relationships) {
        this(schemaVersion, projectName, applicationName, technicalName, entities, relationships,
                UmlStructuralModel.EMPTY);
    }
}
